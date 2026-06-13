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

package io.inspector.mcp.webflux.it;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.reactive.server.WebTestClient;

import io.inspector.mcp.core.bootstrap.InspectorBootstrapCustomizer;

/**
 * Reactive parallel of {@code WebMvcCustomizerIT}.
 *
 * <p>
 * Test scenarios mandated by the plan ({@code ### Integration Layer (Java)} — Customizer
 * integration, WebFlux):
 * <ul>
 * <li>{@code customizer_affectsConfigEndpoint}</li>
 * <li>{@code customizer_orderedByAtOrder}</li>
 * </ul>
 */
@Epic("MCP Inspector WebFlux")
@Feature("Bootstrap customizer integration (integration)")
@AutoConfigureWebTestClient
@SpringBootTest(classes = { TestMcpServerApp.class, WebFluxCustomizerIT.TestCustomizers.class },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.main.web-application-type=reactive", "spring.ai.mcp.server.protocol=SSE",
				"spring.ai.mcp.server.name=mcp-inspector-itest-flux-cust", "spring.ai.mcp.server.version=0.1.0",
				"spring.ai.mcp.inspector.auth-enabled=false", "spring.application.name=mcp-inspector-itest-flux-cust" })
class WebFluxCustomizerIT {

	static final String ORDER_LOSER = "http://order-1.example/mcp";
	static final String ORDER_WINNER = "http://order-2.example/mcp";

	@Autowired
	private WebTestClient webTestClient;

	@Test
	@Story("Customizer applied")
	@Severity(SeverityLevel.NORMAL)
	@Description("A registered InspectorBootstrapCustomizer's defaultUrl shows up on the /config endpoint")
	void customizer_affectsConfigEndpoint() {
		// given two ordered customizers; when & then
		this.webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.defaultUrl")
			.exists();
	}

	@Test
	@Story("Customizer applied")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Customizers run in @Order sequence so the highest-order customizer wins the defaultUrl")
	void customizer_orderedByAtOrder() {
		// given two ordered customizers; when & then
		this.webTestClient.get()
			.uri("/mcp-inspector/config")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.defaultUrl")
			.isEqualTo(ORDER_WINNER);
	}

	@TestConfiguration
	static class TestCustomizers {

		@Bean
		@Order(1)
		InspectorBootstrapCustomizer firstCustomizer() {
			return (bootstrap) -> bootstrap.setDefaultUrl(ORDER_LOSER);
		}

		@Bean
		@Order(2)
		InspectorBootstrapCustomizer secondCustomizer() {
			return (bootstrap) -> bootstrap.setDefaultUrl(ORDER_WINNER);
		}

	}

}
