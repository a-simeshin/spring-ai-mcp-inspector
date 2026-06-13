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

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds {@link McpSyncClient} instances that spawn an <em>external</em> MCP server as a
 * child process and talk to it over stdio. Used by the inspector "External Target" tab
 * and the {@code ExternalStdioProcessIT} integration test.
 *
 * @author Artem Simeshin
 */
public class ExternalStdioClientFactory {

	private final JsonMapper objectMapper;

	public ExternalStdioClientFactory() {
		this(new JsonMapper());
	}

	public ExternalStdioClientFactory(final JsonMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new JsonMapper();
	}

	/**
	 * Builds and returns an unconnected {@link McpSyncClient} for the given command.
	 * Caller is responsible for {@code initialize()} / {@code close()}.
	 * @param command command-line tokens (first element = executable, rest = args); must
	 * contain at least one element
	 * @param env additional environment variables; may be {@code null} or empty
	 * @return an unconnected {@link McpSyncClient} ready for {@code initialize()}
	 */
	public McpSyncClient forCommand(final List<String> command, final Map<String, String> env) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must contain at least the executable");
		}

		final ServerParameters.Builder paramsBuilder = ServerParameters.builder(command.get(0));
		if (command.size() > 1) {
			paramsBuilder.args(command.subList(1, command.size()));
		}
		if (env != null && !env.isEmpty()) {
			paramsBuilder.env(env);
		}
		final ServerParameters parameters = paramsBuilder.build();

		final StdioClientTransport transport = new StdioClientTransport(parameters,
				new JacksonMcpJsonMapper(this.objectMapper));
		return McpClient.sync(transport).build();
	}

}
