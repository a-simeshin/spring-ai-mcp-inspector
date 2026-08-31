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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.inspector.mcp.core.timeline.ClientConfigReader.ClientConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClientConfigReader}.
 *
 * @author Artem Simeshin
 */
class ClientConfigReaderTests {

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null environment")
		void rejectsNullEnvironment() {
			assertThatThrownBy(() -> new ClientConfigReader(null)).isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("readClients")
	class ReadClients {

		@Test
		@DisplayName("reads stdio connections")
		void readsStdioConnections() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.stdio.connections.server1.command", "echo");
			env.setProperty("spring.ai.mcp.client.stdio.connections.server1.args", "hello");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).hasSize(1);
			assertThat(clients).containsKey("server1");
			final ClientConfig config = clients.get("server1");
			assertThat(config.transportType()).isEqualTo("stdio");
			assertThat(config.command()).isEqualTo("echo");
			assertThat(config.url()).isNull();
		}

		@Test
		@DisplayName("reads sse connections with url")
		void readsSseConnections() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.sse.connections.httpServer.url", "https://example.com/sse");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).hasSize(1);
			assertThat(clients).containsKey("httpServer");
			final ClientConfig config = clients.get("httpServer");
			assertThat(config.transportType()).isEqualTo("sse");
			assertThat(config.url()).isEqualTo("https://example.com/sse");
			assertThat(config.command()).isNull();
		}

		@Test
		@DisplayName("reads streamable-http connections")
		void readsStreamableHttpConnections() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.streamable-http.connections.apiServer.url",
					"https://example.com/mcp");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).hasSize(1);
			assertThat(clients).containsKey("apiServer");
			final ClientConfig config = clients.get("apiServer");
			assertThat(config.transportType()).isEqualTo("streamable-http");
		}

		@Test
		@DisplayName("reads clients from multiple transport families")
		void readsMultipleFamilies() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.stdio.connections.stdio1.command", "echo");
			env.setProperty("spring.ai.mcp.client.sse.connections.sse1.url", "https://a.com/sse");
			env.setProperty("spring.ai.mcp.client.streamable-http.connections.http1.url", "https://b.com/mcp");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).hasSize(3);
			assertThat(clients.keySet()).contains("stdio1", "sse1", "http1");
		}

		@Test
		@DisplayName("returns empty map when no clients configured")
		void returnsEmptyWhenNoneConfigured() {
			// given
			final MockEnvironment env = new MockEnvironment();
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).isEmpty();
		}

		@Test
		@DisplayName("clientNames returns just the names")
		void clientNamesReturnsNames() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.stdio.connections.s1.command", "echo");
			env.setProperty("spring.ai.mcp.client.sse.connections.s2.url", "https://a.com/sse");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Set<String> names = reader.clientNames();

			// then
			assertThat(names).containsExactlyInAnyOrder("s1", "s2");
		}

		@Test
		@DisplayName("preserves both url and command when both are configured")
		void preservesBothUrlAndCommand() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.sse.connections.mixed.url", "https://example.invalid/sse");
			env.setProperty("spring.ai.mcp.client.sse.connections.mixed.command", "unexpected-command");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			assertThat(clients).hasSize(1);
			final ClientConfig config = clients.get("mixed");
			assertThat(config.url()).isEqualTo("https://example.invalid/sse");
			assertThat(config.command()).isEqualTo("unexpected-command");
		}

	}

	@Nested
	@DisplayName("redaction")
	class Redaction {

		@Test
		@DisplayName("redacts userinfo in URL")
		void redactsUserinfo() {
			// given
			final String raw = "https://user:secret@example.invalid/mcp";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).doesNotContain("user:secret");
			assertThat(redacted).contains("***@example.invalid/mcp");
		}

		@Test
		@DisplayName("redacts query string credentials in URL")
		void redactsQueryCredentials() {
			// given
			final String raw = "https://example.invalid/mcp?token=abc123";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).doesNotContain("token=abc123");
			assertThat(redacted).endsWith("?***");
		}

		@Test
		@DisplayName("redacts both userinfo and query string")
		void redactsBothUserinfoAndQuery() {
			// given
			final String raw = "https://user:secret@example.invalid/mcp?token=abc123";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).doesNotContain("user:secret");
			assertThat(redacted).doesNotContain("token=abc123");
			assertThat(redacted).isEqualTo("https://***@example.invalid/mcp?***");
		}

		@Test
		@DisplayName("does not alter URL without credentials")
		void doesNotAlterCleanUrl() {
			// given
			final String raw = "https://example.invalid/mcp";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).isEqualTo(raw);
		}

		@Test
		@DisplayName("does not alter non-URL string")
		void doesNotAlterNonUrl() {
			// given
			final String raw = "echo";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).isEqualTo(raw);
		}

		@Test
		@DisplayName("redacts http URL with userinfo")
		void redactsHttpUrlWithUserinfo() {
			// given
			final String raw = "http://user:secret@example.invalid/mcp";

			// when
			final String redacted = ClientConfigReader.redactUrl(raw);

			// then
			assertThat(redacted).doesNotContain("user:secret");
			assertThat(redacted).contains("***@example.invalid/mcp");
		}

		@Test
		@DisplayName("redacts command arguments beyond the executable")
		void redactsCommandArgs() {
			// given
			final String raw = "npx --api-key=sk-secret-123 @mcp/server";

			// when
			final String redacted = ClientConfigReader.redactCommand(raw);

			// then
			assertThat(redacted).doesNotContain("sk-secret-123");
			assertThat(redacted).doesNotContain("@mcp/server");
			assertThat(redacted).startsWith("npx");
			assertThat(redacted).endsWith("***");
		}

		@Test
		@DisplayName("does not alter single-token command")
		void doesNotAlterSingleTokenCommand() {
			// given
			final String raw = "echo";

			// when
			final String redacted = ClientConfigReader.redactCommand(raw);

			// then
			assertThat(redacted).isEqualTo(raw);
		}

		@Test
		@DisplayName("returns null for null URL")
		void returnsNullForNullUrl() {
			assertThat(ClientConfigReader.redactUrl(null)).isNull();
		}

		@Test
		@DisplayName("returns null for null command")
		void returnsNullForNullCommand() {
			assertThat(ClientConfigReader.redactCommand(null)).isNull();
		}

		@Test
		@DisplayName("redacts secrets in readClients output")
		void redactsInReadClientsOutput() {
			// given
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.sse.connections.secret.url",
					"https://user:secret@example.invalid/mcp?token=abc123");
			env.setProperty("spring.ai.mcp.client.stdio.connections.secret2.command", "npx --key=sk-secret tool");
			final ClientConfigReader reader = new ClientConfigReader(env);

			// when
			final Map<String, ClientConfig> clients = reader.readClients();

			// then
			final ClientConfig sseConfig = clients.get("secret");
			assertThat(sseConfig.url()).doesNotContain("user:secret").doesNotContain("token=abc123");
			final ClientConfig stdioConfig = clients.get("secret2");
			assertThat(stdioConfig.command()).doesNotContain("sk-secret");
		}

	}

}
