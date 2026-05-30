/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.proxy;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Builds <strong>bare</strong> {@link McpClientTransport} instances that the proxy
 * controllers will wire by hand.
 *
 * <p>
 * Unlike {@code LoopbackMcpClientFactory} this does <em>not</em> wrap the transport in
 * {@code McpClient.sync(...)} — the proxy needs raw access to {@code connect(handler)},
 * {@code sendMessage(...)} and {@code closeGracefully()} because it is a frame-level
 * relay, not an MCP client.
 *
 * <p>
 * For SSE we install a loopback-friendly endpoint validator so a server may advertise a
 * message endpoint on a different port (common when both the proxy and the upstream MCP
 * server run on the same machine on random ports).
 */
public class ProxyTransportFactory {

	private final ObjectMapper objectMapper;

	public ProxyTransportFactory() {
		this(new ObjectMapper());
	}

	public ProxyTransportFactory(ObjectMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
	}

	/**
	 * Builds an SSE client transport that targets the supplied {@code sseUri}.
	 *
	 * <p>
	 * The URI's scheme/host/port form the transport's base URI; the URI's path is the SSE
	 * endpoint. Example: {@code http://127.0.0.1:8080/sse} → base
	 * {@code http://127.0.0.1:8080}, SSE endpoint {@code /sse}.
	 */
	public McpClientTransport openSse(URI sseUri) {
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		String baseUri = stripPath(sseUri);
		String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse" : sseUri.getRawPath();
		return HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(noopValidator())
			.build();
	}

	/**
	 * Builds a streamable-HTTP transport that targets the supplied {@code mcpUri}.
	 *
	 * <p>
	 * Same URI breakdown semantics as {@link #openSse(URI)}.
	 */
	public McpClientTransport openStreamable(URI mcpUri) {
		if (mcpUri == null) {
			throw new IllegalArgumentException("mcpUri must not be null");
		}
		String baseUri = stripPath(mcpUri);
		String path = (mcpUri.getRawPath() == null || mcpUri.getRawPath().isBlank()) ? "/mcp" : mcpUri.getRawPath();
		return HttpClientStreamableHttpTransport.builder(baseUri).endpoint(path).build();
	}

	/**
	 * Spawns a stdio MCP server and returns a bare {@link StdioClientTransport}.
	 * @param command executable + args; first element is the command, rest are args; must
	 * be non-empty
	 * @param env optional extra env vars; may be {@code null} or empty
	 */
	public McpClientTransport openStdio(List<String> command, Map<String, String> env) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must contain at least the executable");
		}
		ServerParameters.Builder builder = ServerParameters.builder(command.get(0));
		if (command.size() > 1) {
			builder.args(command.subList(1, command.size()));
		}
		if (env != null && !env.isEmpty()) {
			builder.env(env);
		}
		ServerParameters parameters = builder.build();
		return new StdioClientTransport(parameters, new JacksonMcpJsonMapper(objectMapper));
	}

	/** Returns {@code scheme://host[:port]} with no path. */
	private static String stripPath(URI uri) {
		StringBuilder sb = new StringBuilder();
		sb.append(uri.getScheme()).append("://").append(uri.getHost());
		if (uri.getPort() > 0) {
			sb.append(":").append(uri.getPort());
		}
		return sb.toString();
	}

	/**
	 * No-op SSE message-endpoint validator. SDK 0.18.2 ships a same-origin validator by
	 * default; for proxied / loopback connections this is too strict.
	 */
	private static SseMessageEndpointValidator noopValidator() {
		return (sseEndpoint, messageEndpoint) -> {
			/* trust the upstream server */
		};
	}

}
