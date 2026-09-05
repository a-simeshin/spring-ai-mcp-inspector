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

package io.inspector.mcp.core.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * @author Artem Simeshin
 */
public class ProxyTransportFactory {

	/** Names the threads of {@link #SHARED_HTTP_EXECUTOR}. Must stay declared first. */
	private static final AtomicLong THREAD_SEQ = new AtomicLong();

	/**
	 * One executor shared by every HTTP transport this factory builds.
	 *
	 * <p>
	 * The MCP SDK's builders take an {@link java.net.http.HttpClient.Builder}, never a
	 * finished client, so each proxy session gets an {@link java.net.http.HttpClient} of
	 * its own — and on Java 17 an {@code HttpClient} has no {@code close()}: its selector
	 * and its private worker pool live until the instance is garbage collected. Left
	 * alone, each session therefore costs about five threads that outlive it. A hundred
	 * open/close cycles on a two-core Linux runner reached 120 of the JVM's 154 threads
	 * and wedged the proxy well before the loop finished (measured on
	 * {@code ProxySessionLifecycleIT}); the same loop on a developer laptop never
	 * noticed, because the sessions were reclaimed faster than they piled up.
	 *
	 * <p>
	 * Handing every client the same executor collapses that to one selector thread per
	 * session, which the JVM can carry until GC catches up. The threads are daemons so a
	 * lingering client cannot hold up JVM exit, and the pool is cached so it shrinks back
	 * on its own once the sessions are gone.
	 */
	private static final Executor SHARED_HTTP_EXECUTOR = Executors.newCachedThreadPool((runnable) -> {
		final Thread thread = new Thread(runnable, "mcp-inspector-proxy-http-" + THREAD_SEQ.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private final JsonMapper objectMapper;

	public ProxyTransportFactory() {
		this(new JsonMapper());
	}

	public ProxyTransportFactory(final JsonMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
	}

	/**
	 * Builds an SSE client transport that targets the supplied {@code sseUri}.
	 *
	 * <p>
	 * The URI's scheme/host/port form the transport's base URI; the URI's path is the SSE
	 * endpoint. Example: {@code http://127.0.0.1:8080/sse} → base
	 * {@code http://127.0.0.1:8080}, SSE endpoint {@code /sse}.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @return a configured {@link McpClientTransport} for SSE
	 */
	public McpClientTransport openSse(final URI sseUri) {
		return openSse(sseUri, null, null);
	}

	/**
	 * Builds an SSE client transport that targets {@code sseUri} and forwards the
	 * supplied {@code authorization} header plus any {@code customHeaders} to the
	 * upstream MCP server on every outbound request.
	 *
	 * <p>
	 * Same URI breakdown semantics as {@link #openSse(URI)}. Headers are injected via the
	 * SDK's {@code httpRequestCustomizer} hook so they ride on both the SSE connect and
	 * the message POSTs.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @param authorization the inbound {@code Authorization} header value to forward, or
	 * {@code null} / blank to omit
	 * @param customHeaders additional headers to forward (may be {@code null} or empty)
	 * @return a configured {@link McpClientTransport} for SSE
	 */
	public McpClientTransport openSse(final URI sseUri, final String authorization,
			final Map<String, String> customHeaders) {
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final String baseUri = stripPath(sseUri);
		final String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse"
				: sseUri.getRawPath();
		final HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(noopValidator())
			.customizeClient((client) -> client.executor(SHARED_HTTP_EXECUTOR));
		final McpSyncHttpClientRequestCustomizer customizer = headerCustomizer(authorization, customHeaders);
		if (customizer != null) {
			builder.httpRequestCustomizer(customizer);
		}
		return builder.build();
	}

	/**
	 * Builds an SSE client transport wrapped in a lightweight preflight probe.
	 *
	 * <p>
	 * The returned transport performs an HTTP HEAD-based preflight before delegating to
	 * the inner {@link HttpClientSseClientTransport}. This avoids creating an orphaned
	 * upstream SSE session — the HEAD probe never opens a stream. If the server rejects
	 * HEAD with 405, a GET fallback is used with a header-only body handler that cancels
	 * immediately on response headers.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @return a configured {@link McpClientTransport} for SSE with preflight
	 * @see SsePreflightTransport
	 */
	public McpClientTransport buildSse(final URI sseUri) {
		return buildSse(sseUri, null, null);
	}

	/**
	 * Builds an SSE client transport wrapped in a lightweight preflight probe, with the
	 * supplied {@code authorization} header and {@code customHeaders} forwarded to the
	 * upstream MCP server on every outbound request.
	 *
	 * <p>
	 * Same preflight semantics as {@link #buildSse(URI)}. Headers are injected via the
	 * SDK's {@code httpRequestCustomizer} hook so they ride on both the preflight probe
	 * and the real transport's requests.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @param authorization the inbound {@code Authorization} header value to forward, or
	 * {@code null} / blank to omit
	 * @param customHeaders additional headers to forward (may be {@code null} or empty)
	 * @return a configured {@link McpClientTransport} for SSE with preflight
	 */
	public McpClientTransport buildSse(final URI sseUri, final String authorization,
			final Map<String, String> customHeaders) {
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final McpClientTransport delegate = openSse(sseUri, authorization, customHeaders);
		final HttpClient preflightClient = HttpClient.newBuilder().executor(SHARED_HTTP_EXECUTOR).build();
		final HttpRequest.Builder requestTemplate = HttpRequest.newBuilder();
		final McpSyncHttpClientRequestCustomizer customizer = headerCustomizer(authorization, customHeaders);
		return new SsePreflightTransport(delegate, sseUri, requestTemplate, customizer, preflightClient);
	}

	/**
	 * Builds a streamable-HTTP transport that targets the supplied {@code mcpUri}.
	 *
	 * <p>
	 * Same URI breakdown semantics as {@link #openSse(URI)}.
	 * @param mcpUri the full MCP endpoint URI (must not be {@code null})
	 * @return a configured {@link McpClientTransport} for streamable-HTTP
	 */
	public McpClientTransport openStreamable(final URI mcpUri) {
		return openStreamable(mcpUri, null, null);
	}

	/**
	 * Builds a streamable-HTTP transport that targets {@code mcpUri} and forwards the
	 * supplied {@code authorization} header plus any {@code customHeaders} to the
	 * upstream MCP server on every outbound request.
	 *
	 * <p>
	 * Same URI breakdown semantics as {@link #openStreamable(URI)}. Headers are injected
	 * via the SDK's {@code httpRequestCustomizer} hook.
	 * @param mcpUri the full MCP endpoint URI (must not be {@code null})
	 * @param authorization the inbound {@code Authorization} header value to forward, or
	 * {@code null} / blank to omit
	 * @param customHeaders additional headers to forward (may be {@code null} or empty)
	 * @return a configured {@link McpClientTransport} for streamable-HTTP
	 */
	public McpClientTransport openStreamable(final URI mcpUri, final String authorization,
			final Map<String, String> customHeaders) {
		if (mcpUri == null) {
			throw new IllegalArgumentException("mcpUri must not be null");
		}
		final String baseUri = stripPath(mcpUri);
		final String path = (mcpUri.getRawPath() == null || mcpUri.getRawPath().isBlank()) ? "/mcp"
				: mcpUri.getRawPath();
		final HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUri)
			.endpoint(path)
			.customizeClient((client) -> client.executor(SHARED_HTTP_EXECUTOR));
		final McpSyncHttpClientRequestCustomizer customizer = headerCustomizer(authorization, customHeaders);
		if (customizer != null) {
			builder.httpRequestCustomizer(customizer);
		}
		return builder.build();
	}

	/**
	 * Spawns a stdio MCP server and returns a bare {@link StdioClientTransport}.
	 * @param command executable + args; first element is the command, rest are args; must
	 * be non-empty
	 * @param env optional extra env vars; may be {@code null} or empty
	 * @return a configured {@link McpClientTransport} backed by a stdio child process
	 */
	public McpClientTransport openStdio(final List<String> command, final Map<String, String> env) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must contain at least the executable");
		}
		final ServerParameters.Builder builder = ServerParameters.builder(command.get(0));
		if (command.size() > 1) {
			builder.args(command.subList(1, command.size()));
		}
		if (env != null && !env.isEmpty()) {
			builder.env(env);
		}
		final ServerParameters parameters = builder.build();
		return new StdioClientTransport(parameters, new JacksonMcpJsonMapper(this.objectMapper));
	}

