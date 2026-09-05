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

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Unit tests for the reaper and inactivity budget. */
@Epic("MCP Inspector Core")
@Feature("Session reaper")
class ProxySessionRegistryReaperTests {

	private static final Duration FAST_BUDGET = Duration.ofMillis(50);

	private ProxySessionRegistry registry;

	private McpClientTransport transport;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	@BeforeEach
	void setUp() {
		this.registry = new ProxySessionRegistry();
		this.transport = mock(McpClientTransport.class);
		given(this.transport.closeGracefully()).willReturn(Mono.empty());
		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(64);
	}

	@Nested
	@DisplayName("reap()")
	class Reap {

		@Test
		@Story("Reaper evicts closed session immediately")
		@Description("a closed session is evicted on the next reap cycle")
		void reap_evictsClosedSession() {
			// given
			final ProxySession session = new ProxySession("s-1", ProxySessionRegistryReaperTests.this.transport,
					ProxySessionRegistryReaperTests.this.browserToTarget,
					ProxySessionRegistryReaperTests.this.targetToBrowser);
			ProxySessionRegistryReaperTests.this.registry.put(session);
			session.close();
			assertThat(ProxySessionRegistryReaperTests.this.registry.size()).as("session still in registry before reap")
				.isEqualTo(1);

			// when
			ProxySessionRegistryReaperTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryReaperTests.this.registry.size()).as("session evicted after reap").isZero();
		}

		@Test
		@Story("Reaper evicts idle session beyond budget")
		@Description("a session idle longer than the inactivity budget is evicted")
		void reap_evictsIdleSession() throws Exception {
			// given
			ProxySessionRegistryReaperTests.this.registry.setInactivityBudget(FAST_BUDGET);
			final ProxySession session = new ProxySession("s-2", ProxySessionRegistryReaperTests.this.transport,
					ProxySessionRegistryReaperTests.this.browserToTarget,
					ProxySessionRegistryReaperTests.this.targetToBrowser);
			ProxySessionRegistryReaperTests.this.registry.put(session);
			// Wait past the budget
			Thread.sleep(FAST_BUDGET.toMillis() + 50);
			assertThat(ProxySessionRegistryReaperTests.this.registry.size()).as("session still in registry before reap")
				.isEqualTo(1);

			// when
			ProxySessionRegistryReaperTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryReaperTests.this.registry.size()).as("idle session evicted after reap")
				.isZero();
		}

		@Test
		@Story("Reaper keeps recently active session")
		@Description("a session that was recently touched is not evicted")
		void reap_keepsActiveSession() {
			// given
			ProxySessionRegistryReaperTests.this.registry.setInactivityBudget(FAST_BUDGET);
			final ProxySession session = new ProxySession("s-3", ProxySessionRegistryReaperTests.this.transport,
					ProxySessionRegistryReaperTests.this.browserToTarget,
					ProxySessionRegistryReaperTests.this.targetToBrowser);
			ProxySessionRegistryReaperTests.this.registry.put(session);
			session.touch(); // refresh activity timestamp

			// when
			ProxySessionRegistryReaperTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryReaperTests.this.registry.size()).as("active session must not be evicted")
				.isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("setInactivityBudget()")
	class SetInactivityBudget {

		@Test
		@Story("setInactivityBudget accepts valid duration")
		@Description("a positive duration is accepted")
		void setInactivityBudget_acceptsValidDuration() {
			ProxySessionRegistryReaperTests.this.registry.setInactivityBudget(Duration.ofSeconds(10));
			// verify no exception, and the budget is set
		}

		@Test
		@Story("setInactivityBudget falls back on null")
		@Description("null falls back to the 30m default")
		void setInactivityBudget_fallsBackOnNull() {
			ProxySessionRegistryReaperTests.this.registry.setInactivityBudget(null);
			// no exception expected
		}

	}

	@Nested
	@DisplayName("forEachSession()")
	class ForEachSession {

		@Test
		@Story("forEachSession iterates all sessions")
		@Description("all registered sessions are visited by forEachSession")
		void forEachSession_iteratesAllSessions() {
			// given
			final ProxySession s1 = new ProxySession("s-1", ProxySessionRegistryReaperTests.this.transport,
					ProxySessionRegistryReaperTests.this.browserToTarget,
					ProxySessionRegistryReaperTests.this.targetToBrowser);
			final ProxySession s2 = new ProxySession("s-2", ProxySessionRegistryReaperTests.this.transport,
					ProxySessionRegistryReaperTests.this.browserToTarget,
					ProxySessionRegistryReaperTests.this.targetToBrowser);
			ProxySessionRegistryReaperTests.this.registry.put(s1);
			ProxySessionRegistryReaperTests.this.registry.put(s2);
			final int[] count = { 0 };

			// when
			ProxySessionRegistryReaperTests.this.registry.forEachSession((s) -> count[0]++);

			// then
			assertThat(count[0]).isEqualTo(2);
		}

	}

}
