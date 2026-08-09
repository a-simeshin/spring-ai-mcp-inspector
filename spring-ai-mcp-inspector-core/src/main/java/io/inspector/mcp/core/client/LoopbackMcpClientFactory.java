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

package io.inspector.mcp.core.client;

import java.util.function.Function;

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
 *
 * @author Artem Simeshin
 */
public class LoopbackMcpClientFactory {

	private static final String DEFAULT_SSE_PATH = "/sse";

	/**
	 * Builds a {@link McpSyncClient} for the legacy SSE transport.
	 * @param host loopback host (e.g. {@code 127.0.0.1})
	 * @param port loopback port
	 * @param sseEndpoint the SSE endpoint path, deployment prefix included (e.g.
	 * {@code /app/sse}); defaults to {@code /sse} when blank. The message endpoint is not
	 * passed in — the server advertises it on the stream.
	 * @return a connected-ready {@link McpSyncClient} for SSE
	 */
	public McpSyncClient forSse(final String host, final int port, final String sseEndpoint) {
		return forSse(host, port, sseEndpoint, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forSse(String, int, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 * @param host loopback host (e.g. {@code 127.0.0.1})
	 * @param port loopback port
	 * @param sseEndpoint the SSE endpoint path, deployment prefix included; defaults to
	 * {@code /sse} when blank
	 * @param handlers inspector client handlers to register; may be {@code null}
	 * @return a connected-ready {@link McpSyncClient} for SSE with handlers applied
	 */
	public McpSyncClient forSse(final String host, final int port, final String sseEndpoint,
			final InspectorClientHandlers handlers) {
		final String baseUri = buildBaseUri(host, port);
		final String ssePath = (sseEndpoint == null || sseEndpoint.isBlank() || "/".equals(sseEndpoint))
				? DEFAULT_SSE_PATH : sseEndpoint;

		final HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(loopbackNoopValidator())
			.build();

		return applyHandlers(McpClient.sync(transport), handlers).build();
	}

	/**
	 * Builds a {@link McpSyncClient} for the streamable-HTTP transport.
	 * @param host loopback host
	 * @param port loopback port
	 * @param endpoint the MCP endpoint path (e.g. {@code /mcp})
	 * @return a connected-ready {@link McpSyncClient} for streamable-HTTP
	 */
	public McpSyncClient forStreamable(final String host, final int port, final String endpoint) {
		return buildStreamable(host, port, endpoint, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forStreamable(String, int, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 * @param host loopback host
	 * @param port loopback port
	 * @param endpoint the MCP endpoint path (e.g. {@code /mcp})
	 * @param handlers inspector client handlers to register; may be {@code null}
	 * @return a connected-ready {@link McpSyncClient} for streamable-HTTP with handlers
	 */
	public McpSyncClient forStreamable(final String host, final int port, final String endpoint,
			final InspectorClientHandlers handlers) {
		return buildStreamable(host, port, endpoint, handlers);
	}

	/**
	 * Builds a {@link McpSyncClient} against a stateless-HTTP MCP server.
	 *
	 * <p>
	 * SDK 0.18.2 has no client-side stateless flag — this delegates to the same builder
	 * as {@link #forStreamable}. Stateless-only behavior is enforced by the server.
	 * @param host loopback host
	 * @param port loopback port
	 * @param endpoint the MCP endpoint path (e.g. {@code /mcp})
	 * @return a connected-ready {@link McpSyncClient} for stateless-HTTP
	 */
	public McpSyncClient forStateless(final String host, final int port, final String endpoint) {
		return buildStreamable(host, port, endpoint, InspectorClientHandlers.none());
	}

	/**
	 * Variant of {@link #forStateless(String, int, String)} that wires inspector client
	 * handlers (sampling / elicitation) on the resulting client.
	 * @param host loopback host
	 * @param port loopback port
	 * @param endpoint the MCP endpoint path (e.g. {@code /mcp})
	 * @param handlers inspector client handlers to register; may be {@code null}
	 * @return a connected-ready {@link McpSyncClient} for stateless-HTTP with handlers
	 */
	public McpSyncClient forStateless(final String host, final int port, final String endpoint,
			final InspectorClientHandlers handlers) {
		return buildStreamable(host, port, endpoint, handlers);
	}

	private McpSyncClient buildStreamable(final String host, final int port, final String endpoint,
			final InspectorClientHandlers handlers) {
		final String baseUri = buildBaseUri(host, port);
		final String path = (endpoint == null || endpoint.isBlank()) ? "/mcp" : endpoint;

		final McpClientTransport transport = HttpClientStreamableHttpTransport.builder(baseUri).endpoint(path).build();

		return applyHandlers(McpClient.sync(transport), handlers).build();
	}

	/**
	 * Wires sampling / elicitation handlers (if present) on the given client spec and
	 * advertises the matching {@link McpSchema.ClientCapabilities} so the server knows
	 * exactly which elicitation modes are supported.
	 * @param spec the client spec to configure
	 * @param handlers the handlers to apply; may be {@code null}
	 * @return the same spec instance for chaining
	 */
	private static McpClient.SyncSpec applyHandlers(McpClient.SyncSpec spec, final InspectorClientHandlers handlers) {
		if (handlers == null) {
			return spec;
		}
		if (handlers.sampling() != null) {
			spec = spec.sampling(handlers.sampling());
		}
		final boolean hasForm = handlers.elicitation() != null;
		final boolean hasUrl = handlers.urlElicitation() != null;
		if (hasForm) {
			final Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitation = handlers.elicitation();
			spec = spec.elicitation(elicitation::apply);
		}
		if (hasUrl) {
			spec = spec.urlElicitation(handlers.urlElicitation()::apply);
		}
		if (hasForm || hasUrl) {
			spec = spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation(hasForm, hasUrl).build());
		}
		return spec;
	}

	@SuppressWarnings("unused")
	private static McpSchema.Root keepImport() {
		return null;
	}

	private static String buildBaseUri(final String host, final int port) {
		final String h = (host == null || host.isBlank()) ? "127.0.0.1" : host;
		return "http://" + h + ":" + port;
	}

	/**
	 * Loopback-friendly validator: accepts any message endpoint the server advertises.
	 *
	 * <p>
	 * Critical for SDK 0.18.2, whose default validator requires the SSE endpoint and the
	 * message endpoint to share an origin — broken for tests on random ports.
	 * @return a no-op validator that accepts any message endpoint unconditionally
	 */
	private static SseMessageEndpointValidator loopbackNoopValidator() {
		return (sseEndpoint, messageEndpoint) -> {
			/* no-op: trust loopback */
		};
	}

}
