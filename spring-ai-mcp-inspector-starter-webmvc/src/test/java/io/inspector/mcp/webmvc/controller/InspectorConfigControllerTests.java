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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link InspectorConfigController}. Asserts the assembled bootstrap is
 * returned with status 200, JSON content type and the no-cache headers.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorConfigController")
class InspectorConfigControllerTests {

	private InspectorBootstrapAssembler assembler;

	private InspectorConfigController controller;

	@BeforeEach
	void setUp() {
		this.assembler = mock(InspectorBootstrapAssembler.class);
		this.controller = new InspectorConfigController(this.assembler);
	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Bootstrap JSON endpoint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() returns the assembled bootstrap with 200, JSON content type and no-cache headers")
		void config_returnsBootstrapWithNoCacheHeaders() {
			// given
			final InspectorBootstrap bootstrap = new InspectorBootstrap();
			bootstrap.setAuthToken("tok");
			bootstrap.setProxyAddress("/mcp-inspector-api");
			given(InspectorConfigControllerTests.this.assembler.assemble(anyString())).willReturn(bootstrap);

			// when
			final ResponseEntity<InspectorBootstrap> response = InspectorConfigControllerTests.this.controller
				.config(new MockHttpServletRequest());

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isSameAs(bootstrap);
			assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-store");
			assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
		}

		@Test
		@Story("Context path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() passes the request context path to the assembler so the proxy address is reachable")
		void config_underContextPath_passesPrefixToAssembler() {
			// given
			given(InspectorConfigControllerTests.this.assembler.assemble(anyString()))
				.willReturn(new InspectorBootstrap());
			final MockHttpServletRequest request = new MockHttpServletRequest();
			request.setContextPath("/app");

			// when
			InspectorConfigControllerTests.this.controller.config(request);

			// then
			verify(InspectorConfigControllerTests.this.assembler).assemble("/app");
		}

		@Test
		@Story("Context path")
		@Severity(SeverityLevel.NORMAL)
		@Description("config() treats a \"/\" context path as no prefix, so the proxy address never becomes protocol-relative")
		void config_withRootContextPath_passesEmptyPrefix() {
			// given
			given(InspectorConfigControllerTests.this.assembler.assemble(anyString()))
				.willReturn(new InspectorBootstrap());
			final MockHttpServletRequest request = new MockHttpServletRequest();
			request.setContextPath("/");

			// when
			InspectorConfigControllerTests.this.controller.config(request);

			// then
			verify(InspectorConfigControllerTests.this.assembler).assemble("");
		}

	}

}
