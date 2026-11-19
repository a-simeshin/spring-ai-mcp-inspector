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

package io.inspector.mcp.webmvc.controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.timeline.ClientConnectionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@link ClientConnectionsController}: the serialized
 * {@code ClientConnectionSnapshot} wire form through the real MVC converters. The
 * {@link #EXPECTED_KEYS} order and the {@link #assertConnectionShape} helper are pinned
 * 1:1 with the WebFlux {@code ClientConnectionsHandlerTests}, so both stacks serve the
 * identical JSON form.
 *
 * @author Artem Simeshin
 */
@Epic("WebMvc Inspector")
@Feature("ClientConnectionsController")
class ClientConnectionsControllerTests {

	private static final JsonMapper MAPPER = new JsonMapper();

	/**
	 * Wire form pinned 1:1 with the WebFlux ClientConnectionsHandlerTests constant: the
	 * exact key set and order of one serialized {@code ClientConnectionSnapshot}.
	 */
	private static final List<String> EXPECTED_KEYS = List.of("name", "transportType", "target", "state", "since",
			"lastActivity", "inCount", "outCount", "lastError");

	private ClientConnectionRegistry registry;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.registry = new ClientConnectionRegistry();
		this.mockMvc = MockMvcBuilders.standaloneSetup(new ClientConnectionsController(this.registry)).build();
	}

	/**
	 * Shared shape assertion, byte-for-byte identical with the WebFlux handler test:
	 * exact key set and order, concrete values for every deterministic field, ISO
	 * instants and the lastError nullability contract.
	 * @param connection one serialized snapshot entry
	 * @param name expected client name
	 * @param transportType expected transport type
	 * @param target expected sanitized target
	 * @param state expected lifecycle state name
	 * @param inCount expected inbound message count
	 * @param outCount expected outbound message count
	 * @param lastError expected last error, {@code null} when none
	 * @param lastActivityNull whether {@code lastActivity} must be JSON null
	 */
	private static void assertConnectionShape(final JsonNode connection, final String name, final String transportType,
			final String target, final String state, final long inCount, final long outCount, final String lastError,
			final boolean lastActivityNull) {
		final List<String> keys = new ArrayList<>();
		connection.properties().forEach((property) -> keys.add(property.getKey()));
		assertThat(keys).isEqualTo(EXPECTED_KEYS);
		assertThat(connection.get("name").asString()).isEqualTo(name);
		assertThat(connection.get("transportType").asString()).isEqualTo(transportType);
		assertThat(connection.get("target").asString()).isEqualTo(target);
		assertThat(connection.get("state").asString()).isEqualTo(state);
		assertThat(Instant.parse(connection.get("since").asString())).isNotNull();
		if (lastActivityNull) {
			assertThat(connection.get("lastActivity").isNull()).isTrue();
		}
		else {
			assertThat(Instant.parse(connection.get("lastActivity").asString())).isNotNull();
		}
		assertThat(connection.get("inCount").asLong()).isEqualTo(inCount);
		assertThat(connection.get("outCount").asLong()).isEqualTo(outCount);
		if (lastError == null) {
			assertThat(connection.get("lastError").isNull()).isTrue();
		}
		else {
			assertThat(connection.get("lastError").asString()).isEqualTo(lastError);
		}
	}

	@Nested
	@DisplayName("GET ${path}/api/client-connections")
	class Connections {

		@Test
		@Story("Empty registry")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connections() on an empty registry answers 200 with an empty JSON array")
		void connections_emptyRegistry_servesEmptyArray() throws Exception {
			// when & then
			ClientConnectionsControllerTests.this.mockMvc.perform(get("/mcp-inspector/api/client-connections"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("[]"));
		}

		@Test
		@Story("One client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connections() serves one connected client with the pinned wire form and concrete values")
		void connections_oneClient_servesPinnedWireForm() throws Exception {
			// given
			ClientConnectionsControllerTests.this.registry.markInitializing("demo", "streamable-http",
					"https://***@mcp.example.com/mcp");
			ClientConnectionsControllerTests.this.registry.markConnected("demo");
			ClientConnectionsControllerTests.this.registry.recordInbound("demo");
			ClientConnectionsControllerTests.this.registry.recordInbound("demo");
			ClientConnectionsControllerTests.this.registry.recordOutbound("demo");

			// when
			final String body = ClientConnectionsControllerTests.this.mockMvc
				.perform(get("/mcp-inspector/api/client-connections"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			final JsonNode array = MAPPER.readTree(body);

			// then
			assertThat(array.isArray()).isTrue();
			assertThat(array.size()).isEqualTo(1);
			assertConnectionShape(array.get(0), "demo", "streamable-http", "https://***@mcp.example.com/mcp",
					"CONNECTED", 2, 1, null, false);
		}

		@Test
		@Story("Several clients")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connections() serves several clients sorted by name with per-entry shapes valid")
		void connections_severalClients_sortedByNameWithValidShapes() throws Exception {
			// given
			ClientConnectionsControllerTests.this.registry.markInitializing("beta", "sse",
					"https://***@sse.example.com/mcp");
			ClientConnectionsControllerTests.this.registry.markInitializing("alpha", "stdio", "npx -y demo-server");
			ClientConnectionsControllerTests.this.registry.markFailed("alpha", "spawn ENOENT");

			// when
			final String body = ClientConnectionsControllerTests.this.mockMvc
				.perform(get("/mcp-inspector/api/client-connections"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			final JsonNode array = MAPPER.readTree(body);

			// then
			assertThat(array.isArray()).isTrue();
			assertThat(array.size()).isEqualTo(2);
			assertThat(array.get(0).get("name").asString()).isEqualTo("alpha");
			assertThat(array.get(1).get("name").asString()).isEqualTo("beta");
			assertConnectionShape(array.get(0), "alpha", "stdio", "npx -y demo-server", "FAILED", 0, 0, "spawn ENOENT",
					true);
			assertConnectionShape(array.get(1), "beta", "sse", "https://***@sse.example.com/mcp", "INITIALIZING", 0, 0,
					null, true);
		}

		@Test
		@Story("Failed client")
		@Severity(SeverityLevel.CRITICAL)
		@Description("connections() masks credential-bearing URLs inside lastError, the raw failure text never leaves the endpoint")
		void connections_failedClient_masksCredentialsInLastError() throws Exception {
			// given
			ClientConnectionsControllerTests.this.registry.markInitializing("demo", "streamable-http",
					"https://***@mcp.example.com/mcp");
			ClientConnectionsControllerTests.this.registry.markFailed("demo",
					"java.net.ConnectException: https://user:secret@mcp.example.com/mcp?token=abc123");

			// when
			final String body = ClientConnectionsControllerTests.this.mockMvc
				.perform(get("/mcp-inspector/api/client-connections"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
			final JsonNode array = MAPPER.readTree(body);

			// then
			assertThat(array.size()).isEqualTo(1);
			assertConnectionShape(array.get(0), "demo", "streamable-http", "https://***@mcp.example.com/mcp", "FAILED",
					0, 0, "java.net.ConnectException: https://***@mcp.example.com/mcp", true);
			assertThat(body).doesNotContain("user:secret").doesNotContain("token=abc123");
		}

	}

}
