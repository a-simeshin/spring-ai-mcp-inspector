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

package io.inspector.mcp.core.bootstrap;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for Jackson serialization of {@link InspectorBootstrap}. */
@Epic("MCP Inspector Core")
@Feature("Bootstrap Jackson serialization")
class InspectorBootstrapJacksonTests {

	@Test
	@Story("Serialization")
	@Severity(SeverityLevel.CRITICAL)
	@Description("InspectorBootstrap serializes all first-class fields plus the extra map to the expected JSON shape")
	void serialize_emitsExpectedShape() throws Exception {
		// given
		final InspectorBootstrap bootstrap = new InspectorBootstrap();
		bootstrap.setAuthToken("token-abc");
		bootstrap.setProxyAddress("/mcp-inspector-api");
		bootstrap.setDetectedTransport("streamable-http");
		bootstrap.setDetectedUrl("/mcp");
		bootstrap.setDefaultUrl("https://example.com/mcp");
		bootstrap.setDefaultTransport("sse");
		bootstrap.setDefaultHeaders(List.of(new CustomHeader("X-Tenant", "acme", true)));
		bootstrap.setServerEntries(List
			.of(new ServerEntry("internal", "https://internal/mcp", "streamable-http", Map.of("X-Env", "prod"))));
		bootstrap.getExtra().put("featureFlag", "on");

		// when
		final String json = new ObjectMapper().writeValueAsString(bootstrap);

		// then
		assertThat(json).contains("\"authToken\":\"token-abc\"")
			.contains("\"proxyAddress\":\"/mcp-inspector-api\"")
			.contains("\"detectedTransport\":\"streamable-http\"")
			.contains("\"detectedUrl\":\"/mcp\"")
			.contains("\"defaultUrl\":\"https://example.com/mcp\"")
			.contains("\"defaultTransport\":\"sse\"")
			.contains("\"defaultHeaders\"")
			.contains("\"X-Tenant\"")
			.contains("\"serverEntries\"")
			.contains("\"internal\"")
			.contains("\"extra\"")
			.contains("\"featureFlag\":\"on\"");
	}

}
