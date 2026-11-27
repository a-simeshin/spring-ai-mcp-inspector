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
package io.inspector.mcp.demo.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connect-level contract for the SEP-2640 skills extension detection on both stacks:
 * {@code POST /mcp-inspector/api/connect} must report {@code skillsExtension} from the
 * <em>canonical</em> {@code capabilities.extensions} declaration of the initialize
 * response.
 *
 * <p>
 * The loopback client of a connect call targets the application's own MCP endpoint (path
 * from {@code spring.ai.mcp.server.mcp-endpoint}). This IT redirects that path to an
 * in-process mock MCP endpoint (a {@code @Controller} in
 * {@code io.inspector.mcp.itfixtures}, outside the demo app's scan root, registered as an
 * explicit bean through a lite-mode source so sibling IT contexts never pick it up) that
 * answers {@code initialize} with the canonical declaration and nothing in {@code _meta}.
 * That is exactly the wire shape the SDK's typed {@code ServerCapabilities} record drops,
 * which is how the first version of this feature reported {@code supported=false} for
 * every spec-compliant server: the regression this test locks in.
 *
 * <p>
 * Runs once per stack (webmvc and webflux) through the demo-app test-jar's Failsafe
 * {@code dependenciesToScan}, like every other proxy IT.
 */
@Epic("Inspector Proxy")
@Feature("SEP-2640 skills capability detection")
class SkillsConnectCapabilityIT {

	private static final JsonMapper MAPPER = new JsonMapper();

	private static final HttpClient HTTP = ProxyAppHarness.httpClient(Duration.ofSeconds(5));

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (this.app != null) {
			try {
				this.app.close();
			}
			catch (Exception ignored) {
				// best-effort
			}
			this.app = null;
		}
	}

	@Test
	@DisplayName("connect reports skillsExtension=true for a canonical capabilities.extensions declaration")
	@Story("Canonical declaration via /connect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A spec-compliant SEP-2640 server (capabilities.extensions only, no _meta fallback) reports "
			+ "skillsExtension.supported=true and directoryRead=true in the /connect response on both stacks")
	void connect_withCanonicalDeclaration_reportsSkillsExtension() throws Exception {
		// given: the inspector's loopback target redirected to the in-process mock
		this.app = ProxyAppHarness.startWithSources("STREAMABLE", false, null,
				new Class<?>[] { SkillsConnectCapabilityIT.MockMcpEndpointSource.class },
				"--spring.ai.mcp.server.mcp-endpoint=" + io.inspector.mcp.itfixtures.MockSkillsMcpEndpoint.ENDPOINT);
		final HttpResponse<String> connect = postConnect();

		// then: the canonical declaration survived into the connect response
		assertThat(connect.statusCode()).as("connect on %s, body=%s", ProxyAppHarness.stack(), connect.body())
			.isEqualTo(200);
		final JsonNode body = MAPPER.readTree(connect.body());
		assertThat(body.path("sessionId").asText()).as("sessionId on %s", ProxyAppHarness.stack()).isNotBlank();
		final JsonNode skills = body.path("skillsExtension");
		assertThat(skills.path("supported").asBoolean())
			.as("canonical capabilities.extensions must report supported=true on %s, body=%s", ProxyAppHarness.stack(),
					connect.body())
			.isTrue();
		assertThat(skills.path("directoryRead").asBoolean())
			.as("directoryRead=true must be reported on %s, body=%s", ProxyAppHarness.stack(), connect.body())
			.isTrue();
	}

	@Test
	@DisplayName("connect reports skillsExtension=false for a server without the declaration")
	@Story("No declaration via /connect")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The demo app's own MCP server (no capabilities.extensions) reports skillsExtension.supported=false "
			+ "in the /connect response on both stacks, so the Skills tab stays hidden")
	void connect_withoutDeclaration_reportsNotSupported() throws Exception {
		// given: the plain demo app, whose MCP server declares no extensions
		this.app = ProxyAppHarness.start("STREAMABLE", false, null);

		// when
		final HttpResponse<String> connect = postConnect();

		// then
		assertThat(connect.statusCode()).as("connect on %s, body=%s", ProxyAppHarness.stack(), connect.body())
			.isEqualTo(200);
		final JsonNode skills = MAPPER.readTree(connect.body()).path("skillsExtension");
		assertThat(skills.path("supported").asBoolean())
			.as("no declaration must report supported=false on %s, body=%s", ProxyAppHarness.stack(), connect.body())
			.isFalse();
		assertThat(skills.path("directoryRead").asBoolean())
			.as("directoryRead defaults to false on %s", ProxyAppHarness.stack())
			.isFalse();
	}

	private HttpResponse<String> postConnect() throws Exception {
		return HTTP.send(HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + ProxyAppHarness.port(this.app) + "/mcp-inspector/api/connect"))
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Lite-mode source ({@code @Configuration}-free on purpose, per the board's
	 * config-fixture convention) registering the mock MCP endpoint bean. The endpoint
	 * type itself lives in {@code io.inspector.mcp.itfixtures}, outside the demo app's
	 * component-scan root: Spring Framework 7 requires the {@code @Controller} stereotype
	 * for handler mapping, so keeping the annotated type under the scanned package would
	 * leak it into every sibling IT context.
	 */
	static class MockMcpEndpointSource {

		@org.springframework.context.annotation.Bean
		io.inspector.mcp.itfixtures.MockSkillsMcpEndpoint mockSkillsMcpEndpoint() {
			return new io.inspector.mcp.itfixtures.MockSkillsMcpEndpoint();
		}

	}

}
