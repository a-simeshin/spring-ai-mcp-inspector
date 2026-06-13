/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.it;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.client.ExternalStdioClientFactory;
import io.inspector.mcp.demo.DemoApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spawns {@code DemoApplication} as an external Java child process with the {@code stdio}
 * Spring profile, then talks to it over MCP stdio using
 * {@link ExternalStdioClientFactory}.
 *
 * <p>
 * Reuses the test JVM's own classpath so we don't need to depend on a separately built
 * "demo-stdio" fat jar — the same Maven-built classpath that runs this test is sufficient
 * to start the child {@code DemoApplication}.
 *
 * <p>
 * Mandated by {@code ### Integration Layer (Java)} →
 * {@code ExternalStdioProcessIT#externalStdioServerToolsListViaSpawnedJar}.
 */
@Epic("MCP Transports")
@Feature("External stdio process")
class ExternalStdioProcessIT {

	@Test
	@DisplayName("listTools over a spawned external stdio server returns the demo tools")
	@Story("Spawned process tools/list")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Spawns DemoApplication as an external Java child process in stdio mode and verifies "
			+ "the MCP stdio client can list the expected demo tools (echo, sum, currentTime).")
	void listTools_overSpawnedExternalStdioServer_returnsDemoTools() {
		// given
		String javaHome = System.getProperty("java.home");
		Path javaBin = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
		assertThat(javaBin).as("java executable must exist at %s", javaBin).exists();

		String classpath = System.getProperty("java.class.path");
		assertThat(classpath).as("test JVM classpath must be available").isNotBlank();

		List<String> command = new ArrayList<>();
		command.add(javaBin.toString());
		// Ensure stdio mode: web=NONE, banner off, server.stdio=true
		command.add("-Dspring.main.web-application-type=NONE");
		command.add("-Dspring.main.banner-mode=OFF");
		command.add("-Dspring.ai.mcp.server.stdio=true");
		// Logging must not write to stdout (would break MCP framing).
		command.add("-Dlogging.pattern.console=");
		command.add("-Dspring.profiles.active=stdio");
		command.add("-cp");
		command.add(classpath);
		command.add(DemoApplication.class.getName());

		ExternalStdioClientFactory factory = new ExternalStdioClientFactory();
		McpSyncClient client = factory.forCommand(command, Map.of());
		try {
			// when
			client.initialize();
			ListToolsResult result = client.listTools();
			List<String> names = result.tools().stream().map(t -> t.name()).toList();

			// then
			assertThat(names).contains("echo", "sum", "currentTime");
		}
		finally {
			try {
				client.closeGracefully();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			try {
				client.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
	}

	private static boolean isWindows() {
		return File.separatorChar == '\\';
	}

}
