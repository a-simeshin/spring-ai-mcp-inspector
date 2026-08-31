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

package io.inspector.mcp.core.timeline;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads the MCP client configuration from
 * {@code spring.ai.mcp.client.stdio.connections.*},
 * {@code spring.ai.mcp.client.sse.connections.*} and
 * {@code spring.ai.mcp.client.streamable-http.connections.*} properties, reporting the
 * set of configured client names and their transport types.
 *
 * <p>
 * Core does not depend on the spring-ai-autoconfigure-mcp-client-common jar at compile
 * time, so instead of binding to
 * {@code McpStdioClientProperties}/{@code McpSseClientProperties}/
 * {@code McpStreamableHttpClientProperties} it reads the connection map keys directly
 * from the {@link Environment}. This gives the exact set of configured client names and
 * their transport families, which is all the desync detector needs.
 *
 * @author Artem Simeshin
 */
public final class ClientConfigReader {

	private static final String STDIO_PREFIX = "spring.ai.mcp.client.stdio.connections";

	private static final String SSE_PREFIX = "spring.ai.mcp.client.sse.connections";

	private static final String STREAMABLE_HTTP_PREFIX = "spring.ai.mcp.client.streamable-http.connections";

	private final Environment environment;

	/**
	 * Creates a new reader.
	 * @param environment the Spring environment (must not be {@code null})
	 */
	public ClientConfigReader(final Environment environment) {
		if (environment == null) {
			throw new IllegalArgumentException("environment must not be null");
		}
		this.environment = environment;
	}

	/**
	 * Reads all configured clients across the three transport families and returns them
	 * keyed by client name with their transport type.
	 * @return an immutable map from client name to transport type label (never
	 * {@code null})
	 */
	public Map<String, ClientConfig> readClients() {
		final Map<String, ClientConfig> clients = new TreeMap<>();
		addClientsFromPrefix(clients, STDIO_PREFIX, "stdio");
		addClientsFromPrefix(clients, SSE_PREFIX, "sse");
		addClientsFromPrefix(clients, STREAMABLE_HTTP_PREFIX, "streamable-http");
		return Map.copyOf(clients);
	}

	private void addClientsFromPrefix(final Map<String, ClientConfig> clients, final String prefix,
			final String transportType) {
		final Set<String> names = readConnectionNames(prefix);
		for (final String name : names) {
			final String detail = readTransportDetail(prefix, name);
			clients.put(name, new ClientConfig(name, transportType, detail));
		}
	}

	/**
	 * Extracts connection names from a nested properties map prefix like
	 * {@code spring.ai.mcp.client.stdio.connections}.
	 * <p>
	 * Uses Spring Boot's {@link Binder} to resolve the nested map, which works with any
	 * {@link Environment} implementation (including property sources that do not
	 * enumerate their own keys).
	 * @param prefix the properties prefix ending at {@code connections}
	 * @return the set of connection names found under this prefix
	 */
	private Set<String> readConnectionNames(final String prefix) {
		final Map<String, Object> connectionMap = Binder.get(this.environment)
			.bind(prefix, Bindable.mapOf(String.class, Object.class))
			.orElseGet(Map::of);
		return new TreeSet<>(connectionMap.keySet());
	}

	private String readTransportDetail(final String prefix, final String name) {
		final String url = this.environment.getProperty(prefix + "." + name + ".url");
		if (url != null) {
			return url;
		}
		final String command = this.environment.getProperty(prefix + "." + name + ".command");
		if (command != null) {
			return command;
		}
		return null;
	}

	/**
	 * Returns the set of all configured client names across all transport families.
	 * @return the set of client names (never {@code null})
	 */
	public Set<String> clientNames() {
		return readClients().keySet();
	}

	/**
	 * Immutable record describing a single configured MCP client.
	 *
	 * @param name the client (connection) name
	 * @param transportType the transport family: {@code stdio}, {@code sse}, or
	 * {@code streamable-http}
	 * @param detail the URL or command, if readable from the properties (may be
	 * {@code null})
	 */
	public record ClientConfig(String name, String transportType, String detail) {
	}

}
