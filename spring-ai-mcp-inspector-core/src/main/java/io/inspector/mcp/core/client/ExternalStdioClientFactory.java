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

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

/**
 * Builds {@link McpSyncClient} instances that spawn an <em>external</em> MCP server as a
 * child process and talk to it over stdio. Used by the inspector "External Target" tab
 * and the {@code ExternalStdioProcessIT} integration test.
 */
public class ExternalStdioClientFactory {

	private final ObjectMapper objectMapper;

	public ExternalStdioClientFactory() {
		this(new ObjectMapper());
	}

	public ExternalStdioClientFactory(ObjectMapper objectMapper) {
		this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
	}

	/**
	 * Builds and returns an unconnected {@link McpSyncClient} for the given command.
	 * Caller is responsible for {@code initialize()} / {@code close()}.
	 * @param command command-line tokens (first element = executable, rest = args); must
	 * contain at least one element
	 * @param env additional environment variables; may be {@code null} or empty
	 */
	public McpSyncClient forCommand(List<String> command, Map<String, String> env) {
		if (command == null || command.isEmpty()) {
			throw new IllegalArgumentException("command must contain at least the executable");
		}

		ServerParameters.Builder paramsBuilder = ServerParameters.builder(command.get(0));
		if (command.size() > 1) {
			paramsBuilder.args(command.subList(1, command.size()));
		}
		if (env != null && !env.isEmpty()) {
			paramsBuilder.env(env);
		}
		ServerParameters parameters = paramsBuilder.build();

		StdioClientTransport transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(objectMapper));
		return McpClient.sync(transport).build();
	}

}
