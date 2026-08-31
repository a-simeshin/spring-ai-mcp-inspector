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
 * <p>
 * URL and command values are redacted before leaving this reader: userinfo and query
 * string in URLs are masked, and command arguments beyond the executable are masked. This
 * prevents credential leakage through diagnostic logs and timeline events.
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
			final String url = readUrl(prefix, name);
			final String command = readCommand(prefix, name);
			clients.put(name, new ClientConfig(name, transportType, url, command));
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

	private String readUrl(final String prefix, final String name) {
		return redactUrl(this.environment.getProperty(prefix + "." + name + ".url"));
	}

	private String readCommand(final String prefix, final String name) {
		return redactCommand(this.environment.getProperty(prefix + "." + name + ".command"));
	}

	/**
	 * Redacts sensitive parts of a URL: userinfo (user:password@) is replaced with
	 * {@code ***@} and the query string is replaced with {@code ?***}. The scheme, host,
	 * port, path and fragment are preserved because they are needed for transport
	 * mismatch diagnostics.
	 * @param raw the raw URL, or {@code null}
	 * @return the redacted URL, or {@code null} if the input was {@code null}
	 */
	static String redactUrl(final String raw) {
		if (raw == null) {
			return null;
		}
		String result = raw;
		final int schemeEnd = result.indexOf("://");
		if (schemeEnd < 0) {
			return result;
		}
		final int atSign = result.indexOf('@', schemeEnd + 3);
		if (atSign >= 0) {
			final int queryStart = result.indexOf('?');
			if (queryStart < 0 || atSign < queryStart) {
				result = result.substring(0, schemeEnd + 3) + "***" + result.substring(atSign);
			}
		}
		final int queryStart = result.indexOf('?');
		if (queryStart >= 0) {
			result = result.substring(0, queryStart + 1) + "***";
		}
		return result;
	}

	/**
	 * Redacts a command by keeping only the executable (first token) and replacing the
	 * rest with {@code ***}. This prevents leakage of secrets that may be passed as
	 * inline arguments.
	 * @param raw the raw command, or {@code null}
	 * @return the redacted command, or {@code null} if the input was {@code null}
	 */
	static String redactCommand(final String raw) {
		if (raw == null) {
			return null;
		}
		final String[] parts = raw.split("\\s+", 2);
		if (parts.length > 1) {
			return parts[0] + " ***";
		}
		return raw;
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
	 * @param url the redacted URL, if the {@code url} property is configured (may be
	 * {@code null})
	 * @param command the redacted command, if the {@code command} property is configured
	 * (may be {@code null})
	 */
	public record ClientConfig(String name, String transportType, String url, String command) {
	}

}
