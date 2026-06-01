/*
 * Copyright 2025-present the original author or authors.
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

import io.modelcontextprotocol.client.McpSyncClient;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ExternalStdioClientFactory}. */
@Epic("MCP Inspector Core")
@Feature("External stdio client factory")
class ExternalStdioClientFactoryTests {

	private final ExternalStdioClientFactory factory = new ExternalStdioClientFactory();

	private static void closeQuietly(final McpSyncClient client) {
		try {
			client.close();
		}
		catch (final Exception ignored) {
			/* best-effort */
		}
	}

	@Nested
	@DisplayName("forCommand()")
	class ForCommand {

		@Test
		@Story("Build stdio client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("forCommand() builds an McpSyncClient for a valid command without opening a transport")
		void forCommand_withValidCommand_buildsClient() {
			// given / when
			final McpSyncClient client = ExternalStdioClientFactoryTests.this.factory
				.forCommand(List.of("java", "-version"), Map.of());

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Build stdio client")
		@Severity(SeverityLevel.NORMAL)
		@Description("forCommand() builds a client when extra environment variables are supplied")
		void forCommand_withEnvironment_buildsClient() {
			// given / when
			final McpSyncClient client = ExternalStdioClientFactoryTests.this.factory
				.forCommand(List.of("java", "-version"), Map.of("FOO", "bar"));

			// then
			assertThat(client).isNotNull();
			closeQuietly(client);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("forCommand() rejects an empty command list")
		void forCommand_withEmptyCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ExternalStdioClientFactoryTests.this.factory.forCommand(List.of(), Map.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command");
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("forCommand() rejects a null command list")
		void forCommand_withNullCommand_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> ExternalStdioClientFactoryTests.this.factory.forCommand(null, Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
		}

	}

}
