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

import java.net.URI;

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

/** Unit tests for {@link ProxyErrorDto} — D5 URL redaction and the wire DTO shape. */
@Epic("MCP Inspector Core")
@Feature("ProxyErrorDto (D5 URL redaction)")
class ProxyErrorDtoTests {

	@Nested
	@DisplayName("redactUrl()")
	class RedactUrl {

		@Test
		@Story("D5 redaction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("query string is stripped so an API-key QUERY value never appears; scheme/host/path remain")
		void redactUrl_withSecretQuery_stripsQueryKeepsCore() {
			// given — an API-key value in the query, exactly what must never leak
			final URI uri = URI.create("https://target.example.com:8443/mcp/stream?api_key=super-secret-key&x=1");

			// when
			final String redacted = ProxyErrorDto.redactUrl(uri);

			// then
			assertThat(redacted).isEqualTo("https://target.example.com:8443/mcp/stream");
			assertThat(redacted).doesNotContain("super-secret-key");
			assertThat(redacted).doesNotContain("api_key");
			assertThat(redacted).doesNotContain("x=1");
			assertThat(redacted).doesNotContain("?");
		}

		@Test
		@Story("D5 redaction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("fragment is stripped together with the query")
		void redactUrl_withFragment_stripsFragment() {
			// when
			final String redacted = ProxyErrorDto
				.redactUrl(URI.create("https://target.example.com/path?token=abc#section"));

			// then
			assertThat(redacted).isEqualTo("https://target.example.com/path");
			assertThat(redacted).doesNotContain("abc");
			assertThat(redacted).doesNotContain("#");
		}

		@Test
		@Story("D5 redaction")
		@Severity(SeverityLevel.NORMAL)
		@Description("explicit ports are kept verbatim (scheme://host[:port]/path)")
		void redactUrl_keepsExplicitPorts() {
			// when/then — the implementation keeps an explicit port as-is (no
			// default-port elision)
			assertThat(ProxyErrorDto.redactUrl(URI.create("http://target.example.com/path")))
				.isEqualTo("http://target.example.com/path");
			assertThat(ProxyErrorDto.redactUrl(URI.create("https://target.example.com:443/path")))
				.isEqualTo("https://target.example.com:443/path");
			assertThat(ProxyErrorDto.redactUrl(URI.create("http://target.example.com:8080/path")))
				.isEqualTo("http://target.example.com:8080/path");
		}

		@Test
		@Story("D5 redaction")
		@Severity(SeverityLevel.NORMAL)
		@Description("null input yields null")
		void redactUrl_null_returnsNull() {
			// when/then
			assertThat(ProxyErrorDto.redactUrl(null)).isNull();
		}

	}

	@Nested
	@DisplayName("withUrl()")
	class WithUrl {

		@Test
		@Story("DTO assembly")
		@Severity(SeverityLevel.NORMAL)
		@Description("withUrl() attaches the redacted URL to a mapped DTO without touching the other fields")
		void withUrl_attachesRedactedUrl() {
			// given
			final ProxyErrorDto dto = new ProxyErrorDto(401, "unauthorized", "reason", "guidance", null);

			// when
			final ProxyErrorDto withUrl = dto.withUrl("https://target.example.com/sse");

			// then
			assertThat(withUrl.status()).isEqualTo(401);
			assertThat(withUrl.code()).isEqualTo("unauthorized");
			assertThat(withUrl.reason()).isEqualTo("reason");
			assertThat(withUrl.guidance()).isEqualTo("guidance");
			assertThat(withUrl.url()).isEqualTo("https://target.example.com/sse");
			assertThat(dto.url()).isNull();
		}

	}

}
