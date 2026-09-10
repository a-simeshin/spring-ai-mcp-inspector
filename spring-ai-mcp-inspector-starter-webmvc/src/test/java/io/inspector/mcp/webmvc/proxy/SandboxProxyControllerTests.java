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

package io.inspector.mcp.webmvc.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link SandboxProxyController}. */
class SandboxProxyControllerTests {

	@Test
	void parseAndSerializeCsp_null_returnsDefault() {
		final String result = SandboxProxyController.parseAndSerializeCsp(null);
		assertThat(result).contains("default-src 'none'");
		assertThat(result).contains("connect-src 'none'");
	}

	@Test
	void parseAndSerializeCsp_blank_returnsDefault() {
		final String result = SandboxProxyController.parseAndSerializeCsp("");
		assertThat(result).contains("default-src 'none'");
	}

	@Test
	void parseAndSerializeCsp_validJson_serializesCorrectly() {
		final String cspJson = "{\"connectDomains\":[\"https://api.example.com\"],\"resourceDomains\":[\"https://cdn.example.com\"]}";
		final String result = SandboxProxyController.parseAndSerializeCsp(cspJson);
		assertThat(result).contains("connect-src https://api.example.com");
		assertThat(result).contains("img-src https://cdn.example.com");
	}

	@Test
	void parseAndSerializeCsp_malformedJson_returnsDefault() {
		final String malformed = "not-json{";
		final String result = SandboxProxyController.parseAndSerializeCsp(malformed);
		// Should not echo raw value; should return safe default
		assertThat(result).doesNotContain("not-json{");
		assertThat(result).contains("default-src 'none'");
	}

	@Test
	void parseAndSerializeCsp_javascriptOrigin_rejected() {
		final String cspJson = "{\"connectDomains\":[\"javascript:alert(1)\"]}";
		final String result = SandboxProxyController.parseAndSerializeCsp(cspJson);
		assertThat(result).doesNotContain("javascript:alert");
		assertThat(result).contains("default-src 'none'");
	}

	@Test
	void parseAndSerializeCsp_dataOrigin_rejected() {
		final String cspJson = "{\"frameDomains\":[\"data:text/html,<script>alert(1)</script>\"]}";
		final String result = SandboxProxyController.parseAndSerializeCsp(cspJson);
		assertThat(result).doesNotContain("data:text/html");
		assertThat(result).contains("default-src 'none'");
	}

	@Test
	void serveSandboxWithOversizeCspParam_returns400() throws Exception {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4097; i++) {
			sb.append("x");
		}
		final ResponseEntity<String> response = new SandboxProxyController().serveSandbox(sb.toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("CSP parameter exceeds maximum length");
	}

}
