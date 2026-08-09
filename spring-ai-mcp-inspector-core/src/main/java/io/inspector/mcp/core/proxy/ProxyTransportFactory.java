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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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

	public ProxyTransportFactory() {
		this(new ObjectMapper());
	}

	public ProxyTransportFactory(final ObjectMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
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
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final String baseUri = stripPath(sseUri);
		final String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse"
				: sseUri.getRawPath();
		return HttpClientSseClientTransport.builder(baseUri)
			.sseEndpoint(ssePath)
			.messageEndpointValidator(noopValidator())
			.customizeClient((client) -> client.executor(SHARED_HTTP_EXECUTOR))
			.build();
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
