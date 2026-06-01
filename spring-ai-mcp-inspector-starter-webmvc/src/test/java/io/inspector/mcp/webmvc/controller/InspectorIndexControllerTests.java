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

import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InspectorIndexController}. Collaborators are mocked; assertions
 * cover the redirect routes and the templated index path (delegating to the renderer +
 * assembler) with the no-cache headers.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorIndexController")
class InspectorIndexControllerTests {

	private InspectorBootstrapAssembler assembler;

	private BootstrapHtmlRenderer renderer;

	private InspectorIndexController controller;

	@BeforeEach
	void setUp() {
		this.assembler = mock(InspectorBootstrapAssembler.class);
		this.renderer = mock(BootstrapHtmlRenderer.class);
		this.controller = new InspectorIndexController(this.assembler, this.renderer, "/mcp-inspector");
	}

	@Nested
	@DisplayName("redirect routes")
	class Redirects {

		@Test
		@Story("Root redirect")
		@Severity(SeverityLevel.NORMAL)
		@Description("redirectRoot() returns 302 to the index.html under the configured path")
		void redirectRoot_returns302ToIndexHtml() {
			// when
			final ResponseEntity<Void> response = InspectorIndexControllerTests.this.controller.redirectRoot();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
			assertThat(response.getHeaders().getLocation()).hasToString("/mcp-inspector/index.html");
		}

		@Test
		@Story("Root redirect")
		@Severity(SeverityLevel.MINOR)
		@Description("redirectTrailingSlash() returns the same 302 redirect to index.html")
		void redirectTrailingSlash_returns302ToIndexHtml() {
			// when
			final ResponseEntity<Void> response = InspectorIndexControllerTests.this.controller.redirectTrailingSlash();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
			assertThat(response.getHeaders().getLocation()).hasToString("/mcp-inspector/index.html");
		}

	}

	@Nested
	@DisplayName("index()")
	class Index {

		@Test
		@Story("Templated index")
		@Severity(SeverityLevel.CRITICAL)
		@Description("index() renders the bundle through the renderer and returns it with no-cache headers")
		void index_withBundlePresent_returnsRenderedHtmlWithNoCacheHeaders() throws Exception {
			// given
			final InspectorBootstrap bootstrap = new InspectorBootstrap();
			given(InspectorIndexControllerTests.this.assembler.assemble()).willReturn(bootstrap);
			given(InspectorIndexControllerTests.this.renderer.renderIndexHtml(anyString(),
					any(InspectorBootstrap.class)))
				.willReturn("<html>rendered</html>");

			// when
			final ResponseEntity<String> response = InspectorIndexControllerTests.this.controller.index();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isEqualTo("<html>rendered</html>");
			assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
			assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-store");
			assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
		}

		@Test
		@Story("Templated index")
		@Severity(SeverityLevel.NORMAL)
		@Description("oauthCallback() serves the same templated index page")
		void oauthCallback_returnsRenderedIndex() throws Exception {
			// given
			given(InspectorIndexControllerTests.this.assembler.assemble()).willReturn(new InspectorBootstrap());
			given(InspectorIndexControllerTests.this.renderer.renderIndexHtml(anyString(), any()))
				.willReturn("<html>cb</html>");

			// when
			final ResponseEntity<String> response = InspectorIndexControllerTests.this.controller.oauthCallback();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isEqualTo("<html>cb</html>");
		}

		@Test
		@Story("Templated index")
		@Severity(SeverityLevel.MINOR)
		@Description("oauthCallbackDebug() serves the same templated index page")
		void oauthCallbackDebug_returnsRenderedIndex() throws Exception {
			// given
			given(InspectorIndexControllerTests.this.assembler.assemble()).willReturn(new InspectorBootstrap());
			given(InspectorIndexControllerTests.this.renderer.renderIndexHtml(anyString(), any()))
				.willReturn("<html>dbg</html>");

			// when
			final ResponseEntity<String> response = InspectorIndexControllerTests.this.controller.oauthCallbackDebug();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isEqualTo("<html>dbg</html>");
		}

	}

}
