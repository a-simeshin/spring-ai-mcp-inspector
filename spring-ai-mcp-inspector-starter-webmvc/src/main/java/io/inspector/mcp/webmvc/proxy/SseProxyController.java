/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

/**
 * Upstream-compatible proxy endpoints for SSE-style sessions.
 *
 * <p>
 * Two routes:
 *
 * <ul>
 * <li>{@code GET /mcp-inspector-api/sse} — opens a server-sent-events stream that bridges
 * the browser to the target transport (SSE, Streamable-HTTP <em>or</em> stdio when
 * {@code transportType=stdio}).</li>
 * <li>{@code POST /mcp-inspector-api/message?sessionId=...} — pushes a single JSON-RPC
 * frame from the browser into the session's {@code browserToTarget} sink.</li>
 * <li>{@code GET /mcp-inspector-api/stdio} — convenience alias for opening a stdio target
 * (upstream exposes it on a separate path).</li>
 * </ul>
 *
 * <p>
 * The SSE response opens with an {@code event: endpoint} prologue that tells the
 * browser-side {@code SSEClientTransport} where to POST messages. Subsequent frames from
 * the target are streamed as {@code event: message}.
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class SseProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(SseProxyController.class);

	/** SSE inactivity timeout — generous because MCP servers may idle for minutes. */
	private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L; // 30 minutes

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final ObjectMapper objectMapper;

	private final McpInspectorProperties properties;

	public SseProxyController(ProxySessionRegistry registry, ProxyTransportFactory transportFactory, McpProxy mcpProxy,
			ObjectMapper objectMapper, McpInspectorProperties properties) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
		this.properties = properties;
	}

	/**
	 * Opens an SSE stream and creates a new proxy session backed by a target MCP
	 * transport.
	 *
	 * <p>
	 * Query params:
	 *
	 * <ul>
	 * <li>{@code transportType}: {@code sse} | {@code streamable-http} |
	 * {@code stdio}</li>
	 * <li>{@code url}: target URL for sse/streamable</li>
	 * <li>{@code command}, {@code args}, {@code env}: stdio target</li>
	 * </ul>
	 */
	@GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openSse(
			@RequestParam(value = "transportType", required = false, defaultValue = "sse") String transportType,
			@RequestParam(value = "url", required = false) String url,
			@RequestParam(value = "command", required = false) String command,
			@RequestParam(value = "args", required = false) String args,
			@RequestParam(value = "env", required = false) String env) {
		return openProxiedSession(transportType, url, command, args, env);
	}

	/** Convenience alias for stdio targets. Upstream exposes {@code GET /stdio} too. */
	@GetMapping(path = "/stdio", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openStdio(@RequestParam(value = "command") String command,
			@RequestParam(value = "args", required = false) String args,
			@RequestParam(value = "env", required = false) String env) {
		return openProxiedSession("stdio", null, command, args, env);
	}

	/**
	 * Posts a JSON-RPC frame from the browser into the session's {@code browserToTarget}
	 * sink, which forwards to the upstream transport.
	 */
	@PostMapping(path = "/message", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> postMessage(@RequestParam("sessionId") String sessionId, @RequestBody JsonNode body) {
		ProxySession session = registry.get(sessionId);
		if (session == null) {
			return ResponseEntity.notFound().build();
		}
		Sinks.EmitResult result = session.browserToTarget().tryEmitNext(body);
		if (result.isFailure()) {
			LOG.warn("proxy[{}] /message emit failure: {}", sessionId, result.name());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
		session.touch();
		return ResponseEntity.accepted().build();
	}

	// ---------------------------------------------------------------------
	// session bring-up
	// ---------------------------------------------------------------------

	private SseEmitter openProxiedSession(String transportType, String url, String command, String args, String env) {
		String sessionId = UUID.randomUUID().toString();
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

		McpClientTransport target;
		try {
			target = buildTargetTransport(transportType, url, command, args, env);
		}
		catch (Exception ex) {
			LOG.warn("proxy[{}] failed to build target transport: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
			return emitter;
		}

		// browserToTarget stays unicast (single consumer in McpProxy);
		// targetToBrowser is multicast-replay so the new Streamable-HTTP code can
		// attach per-request awaiters AND keep the long-lived GET /mcp SSE stream
		// working. For the SSE proxy this is a strict superset of the previous
		// unicast contract.
		Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		registry.put(session);

		// SSE prologue — tells SSEClientTransport on the browser where to POST.
		try {
			String messageEndpoint = properties.getProxyPath() + "/message?sessionId=" + sessionId;
			emitter.send(SseEmitter.event().name("endpoint").data(messageEndpoint));
		}
		catch (IOException ex) {
			LOG.warn("proxy[{}] failed to send endpoint event: {}", sessionId, ex.toString());
			registry.removeAndClose(sessionId);
			emitter.completeWithError(ex);
			return emitter;
		}

		// target → browser pump
		targetToBrowser.asFlux().subscribe(frame -> sendMessageEvent(emitter, sessionId, frame), err -> {
			LOG.warn("proxy[{}] target stream errored: {}", sessionId, err.toString());
			emitter.completeWithError(err);
		}, () -> emitter.complete());

		emitter.onCompletion(() -> registry.removeAndClose(sessionId));
		emitter.onTimeout(() -> registry.removeAndClose(sessionId));
		emitter.onError(ex -> registry.removeAndClose(sessionId));

		// Kick off the proxy. This call subscribes the browser->target pump
		// and registers the inbound handler on the target transport.
		mcpProxy.start(session).subscribe(ignored -> {
		}, err -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			registry.removeAndClose(sessionId);
			emitter.completeWithError(err);
		});

		return emitter;
	}

	private void sendMessageEvent(SseEmitter emitter, String sessionId, JsonNode frame) {
		try {
			emitter.send(SseEmitter.event().name("message").data(objectMapper.writeValueAsString(frame)));
		}
		catch (IOException ex) {
			LOG.warn("proxy[{}] failed to send SSE message: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
		}
	}

	private McpClientTransport buildTargetTransport(String transportType, String url, String command, String args,
			String env) throws Exception {
		String type = (transportType == null) ? "sse" : transportType.toLowerCase();
		return switch (type) {
			case "sse" -> {
				if (url == null || url.isBlank()) {
					throw new IllegalArgumentException("missing required 'url' query parameter for SSE transport");
				}
				yield transportFactory.openSse(URI.create(url));
			}
			case "streamable-http" -> {
				if (url == null || url.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'url' query parameter for streamable-http transport");
				}
				yield transportFactory.openStreamable(URI.create(url));
			}
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'command' query parameter for stdio transport");
				}
				List<String> argv = parseArgv(command, args);
				Map<String, String> environment = parseEnv(env);
				yield transportFactory.openStdio(argv, environment);
			}
			default -> throw new IllegalArgumentException("unsupported transportType: " + transportType);
		};
	}

	/** Tokenize {@code command} into [exe, ...] and append shell-split {@code args}. */
	private static List<String> parseArgv(String command, String args) {
		List<String> argv = new java.util.ArrayList<>();
		argv.add(command.trim());
		if (args != null && !args.isBlank()) {
			// upstream uses shell-quote; we do a plain whitespace split — good enough
			// for the inspector "stdio target" form which accepts simple args.
			argv.addAll(Arrays.asList(args.trim().split("\\s+")));
		}
		return argv;
	}

	private Map<String, String> parseEnv(String env) {
		if (env == null || env.isBlank()) {
			return Map.of();
		}
		try {
			JsonNode node = objectMapper.readTree(env);
			Map<String, String> out = new LinkedHashMap<>();
			node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
			return out;
		}
		catch (Exception ex) {
			LOG.warn("proxy: ignoring malformed env JSON: {}", ex.toString());
			return new HashMap<>();
		}
	}

}