	/**
	 * Builds an {@link McpSyncHttpClientRequestCustomizer} that sets the forwarded
	 * {@code Authorization} header and any custom headers on every outbound request to
	 * the upstream MCP server. Returns {@code null} when there is nothing to forward so
	 * callers can skip installing a customizer entirely.
	 * @param authorization the {@code Authorization} value to forward (ignored when
	 * blank)
	 * @param customHeaders extra headers to forward (ignored when {@code null} / empty)
	 * @return a customizer, or {@code null} when no headers need forwarding
	 */
	private static McpSyncHttpClientRequestCustomizer headerCustomizer(final String authorization,
			final Map<String, String> customHeaders) {
		final boolean hasAuth = authorization != null && !authorization.isBlank();
		final boolean hasCustom = customHeaders != null && !customHeaders.isEmpty();
		if (!hasAuth && !hasCustom) {
			return null;
		}
		return (builder, method, endpoint, body, context) -> {
			if (hasAuth) {
				builder.setHeader("Authorization", authorization);
			}
			if (hasCustom) {
				customHeaders.forEach((name, value) -> {
					if (name != null && !name.isBlank() && value != null) {
						try {
							builder.setHeader(name, value);
						}
						catch (final IllegalArgumentException ignored) {
							// restricted header names are silently skipped
						}
					}
				});
			}
		};
	}

	/**
	 * Returns {@code scheme://host[:port]} with no path.
	 * @param uri the source URI
	 * @return base URI string without path, query or fragment
	 */
	private static String stripPath(final URI uri) {
		final StringBuilder sb = new StringBuilder();
		sb.append(uri.getScheme()).append("://").append(uri.getHost());
		if (uri.getPort() > 0) {
			sb.append(":").append(uri.getPort());
		}
		return sb.toString();
	}

	/**
	 * No-op SSE message-endpoint validator. SDK 0.18.2 ships a same-origin validator by
	 * default; for proxied / loopback connections this is too strict.
	 * @return a validator that accepts any message endpoint unconditionally
	 */
	private static SseMessageEndpointValidator noopValidator() {
		return (sseEndpoint, messageEndpoint) -> {
			/* trust the upstream server */
		};
	}

}
