/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Builds {@link McpSyncClient} instances that connect to the <strong>same JVM</strong>
 * via the loopback HTTP interface ({@code http://host:port}).
 *
 * <p>
 * Important: MCP SDK 0.18.2 introduced a default {@link SseMessageEndpointValidator
 * same-origin validator} on the SSE transport. For loopback we override it with a no-op
 * so a server-advertised message endpoint pointing to {@code http://127.0.0.1:<random>}
 * is accepted unconditionally.
 *
 * <p>
 * SDK 0.18.2 exposes no client-side stateless-mode flag — the same
 * {@link HttpClientStreamableHttpTransport} is used for both {@code STREAMABLE} and
 * {@code STATELESS} servers; the server-side decides protocol semantics.
 */
public class LoopbackMcpClientFactory {

	/**
	 * Builds a {@link McpSyncClient} for the legacy SSE transport.
	 * @param host loopback host (e.g. {@code 127.0.0.1})
	 * @param port loopback port
	 * @param basePath optional servlet/router base path; may be {@code null} or empty
	 * @param messagePath server-advertised message-endpoint path (e.g.
	 * {@code /mcp/message}); must be non-null
	 */
	public McpSyncClient forSse(String host, int port, String basePath, String messagePath) {
		return forSse(host, port, basePath, messagePath, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forSse(String, int, String, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 */
	public McpSyncClient forSse(String host, int port, String basePath, String messagePath,
			InspectorClientHandlers handlers) {
		String baseUri = buildBaseUri(host, port);
		String ssePath = joinPath(basePath, "/sse");

		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(loopbackNoopValidator())
			.build();

		return applyHandlers(McpClient.sync(transport), handlers).build();
	}

	/**
	 * Builds a {@link McpSyncClient} for the streamable-HTTP transport.
	 * @param host loopback host
	 * @param port loopback port
	 * @param endpoint MCP endpoint path (e.g. {@code /mcp})
	 */
	public McpSyncClient forStreamable(String host, int port, String endpoint) {
		return buildStreamable(host, port, endpoint, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forStreamable(String, int, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 */
	public McpSyncClient forStreamable(String host, int port, String endpoint, InspectorClientHandlers handlers) {
		return buildStreamable(host, port, endpoint, handlers);
	}

	/**
	 * Builds a {@link McpSyncClient} against a stateless-HTTP MCP server.
	 *
	 * <p>
	 * SDK 0.18.2 has no client-side stateless flag — this delegates to the same builder
	 * as {@link #forStreamable}. Stateless-only behavior (no sampling / elicitation /
	 * progress) is enforced by the server.
	 */
	public McpSyncClient forStateless(String host, int port, String endpoint) {
		return buildStreamable(host, port, endpoint, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forStateless(String, int, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 */
	public McpSyncClient forStateless(String host, int port, String endpoint, InspectorClientHandlers handlers) {
		return buildStreamable(host, port, endpoint, handlers);
	}

	private McpSyncClient buildStreamable(String host, int port, String endpoint, InspectorClientHandlers handlers) {
		String baseUri = buildBaseUri(host, port);
		String path = (endpoint == null || endpoint.isBlank()) ? "/mcp" : endpoint;

		McpClientTransport transport = HttpClientStreamableHttpTransport.builder(baseUri).endpoint(path).build();

		return applyHandlers(McpClient.sync(transport), handlers).build();
	}

	/**
	 * Wires sampling / elicitation handlers (if present) on the given client spec.
	 * Returns the same spec for chaining.
	 */
	private static McpClient.SyncSpec applyHandlers(McpClient.SyncSpec spec, InspectorClientHandlers handlers) {
		if (handlers == null) {
			return spec;
		}
		if (handlers.sampling() != null) {
			spec = spec.sampling(handlers.sampling());
		}
		if (handlers.elicitation() != null) {
			spec = spec.elicitation(handlers.elicitation());
		}
		return spec;
	}

	@SuppressWarnings("unused")
	private static McpSchema.Root keepImport() {
		return null;
	}

	private static String buildBaseUri(String host, int port) {
		String h = (host == null || host.isBlank()) ? "127.0.0.1" : host;
		return "http://" + h + ":" + port;
	}

	private static String joinPath(String basePath, String suffix) {
		if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
			return suffix;
		}
		String trimmed = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
		return trimmed + suffix;
	}

	/**
	 * Loopback-friendly validator: accepts any message endpoint the server advertises.
	 *
	 * <p>
	 * Critical for SDK 0.18.2, whose default validator requires the SSE endpoint and the
	 * message endpoint to share an origin — broken for tests on random ports.
	 */
	private static SseMessageEndpointValidator loopbackNoopValidator() {
		return (sseEndpoint, messageEndpoint) -> {
			/* no-op: trust loopback */
		};
	}

}
