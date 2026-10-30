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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;

import io.inspector.mcp.core.auth.AuthHeaders;

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
	private static final AtomicLong THREAD_SEQ = new AtomicLong();

	private static final Executor SHARED_HTTP_EXECUTOR = Executors.newCachedThreadPool((runnable) -> {
		final Thread thread = new Thread(runnable, "mcp-inspector-proxy-http-" + THREAD_SEQ.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private final ObjectMapper objectMapper;

	/** Fallback per-request SSE timeout when no explicit value is supplied. */
	private static final Duration DEFAULT_SSE_REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Per-request wall-clock budget for SSE transport calls. Applied through the
	 * transport's request customizer so the handshake {@code GET} (and every other
	 * non-POST request) fails with {@code HttpTimeoutException} when the upstream accepts
	 * the connection but never answers: the SDK itself has no such timeout. POST requests
	 * are exempt (see {@link #noPostTimeoutCustomizer}). Defaults to 10s
	 * ({@code McpInspectorProperties.Timeouts#sseRequest}).
	 */
	private final Duration sseRequestTimeout;

	public ProxyTransportFactory() {
		this(new ObjectMapper());
	}

	private static final Duration DEFAULT_SSE_PREFLIGHT_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * Wall-clock budget for the SSE preflight probe (see {@link SsePreflightTransport}).
	 * Bound to {@code spring.ai.mcp.inspector.timeouts.*} when the factory is wired by
	 * the auto-configurations.
	 */
	private final Duration ssePreflightTimeout;

	public ProxyTransportFactory(final ObjectMapper objectMapper) {
		this(objectMapper, DEFAULT_SSE_PREFLIGHT_TIMEOUT, DEFAULT_SSE_REQUEST_TIMEOUT);
	}

	/**
	 * Creates a factory whose SSE preflight probe is bounded by
	 * {@code ssePreflightTimeout}.
	 * @param objectMapper the JSON mapper backing the transports (may be {@code null})
	 * @param ssePreflightTimeout the preflight probe budget (never {@code null})
	 */
	public ProxyTransportFactory(final ObjectMapper objectMapper, final Duration ssePreflightTimeout) {
		this(objectMapper, ssePreflightTimeout, DEFAULT_SSE_REQUEST_TIMEOUT);
	}

	/**
	 * Creates a factory whose SSE transports apply {@code sseRequestTimeout} to every
	 * non-POST outbound call and whose preflight probe is bounded by
	 * {@code ssePreflightTimeout}.
	 * @param objectMapper the JSON mapper backing the transports (may be {@code null})
	 * @param ssePreflightTimeout the preflight probe budget (never {@code null})
	 * @param sseRequestTimeout the per-request SSE timeout (never {@code null})
	 */
	public ProxyTransportFactory(final ObjectMapper objectMapper, final Duration ssePreflightTimeout,
			final Duration sseRequestTimeout) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
		this.ssePreflightTimeout = (ssePreflightTimeout != null) ? ssePreflightTimeout : DEFAULT_SSE_PREFLIGHT_TIMEOUT;
		this.sseRequestTimeout = (sseRequestTimeout != null) ? sseRequestTimeout : DEFAULT_SSE_REQUEST_TIMEOUT;
	}

	/**
	 * Builds an SSE client transport wrapped in a single header-only preflight probe.
	 *
	 * <p>
	 * The URI's scheme/host/port form the transport's base URI; the URI's path is the SSE
	 * endpoint. Example: {@code http://127.0.0.1:8080/sse} means base
	 * {@code http://127.0.0.1:8080}, SSE endpoint {@code /sse}. The returned transport
	 * performs one cancelled {@code GET} probe before delegating to the inner
	 * {@link HttpClientSseClientTransport} (see {@link SsePreflightTransport}). The probe
	 * reuses the delegate's {@link HttpClient} through a {@link SharedHttpClientBuilder},
	 * so no second client (and no second selector thread) is allocated per proxy session.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @return a configured {@link McpClientTransport} for SSE with preflight
	 * @see SsePreflightTransport
	 */
	public McpClientTransport buildSse(final URI sseUri) {
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final String baseUri = stripPath(sseUri);
		final String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse"
				: sseUri.getRawPath();
		return buildSse(baseUri, normalizeSseUri(sseUri), ssePath, null);
	}

	/**
	 * Builds an SSE client transport that applies the resolved {@link AuthHeaders}
	 * (Authorization header + custom headers + query parameters) on every outbound
	 * request. The authorization value is read through {@code authorizationRef} when
	 * provided, so the OAuth2 one-retry path can refresh the token without rebuilding the
	 * transport.
	 *
	 * <p>
	 * Named {@code openSseWithAuth} (not a {@code buildSse} overload) so callers passing
	 * literal {@code null}s cannot hit an ambiguous overload against the legacy
	 * {@link #buildSse(URI)}.
	 * @param sseUri the full SSE endpoint URI (must not be {@code null})
	 * @param headers the resolved auth headers (never {@code null})
	 * @param authorizationRef live authorization value source, or {@code null} to use the
	 * static value from {@code headers}
	 * @return a configured {@link McpClientTransport} for SSE with preflight and auth
	 */
	public McpClientTransport openSseWithAuth(final URI sseUri, final AuthHeaders headers,
			final AtomicReference<String> authorizationRef) {
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final String baseUri = stripPath(sseUri);
		final String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse"
				: sseUri.getRawPath();
		final McpSyncHttpClientRequestCustomizer customizer = headerCustomizer(headers, authorizationRef);
		final String ssePathWithQuery = appendQuery(ssePath, headers.queryParams());
		return buildSse(baseUri, URI.create(baseUri).resolve(ssePathWithQuery), ssePathWithQuery, customizer);
	}

	/**
	 * Builds the SSE transport with preflight shared by {@link #buildSse(URI)} and
	 * {@link #openSseWithAuth(URI, AuthHeaders, AtomicReference)}.
	 * @param baseUri {@code scheme://host[:port]} without path
	 * @param probeTarget the URI the preflight probe sends its {@code GET} to (path plus
	 * query)
	 * @param ssePath the SSE endpoint the delegate transport connects to
	 * @param customizer optional auth header customizer, or {@code null}
	 * @return the wrapped SSE transport
	 */
	private McpClientTransport buildSse(final String baseUri, final URI probeTarget, final String ssePath,
			final McpSyncHttpClientRequestCustomizer customizer) {
		final HttpClient sharedClient = HttpClient.newBuilder()
			.executor(SHARED_HTTP_EXECUTOR)
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		final HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(noopValidator())
			.clientBuilder(new SharedHttpClientBuilder(sharedClient));
		// The request timeout, if set on a shared request template, would apply to ALL
		// requests the transport makes (the SSE GET stream AND every message POST).
		// For the POST the SDK's BodyHandlers.ofString() waits for the response body
		// to complete, but the MCP protocol delivers the response as an SSE event on
		// the stream, not in the POST body, so a healthy POST would time out. The
		// customizer removes the timeout for POST requests, keeping it only for the
		// SSE GET (where it detects silent upstream disconnects).
		builder.httpRequestCustomizer(noPostTimeoutCustomizer(customizer, this.sseRequestTimeout));
		final HttpRequest.Builder requestTemplate = HttpRequest.newBuilder().timeout(this.sseRequestTimeout);
		return new SsePreflightTransport(builder.build(), probeTarget, requestTemplate, customizer, sharedClient,
				this.ssePreflightTimeout);
	}

	/**
	 * Wraps an optional header customizer so that POST requests (message frames) never
	 * carry the per-request SSE timeout: the MCP protocol delivers the response as an SSE
	 * event on the stream, not in the POST body, so a healthy POST would time out. The
	 * GET (SSE stream) timeout is applied here instead, so it only affects the SSE stream
	 * and not message POSTs.
	 * @param delegate the existing header customizer, or {@code null}
	 * @param timeout the timeout to apply to non-POST requests
	 * @return a composite customizer
	 */
	private static McpSyncHttpClientRequestCustomizer noPostTimeoutCustomizer(
			final McpSyncHttpClientRequestCustomizer delegate, final Duration timeout) {
		return (builder, method, endpoint, body, context) -> {
			if (!"POST".equals(method)) {
				builder.timeout(timeout);
			}
			if (delegate != null) {
				delegate.customize(builder, method, endpoint, body, context);
			}
		};
	}

	/**
	 * Builds a streamable-HTTP transport that targets the supplied {@code mcpUri}.
	 *
	 * <p>
	 * Same URI breakdown semantics as {@link #buildSse(URI)}.
	 * @param mcpUri the full MCP endpoint URI (must not be {@code null})
	 * @return a configured {@link McpClientTransport} for streamable-HTTP
	 */
	public McpClientTransport openStreamable(final URI mcpUri) {
		if (mcpUri == null) {
			throw new IllegalArgumentException("mcpUri must not be null");
		}
		final String baseUri = stripPath(mcpUri);
		final String path = (mcpUri.getRawPath() == null || mcpUri.getRawPath().isBlank()) ? "/mcp"
				: mcpUri.getRawPath();
		return HttpClientStreamableHttpTransport.builder(baseUri)
			.endpoint(path)
			.customizeClient((client) -> client.executor(SHARED_HTTP_EXECUTOR))
			.build();
	}

	/**
	 * Builds a streamable-HTTP transport that applies the resolved {@link AuthHeaders}
	 * (Authorization header + custom headers + query parameters) on every outbound
	 * request. The SDK line this factory targets (mcp-core 0.18.3) has no implicit
	 * authorization auto-retry to disable, so the single explicit retry lives at the call
	 * site (D9). The authorization value is read through {@code authorizationRef} when
	 * provided, so the OAuth2 one-retry path can refresh the token without rebuilding the
	 * transport.
	 *
	 * <p>
	 * Named {@code openStreamableWithAuth} (not an {@code openStreamable} overload) so
	 * callers passing literal {@code null}s cannot hit an ambiguous overload against the
	 * legacy {@link #openStreamable(URI)}.
	 * @param mcpUri the full MCP endpoint URI (must not be {@code null})
	 * @param headers the resolved auth headers (never {@code null})
	 * @param authorizationRef live authorization value source, or {@code null} to use the
	 * static value from {@code headers}
	 * @return a configured {@link McpClientTransport} for streamable-HTTP with auth
	 */
	public McpClientTransport openStreamableWithAuth(final URI mcpUri, final AuthHeaders headers,
			final AtomicReference<String> authorizationRef) {
		if (mcpUri == null) {
			throw new IllegalArgumentException("mcpUri must not be null");
		}
		final String baseUri = stripPath(mcpUri);
		final String path = (mcpUri.getRawPath() == null || mcpUri.getRawPath().isBlank()) ? "/mcp"
				: mcpUri.getRawPath();
		final HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUri)
			.endpoint(appendQuery(path, headers.queryParams()))
			.customizeClient((client) -> client.executor(SHARED_HTTP_EXECUTOR));
		final McpSyncHttpClientRequestCustomizer customizer = headerCustomizer(headers, authorizationRef);
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
	 * Builds a customizer for resolved {@link AuthHeaders}: the authorization header is
	 * read from {@code authorizationRef} when provided (live token refresh support) else
	 * from the static {@code headers.authorization()}; custom headers are applied
	 * verbatim. Returns {@code null} when nothing needs forwarding.
	 * @param headers the resolved auth headers (never {@code null})
	 * @param authorizationRef live authorization source, or {@code null}
	 * @return a customizer, or {@code null} when no headers need forwarding
	 */
	private static McpSyncHttpClientRequestCustomizer headerCustomizer(final AuthHeaders headers,
			final AtomicReference<String> authorizationRef) {
		final boolean hasAuth = (authorizationRef != null)
				|| (headers.authorization() != null && !headers.authorization().isBlank());
		final boolean hasCustom = headers.customHeaders() != null && !headers.customHeaders().isEmpty();
		if (!hasAuth && !hasCustom) {
			return null;
		}
		return (builder, method, endpoint, body, context) -> {
			final String authorization = (authorizationRef != null) ? authorizationRef.get() : headers.authorization();
			if (authorization != null && !authorization.isBlank()) {
				builder.setHeader("Authorization", authorization);
			}
			if (hasCustom) {
				headers.customHeaders().forEach((name, value) -> {
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
	 * Appends URL-encoded query parameters to a path/endpoint string.
	 * @param path the base path
	 * @param queryParams the parameters to append (ignored when empty)
	 * @return {@code path} or {@code path?k=v&...}
	 */
	private static String appendQuery(final String path, final Map<String, String> queryParams) {
		if (queryParams == null || queryParams.isEmpty()) {
			return path;
		}
		final StringBuilder sb = new StringBuilder(path);
		sb.append(path.contains("?") ? '&' : '?');
		boolean first = true;
		for (final Map.Entry<String, String> entry : queryParams.entrySet()) {
			if (!first) {
				sb.append('&');
			}
			first = false;
			sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	/**
	 * Normalizes an SSE URI by ensuring the path is non-empty, defaulting to {@code /sse}
	 * when the supplied URI has no path.
	 * @param uri the source URI (may have an empty or null path)
	 * @return a URI with the same scheme, host and port, but with a non-empty path
	 */
	private static URI normalizeSseUri(final URI uri) {
		final String rawPath = uri.getRawPath();
		if (rawPath != null && !rawPath.isBlank()) {
			return uri;
		}
		return URI.create(stripPath(uri) + "/sse");
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
