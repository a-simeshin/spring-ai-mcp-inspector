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

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProxyConnectionBudgets}: the single place both proxy stacks parse
 * and validate the {@code connectionTimeout} query parameter.
 *
 * @author Artem Simeshin
 */
@DisplayName("ProxyConnectionBudgets: connectionTimeout parsing and payload shape")
class ProxyConnectionBudgetsTests {

	// A default distinct from any value the implementation could hardcode: if
	// parseOrDefault ever ignores its configuredDefault argument the fallback
	// assertions below turn red instead of comparing 30s to 30s.
	private static final Duration DEFAULT = Duration.ofMillis(250);

	@Test
	void parseOrDefault_absentOrBlank_yieldsConfiguredDefault() {
		assertThat(ProxyConnectionBudgets.parseOrDefault(null, DEFAULT)).isEqualTo(DEFAULT);
		assertThat(ProxyConnectionBudgets.parseOrDefault("", DEFAULT)).isEqualTo(DEFAULT);
		assertThat(ProxyConnectionBudgets.parseOrDefault("   ", DEFAULT)).isEqualTo(DEFAULT);
	}

	@Test
	void parseOrDefault_validWholeSeconds_yieldsExactBudget() {
		assertThat(ProxyConnectionBudgets.parseOrDefault("1", DEFAULT)).isEqualTo(Duration.ofSeconds(1));
		assertThat(ProxyConnectionBudgets.parseOrDefault("600", DEFAULT)).isEqualTo(Duration.ofSeconds(600));
		assertThat(ProxyConnectionBudgets.parseOrDefault(" 30 ", DEFAULT)).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void parseOrDefault_malformedValues_areRejected() {
		for (final String raw : new String[] { "abc", "1.5", "1s", "0x10", "" + Long.MAX_VALUE, "9300000000000",
				"10000000000" }) {
			assertThatThrownBy(() -> ProxyConnectionBudgets.parseOrDefault(raw, DEFAULT)).as("raw value %s", raw)
				.isInstanceOf(ProxyConnectionBudgets.ProxyConnectionBudgetException.class);
		}
	}

	@Test
	void parseOrDefault_outOfRangeValues_areRejected() {
		// 0, negative and above the 600s cap are rejected rather than treated as absent
		for (final String raw : new String[] { "0", "-5", "601" }) {
			assertThatThrownBy(() -> ProxyConnectionBudgets.parseOrDefault(raw, DEFAULT)).as("raw value %s", raw)
				.isInstanceOf(ProxyConnectionBudgets.ProxyConnectionBudgetException.class)
				.hasMessageContaining("1 and 600");
		}
	}

	@Test
	void invalidParameterBody_carriesContractShapeAndOffendingValue() {
		final Map<String, Object> body = ProxyConnectionBudgets.invalidParameterBody("abc",
				"connectionTimeout must be a whole number of seconds between 1 and 600");
		@SuppressWarnings("unchecked")
		final Map<String, Object> error = (Map<String, Object>) body.get("error");
		assertThat(error.get("code")).isEqualTo("MCP_CONNECT_FAILED");
		assertThat(error.get("reason")).isEqualTo("validation");
		assertThat(error.get("connectionTimeout")).isEqualTo("abc");
		// a malformed parameter will not heal on a retry
		assertThat(error.get("retryable")).isEqualTo(Boolean.FALSE);
		assertThat((String) error.get("message")).contains("whole number of seconds");
	}

	@Test
	void timeoutBody_usesExistingContractWithTimeoutReasonAndPhaseNumbers() {
		final ProxyConnectTimeoutException ex = new ProxyConnectTimeoutException(
				ProxyConnectTimeoutException.Phase.CONNECT, 1234L, 1000L);
		final Map<String, Object> body = ProxyConnectionBudgets.timeoutBody(ex);
		@SuppressWarnings("unchecked")
		final Map<String, Object> error = (Map<String, Object>) body.get("error");
		assertThat(error.get("code")).isEqualTo("MCP_CONNECT_FAILED");
		assertThat(error.get("reason")).isEqualTo("timeout");
		assertThat(error.get("phase")).isEqualTo("connect");
		assertThat(error.get("elapsedMs")).isEqualTo(1234L);
		assertThat(error.get("budgetMs")).isEqualTo(1000L);
		assertThat(error.get("retryable")).isEqualTo(Boolean.TRUE);
		// no separate connection_timeout code: the UI parses this contract as-is
		assertThat(error).doesNotContainKey("connection_timeout");
	}

}
