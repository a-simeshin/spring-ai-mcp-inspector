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
			assertThat(config.detail()).isEqualTo("echo");
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
			assertThat(config.detail()).isEqualTo("https://example.com/sse");
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

	}

}
