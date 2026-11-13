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

package io.inspector.mcp.core.proxy;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProxyErrorFallback} - the fallback DTOs used for terminal
 * failures the D3 mapper cannot classify, so every terminal SSE failure is readable by
 * the client.
 */
@Epic("MCP Inspector Core")
@Feature("ProxyErrorFallback (readable error delivery)")
class ProxyErrorFallbackTests {

	@Test
	@DisplayName("upstreamDeath(): 502 upstream_unreachable with fixed reason and guidance")
	@Story("Readable error delivery")
	@Severity(SeverityLevel.CRITICAL)
	@Description("The death fallback keeps the D3 shape: status 502, code upstream_unreachable, "
			+ "non-blank reason and guidance, and no url (the caller stamps the redacted target url).")
	void upstreamDeath_keepsD3Shape() {
		final ProxyErrorDto dto = ProxyErrorFallback.upstreamDeath();

		assertThat(dto.status()).isEqualTo(502);
		assertThat(dto.code()).isEqualTo("upstream_unreachable");
		assertThat(dto.reason()).isEqualTo("The upstream MCP server is unreachable.");
		assertThat(dto.guidance()).isEqualTo("Check the upstream server, then reconnect.");
		assertThat(dto.url()).isNull();
	}

}
