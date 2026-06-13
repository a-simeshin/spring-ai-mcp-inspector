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

import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
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
		if (sseUri == null) {
			throw new IllegalArgumentException("sseUri must not be null");
		}
		final String baseUri = stripPath(sseUri);
		final String ssePath = (sseUri.getRawPath() == null || sseUri.getRawPath().isBlank()) ? "/sse"
				: sseUri.getRawPath();
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
		return HttpClientStreamableHttpTransport.builder(baseUri).endpoint(path).build();
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
