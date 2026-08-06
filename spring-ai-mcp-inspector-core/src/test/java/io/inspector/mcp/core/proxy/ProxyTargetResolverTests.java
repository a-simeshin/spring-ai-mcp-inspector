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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link ProxyTargetResolver} — the WAF-safe server-side resolution of a
 * relative/blank {@code ?url=} target against the loopback origin.
 */
class ProxyTargetResolverTests {

	@Nested
	@DisplayName("absolute url (has scheme)")
	class Absolute {

		@Test
		@DisplayName("is returned unchanged for back-compat")
		void passesThrough() {
			assertThat(ProxyTargetResolver.resolve("http://example.com:9000/mcp", 8080, "/mcp"))
				.isEqualTo(URI.create("http://example.com:9000/mcp"));
			assertThat(ProxyTargetResolver.resolve("https://internal/mcp", 8080, "/mcp"))
				.isEqualTo(URI.create("https://internal/mcp"));
		}

		@Test
		@DisplayName("an explicit absolute localhost target is honoured as-is")
		void absoluteLocalhostKept() {
			assertThat(ProxyTargetResolver.resolve("http://localhost:8080/mcp", 8080, "/mcp"))
				.isEqualTo(URI.create("http://localhost:8080/mcp"));
		}

	}

	@Nested
	@DisplayName("relative absolute-path url (/mcp)")
	class RelativePath {

		@Test
		@DisplayName("resolves against the loopback origin")
		void resolvesAgainstLoopback() {
			assertThat(ProxyTargetResolver.resolve("/mcp", 8080, "/mcp"))
				.isEqualTo(URI.create("http://127.0.0.1:8080/mcp"));
			assertThat(ProxyTargetResolver.resolve("/sse", 9999, "/mcp"))
				.isEqualTo(URI.create("http://127.0.0.1:9999/sse"));
		}

		@Test
		@DisplayName("preserves a query string on the path")
		void keepsQuery() {
			assertThat(ProxyTargetResolver.resolve("/mcp?x=1", 8080, "/mcp"))
				.isEqualTo(URI.create("http://127.0.0.1:8080/mcp?x=1"));
		}

	}

	@Nested
	@DisplayName("blank url")
	class Blank {

		@Test
		@DisplayName("falls back to loopback + default path")
		void usesDefaultPath() {
			assertThat(ProxyTargetResolver.resolve(null, 8080, "/mcp"))
				.isEqualTo(URI.create("http://127.0.0.1:8080/mcp"));
			assertThat(ProxyTargetResolver.resolve("", 8080, "/sse"))
				.isEqualTo(URI.create("http://127.0.0.1:8080/sse"));
			assertThat(ProxyTargetResolver.resolve("   ", 7000, "/mcp"))
				.isEqualTo(URI.create("http://127.0.0.1:7000/mcp"));
		}

	}

	@Nested
	@DisplayName("rejections")
	class Rejections {

		@Test
		@DisplayName("protocol-relative //host is an SSRF vector and is rejected")
		void rejectsProtocolRelative() {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> ProxyTargetResolver.resolve("//evil.example/mcp", 8080, "/mcp"));
		}

		@Test
		@DisplayName("a bare relative path without leading slash is rejected")
		void rejectsBareRelative() {
			assertThatIllegalArgumentException().isThrownBy(() -> ProxyTargetResolver.resolve("mcp", 8080, "/mcp"));
		}

		@Test
		@DisplayName("a non-positive port for relative/blank resolution is rejected")
		void rejectsBadPort() {
			assertThatIllegalArgumentException().isThrownBy(() -> ProxyTargetResolver.resolve("/mcp", 0, "/mcp"));
			assertThatIllegalArgumentException().isThrownBy(() -> ProxyTargetResolver.resolve(null, -1, "/mcp"));
		}

		@Test
		@DisplayName("but an absolute url still resolves even when the port is unknown")
		void absoluteIgnoresPort() {
			assertThat(ProxyTargetResolver.resolve("http://example.com/mcp", -1, "/mcp"))
				.isEqualTo(URI.create("http://example.com/mcp"));
		}

	}

}
