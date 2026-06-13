/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.proxy.McpProxy;
import io.inspector.mcp.core.proxy.ProxySession;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.proxy.ProxyTransportFactory;

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
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class SseProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(SseProxyController.class);

	private final ProxySessionRegistry registry;

	private final ProxyTransportFactory transportFactory;

	private final McpProxy mcpProxy;

	private final JsonMapper objectMapper;

	private final McpInspectorProperties properties;

	public SseProxyController(final ProxySessionRegistry registry, final ProxyTransportFactory transportFactory,
			final McpProxy mcpProxy, final JsonMapper objectMapper, final McpInspectorProperties properties) {
		this.registry = registry;
		this.transportFactory = transportFactory;
		this.mcpProxy = mcpProxy;
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
		this.properties = properties;
	}

	private McpInspectorProperties.Timeouts resolveTimeouts() {
		return (this.properties != null) ? this.properties.getTimeouts() : new McpInspectorProperties.Timeouts();
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
	 * @param transportType the transport type ({@code sse}, {@code streamable-http}, or
	 * {@code stdio})
	 * @param url the target URL for SSE or streamable-HTTP transports
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openSse(
			@RequestParam(value = "transportType", required = false, defaultValue = "sse") final String transportType,
			@RequestParam(value = "url", required = false) final String url,
			@RequestParam(value = "command", required = false) final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env) {
		return openProxiedSession(transportType, url, command, args, env);
	}

	/**
	 * Convenience alias for stdio targets. Upstream exposes {@code GET /stdio} too.
	 * @param command the executable for stdio transport
	 * @param args the arguments for stdio transport
	 * @param env the environment variables for stdio transport as JSON
	 * @return the {@link SseEmitter} for the opened session
	 */
	@GetMapping(path = "/stdio", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter openStdio(@RequestParam("command") final String command,
			@RequestParam(value = "args", required = false) final String args,
			@RequestParam(value = "env", required = false) final String env) {
		return openProxiedSession("stdio", null, command, args, env);
	}

	/**
	 * Posts a JSON-RPC frame from the browser into the session's {@code browserToTarget}
	 * sink, which forwards to the upstream transport.
	 * @param sessionId the proxy session identifier
	 * @param body the JSON-RPC frame to forward
	 * @return 202 on success, 404 if the session is unknown, 500 on emit failure
	 */
	@PostMapping(path = "/message", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> postMessage(@RequestParam("sessionId") final String sessionId,
			@RequestBody final JsonNode body) {
		final ProxySession session = this.registry.get(sessionId);
		if (session == null) {
			return ResponseEntity.notFound().build();
		}
		final Sinks.EmitResult result = session.browserToTarget().tryEmitNext(body);
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

	private SseEmitter openProxiedSession(final String transportType, final String url, final String command,
			final String args, final String env) {
		final String sessionId = UUID.randomUUID().toString();
		final SseEmitter emitter = new SseEmitter(resolveTimeouts().getSseSession().toMillis());

		final McpClientTransport target;
		try {
			target = buildTargetTransport(transportType, url, command, args, env);
		}
		catch (final Exception ex) {
			LOG.warn("proxy[{}] failed to build target transport: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
			return emitter;
		}

		// browserToTarget stays unicast (single consumer in McpProxy);
		// targetToBrowser is multicast-replay so the new Streamable-HTTP code can
		// attach per-request awaiters AND keep the long-lived GET /mcp SSE stream
		// working. For the SSE proxy this is a strict superset of the previous
		// unicast contract.
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(256);
		final ProxySession session = new ProxySession(sessionId, target, browserToTarget, targetToBrowser);
		this.registry.put(session);

		// SSE prologue — tells SSEClientTransport on the browser where to POST.
		try {
			final String messageEndpoint = this.properties.getProxyPath() + "/message?sessionId=" + sessionId;
			emitter.send(SseEmitter.event().name("endpoint").data(messageEndpoint));
		}
		catch (final IOException ex) {
			LOG.warn("proxy[{}] failed to send endpoint event: {}", sessionId, ex.toString());
			this.registry.removeAndClose(sessionId);
			emitter.completeWithError(ex);
			return emitter;
		}

		// target → browser pump
		targetToBrowser.asFlux().subscribe((frame) -> sendMessageEvent(emitter, sessionId, frame), (err) -> {
			LOG.warn("proxy[{}] target stream errored: {}", sessionId, err.toString());
			emitter.completeWithError(err);
		}, () -> emitter.complete());

		emitter.onCompletion(() -> this.registry.removeAndClose(sessionId));
		emitter.onTimeout(() -> this.registry.removeAndClose(sessionId));
		emitter.onError((ex) -> this.registry.removeAndClose(sessionId));

		// Kick off the proxy. This call subscribes the browser->target pump
		// and registers the inbound handler on the target transport.
		this.mcpProxy.start(session).subscribe((ignored) -> {
		}, (err) -> {
			LOG.warn("proxy[{}] failed to start mcp proxy: {}", sessionId, err.toString());
			this.registry.removeAndClose(sessionId);
			emitter.completeWithError(err);
		});

		return emitter;
	}

	private void sendMessageEvent(final SseEmitter emitter, final String sessionId, final JsonNode frame) {
		try {
			emitter.send(SseEmitter.event().name("message").data(this.objectMapper.writeValueAsString(frame)));
		}
		catch (final IOException ex) {
			LOG.warn("proxy[{}] failed to send SSE message: {}", sessionId, ex.toString());
			emitter.completeWithError(ex);
		}
	}

	private McpClientTransport buildTargetTransport(final String transportType, final String url, final String command,
			final String args, final String env) throws Exception {
		final String type = (transportType != null) ? transportType.toLowerCase() : "sse";
		return switch (type) {
			case "sse" -> {
				if (url == null || url.isBlank()) {
					throw new IllegalArgumentException("missing required 'url' query parameter for SSE transport");
				}
				yield this.transportFactory.openSse(URI.create(url));
			}
			case "streamable-http" -> {
				if (url == null || url.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'url' query parameter for streamable-http transport");
				}
				yield this.transportFactory.openStreamable(URI.create(url));
			}
			case "stdio" -> {
				if (command == null || command.isBlank()) {
					throw new IllegalArgumentException(
							"missing required 'command' query parameter for stdio transport");
				}
				final List<String> argv = parseArgv(command, args);
				final Map<String, String> environment = parseEnv(env);
				yield this.transportFactory.openStdio(argv, environment);
			}
			default -> throw new IllegalArgumentException("unsupported transportType: " + transportType);
		};
	}

	/**
	 * Tokenize {@code command} into [exe, ...] and append shell-split {@code args}.
	 * @param command the executable command
	 * @param args optional whitespace-separated arguments
	 * @return the full argument vector
	 */
	private static List<String> parseArgv(final String command, final String args) {
		final List<String> argv = new java.util.ArrayList<>();
		argv.add(command.trim());
		if (args != null && !args.isBlank()) {
			// upstream uses shell-quote; we do a plain whitespace split — good enough
			// for the inspector "stdio target" form which accepts simple args.
			argv.addAll(Arrays.asList(args.trim().split("\\s+")));
		}
		return argv;
	}

	private Map<String, String> parseEnv(final String env) {
		if (env == null || env.isBlank()) {
			return Map.of();
		}
		try {
			final JsonNode node = this.objectMapper.readTree(env);
			final Map<String, String> out = new LinkedHashMap<>();
			node.properties().forEach((e) -> out.put(e.getKey(), e.getValue().asText()));
			return out;
		}
		catch (final Exception ex) {
			LOG.warn("proxy: ignoring malformed env JSON: {}", ex.toString());
			return new HashMap<>();
		}
	}

}
