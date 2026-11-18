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

package io.inspector.mcp.core.timeline;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TargetSanitizer}: userinfo and query redaction for URL targets
 * (including scheme-less values), value-free stdio command lines and idempotence.
 *
 * @author Artem Simeshin
 */
class TargetSanitizerTests {

	@Test
	@DisplayName("strips userinfo and drops the query from a scheme-bearing URL")
	void stripsCredentialsAndQuery() {
		assertThat(TargetSanitizer.sanitizeTarget("https://user:pass@host/path?token=x"))
			.isEqualTo("https://***@host/path");
	}

	@Test
	@DisplayName("drops the query without credentials")
	void dropsQueryWithoutCredentials() {
		assertThat(TargetSanitizer.sanitizeTarget("https://api.example/sse?session=1"))
			.isEqualTo("https://api.example/sse");
		assertThat(TargetSanitizer.sanitizeTarget("https://host/p?k=v")).isEqualTo("https://host/p");
	}

	@Test
	@DisplayName("drops the query even when the value carries no scheme")
	void dropsQueryWithoutScheme() {
		assertThat(TargetSanitizer.sanitizeTarget("host/sse?token=abc123")).isEqualTo("host/sse");
	}

	@Test
	@DisplayName("replaces userinfo with and without a password")
	void replacesUserinfo() {
		assertThat(TargetSanitizer.sanitizeTarget("https://user:pass@host/path")).isEqualTo("https://***@host/path");
		assertThat(TargetSanitizer.sanitizeTarget("https://user@host/path")).isEqualTo("https://***@host/path");
	}

	@Test
	@DisplayName("keeps the userinfo-like at sign outside the authority untouched")
	void keepsAtOutsideAuthority() {
		assertThat(TargetSanitizer.sanitizeTarget("https://host/p/a@b")).isEqualTo("https://host/p/a@b");
	}

	@Test
	@DisplayName("an @ inside the query only does not trigger userinfo redaction")
	void atInQueryOnlyNotRedacted() {
		assertThat(TargetSanitizer.sanitizeTarget("https://host/path?user=a@b")).isEqualTo("https://host/path");
	}

	@Test
	@DisplayName("a URL without userinfo or query passes through unchanged")
	void plainUrlPassesThrough() {
		assertThat(TargetSanitizer.sanitizeTarget("https://api.example/mcp")).isEqualTo("https://api.example/mcp");
	}

	@Test
	@DisplayName("null and empty inputs pass through")
	void nullAndEmptyPassThrough() {
		assertThat(TargetSanitizer.sanitizeTarget(null)).isNull();
		assertThat(TargetSanitizer.sanitizeTarget("")).isEmpty();
	}

	@Test
	@DisplayName("stdio command line keeps argv[0] and flag names only")
	void stdioCommandLineKeepsFlagsWithoutValues() {
		assertThat(TargetSanitizer.sanitizeCommand("npx", List.of("-y", "@scope/mcp-server", "--api-key=SECRET")))
			.isEqualTo("npx -y --api-key");
		assertThat(TargetSanitizer.sanitizeCommand("python", List.of("-m", "server", "--verbose", "--token", "abc")))
			.isEqualTo("python -m --verbose --token");
	}

	@Test
	@DisplayName("stdio command without flags or arguments passes through")
	void stdioCommandWithoutArgsPassesThrough() {
		assertThat(TargetSanitizer.sanitizeCommand("npx", List.of())).isEqualTo("npx");
		assertThat(TargetSanitizer.sanitizeCommand("mcp-server", null)).isEqualTo("mcp-server");
	}

	@Test
	@DisplayName("null or empty command passes through")
	void nullAndEmptyCommandPassThrough() {
		assertThat(TargetSanitizer.sanitizeCommand(null, List.of("-y"))).isNull();
		assertThat(TargetSanitizer.sanitizeCommand("", List.of("-y"))).isEmpty();
	}

	@Test
	@DisplayName("sanitizing an already sanitized value is a no-op")
	void idempotent() {
		final String sanitized = TargetSanitizer.sanitizeTarget("https://user:pass@host/path?token=x");
		assertThat(TargetSanitizer.sanitizeTarget(sanitized)).isEqualTo(sanitized);
		final String command = TargetSanitizer.sanitizeCommand("npx", List.of("-y", "--api-key=SECRET"));
		assertThat(TargetSanitizer.sanitizeCommand(command, List.of())).isEqualTo(command);
	}

}
