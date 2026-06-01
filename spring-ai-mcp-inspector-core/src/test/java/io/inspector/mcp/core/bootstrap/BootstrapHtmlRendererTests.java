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

package io.inspector.mcp.core.bootstrap;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link BootstrapHtmlRenderer}. */
@Epic("MCP Inspector Core")
@Feature("Bootstrap HTML renderer")
class BootstrapHtmlRendererTests {

	private final BootstrapHtmlRenderer renderer = new BootstrapHtmlRenderer(new ObjectMapper());

	@Nested
	@DisplayName("renderIndexHtml()")
	class RenderIndexHtml {

		@Test
		@Story("Placeholder replacement")
		@Severity(SeverityLevel.CRITICAL)
		@Description("renderIndexHtml() replaces the placeholder with a script block carrying the serialized bootstrap")
		void render_replacesPlaceholderWithBootstrapScript() throws IOException {
			// given
			final String template = "<head>" + BootstrapHtmlRenderer.PLACEHOLDER + "</head>";
			final InspectorBootstrap bootstrap = new InspectorBootstrap();
			bootstrap.setAuthToken("token-abc");

			// when
			final String html = BootstrapHtmlRendererTests.this.renderer.renderIndexHtml(template, bootstrap);

			// then
			assertThat(html).doesNotContain(BootstrapHtmlRenderer.PLACEHOLDER)
				.contains("<script>window.__MCP_INSPECTOR_BOOTSTRAP = ")
				.contains("\"authToken\":\"token-abc\"")
				.endsWith(";</script></head>");
		}

		@Test
		@Story("Script-tag escaping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("renderIndexHtml() escapes any '</' sequence so injected JSON cannot terminate the script element")
		void render_escapesClosingScriptTagAndReplacesPlaceholder() throws IOException {
			// given
			final String template = BootstrapHtmlRenderer.PLACEHOLDER;
			final InspectorBootstrap bootstrap = new InspectorBootstrap();
			bootstrap.setDefaultUrl("</script><script>alert(1)</script>");

			// when
			final String html = BootstrapHtmlRendererTests.this.renderer.renderIndexHtml(template, bootstrap);

			// then — the raw closing-tag sequence must not survive in the output
			assertThat(html).doesNotContain("</script><script>");
			assertThat(html).contains("<\\/script><script>alert(1)<\\/script>");
		}

		@Test
		@Story("Missing placeholder")
		@Severity(SeverityLevel.NORMAL)
		@Description("renderIndexHtml() returns the template untouched when the placeholder is absent")
		void render_whenPlaceholderMissing_returnsTemplateUnchanged() throws IOException {
			// given
			final String template = "<html><body>no placeholder here</body></html>";

			// when
			final String html = BootstrapHtmlRendererTests.this.renderer.renderIndexHtml(template,
					new InspectorBootstrap());

			// then
			assertThat(html).isEqualTo(template);
		}

		@Test
		@Story("Serialization failure")
		@Severity(SeverityLevel.NORMAL)
		@Description("renderIndexHtml() propagates a Jackson serialization failure as IOException")
		void render_whenSerializationFails_throwsIoException() throws Exception {
			// given
			final ObjectMapper failing = mock(ObjectMapper.class);
			given(failing.writeValueAsString(org.mockito.ArgumentMatchers.any()))
				.willThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {
				});
			final BootstrapHtmlRenderer failingRenderer = new BootstrapHtmlRenderer(failing);

			// when & then
			assertThatThrownBy(
					() -> failingRenderer.renderIndexHtml(BootstrapHtmlRenderer.PLACEHOLDER, new InspectorBootstrap()))
				.isInstanceOf(IOException.class);
		}

	}

}
