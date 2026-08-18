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

import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link ProxySessionRegistry}. */
@Epic("MCP Inspector Core")
@Feature("Proxy session registry")
class ProxySessionRegistryTests {

	private final ProxySessionRegistry registry = new ProxySessionRegistry();

	private static ProxySession sessionWith(final String id, final McpClientTransport transport) {
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
		return new ProxySession(id, transport, browserToTarget, targetToBrowser);
	}

	private static McpClientTransport mockTransport() {
		final McpClientTransport transport = mock(McpClientTransport.class);
		given(transport.closeGracefully()).willReturn(Mono.empty());
		return transport;
	}

	@Nested
	@DisplayName("put() / get()")
	class PutAndGet {

		@Test
		@Story("Registration")
		@Severity(SeverityLevel.CRITICAL)
		@Description("put() then get() returns the same registered session by its session id")
		void put_thenGet_returnsSameSession() {
			// given
			final ProxySession session = sessionWith("s-1", mockTransport());

			// when
			ProxySessionRegistryTests.this.registry.put(session);

			// then
			assertThat(ProxySessionRegistryTests.this.registry.get("s-1")).isSameAs(session);
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
		}

		@Test
		@Story("Miss")
		@Severity(SeverityLevel.NORMAL)
		@Description("get() returns null for an unknown id")
		void get_whenUnknownId_returnsNull() {
			// when
			final ProxySession found = ProxySessionRegistryTests.this.registry.get("nope");

			// then
			assertThat(found).isNull();
		}

		@Test
		@Story("Null safety")
		@Severity(SeverityLevel.MINOR)
		@Description("get() tolerates a null id and returns null")
		void get_whenNullId_returnsNull() {
			// when
			final ProxySession found = ProxySessionRegistryTests.this.registry.get(null);

			// then
			assertThat(found).isNull();
		}

	}

	@Nested
	@DisplayName("removeAndClose()")
	class RemoveAndClose {

		@Test
		@Story("Successful removal")
		@Severity(SeverityLevel.CRITICAL)
		@Description("removeAndClose() removes the session, closes its transport and returns true")
		void removeAndClose_whenSessionExists_closesTransportAndReturnsTrue() {
			// given
			final McpClientTransport transport = mockTransport();
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", transport));

			// when
			final boolean removed = ProxySessionRegistryTests.this.registry.removeAndClose("s-1");

			// then
			assertThat(removed).isTrue();
			assertThat(ProxySessionRegistryTests.this.registry.get("s-1")).isNull();
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
			verify(transport).closeGracefully();
		}

		@Test
		@Story("Miss")
		@Severity(SeverityLevel.NORMAL)
		@Description("removeAndClose() returns false for an unknown id and closes nothing")
		void removeAndClose_whenSessionUnknown_returnsFalse() {
			// given
			final McpClientTransport transport = mockTransport();
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", transport));

			// when
			final boolean removed = ProxySessionRegistryTests.this.registry.removeAndClose("other");

			// then
			assertThat(removed).isFalse();
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
			verify(transport, never()).closeGracefully();
		}

		@Test
		@Story("Null safety")
		@Severity(SeverityLevel.MINOR)
		@Description("removeAndClose() returns false for a null id")
		void removeAndClose_whenNullId_returnsFalse() {
			// when
			final boolean removed = ProxySessionRegistryTests.this.registry.removeAndClose(null);

			// then
			assertThat(removed).isFalse();
		}

	}

	@Nested
	@DisplayName("closeAll()")
	class CloseAll {

		@Test
		@Story("Shutdown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("closeAll() closes every registered session's transport and empties the registry")
		void closeAll_withMultipleSessions_closesAllTransportsAndEmptiesRegistry() {
			// given
			final McpClientTransport first = mockTransport();
			final McpClientTransport second = mockTransport();
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", first));
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-2", second));

			// when
			ProxySessionRegistryTests.this.registry.closeAll();

			// then
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
			verify(first).closeGracefully();
			verify(second).closeGracefully();
		}

	}

	@Nested
	@DisplayName("size()")
	class Size {

		@Test
		@Story("Counting")
		@Severity(SeverityLevel.MINOR)
		@Description("size() reflects the number of registered sessions")
		void size_afterMultiplePuts_countsRegisteredSessions() {
			// given
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", mockTransport()));
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-2", mockTransport()));

			// when
			final int size = ProxySessionRegistryTests.this.registry.size();

			// then
			assertThat(size).isEqualTo(2);
		}

	}

	@Nested
	@DisplayName("setInactivityBudget()")
	class SetInactivityBudget {

		@Test
		@Story("Valid budget")
		@Severity(SeverityLevel.NORMAL)
		@Description("setInactivityBudget() with a positive Duration is accepted; reap() uses it to evict idle sessions")
		void setInactivityBudget_withPositiveDuration_isAccepted() throws InterruptedException {
			// given — 1 ms budget so any session is immediately idle
			ProxySessionRegistryTests.this.registry.setInactivityBudget(Duration.ofMillis(1));
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", mockTransport()));
			Thread.sleep(5);

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then — session was idle past the 1 ms budget and was evicted
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
		}

		@Test
		@Story("Null falls back to default")
		@Severity(SeverityLevel.NORMAL)
		@Description("setInactivityBudget(null) falls back to the 30-minute default so reap() keeps a recent session")
		void setInactivityBudget_withNull_fallsBackToThirtyMinuteDefault() {
			// given
			ProxySessionRegistryTests.this.registry.setInactivityBudget(null);
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", mockTransport()));

			// when — reap immediately; the session was just created so it is not idle
			ProxySessionRegistryTests.this.registry.reap();

			// then — session was kept because the 30-minute default budget is not
			// exceeded
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
		}

		@Test
		@Story("Zero falls back to default")
		@Severity(SeverityLevel.NORMAL)
		@Description("setInactivityBudget(Duration.ZERO) falls back to the 30-minute default")
		void setInactivityBudget_withZero_fallsBackToDefault() {
			// given
			ProxySessionRegistryTests.this.registry.setInactivityBudget(Duration.ZERO);
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", mockTransport()));

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then — default 30-minute budget keeps the freshly-created session alive
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
		}

		@Test
		@Story("Negative falls back to default")
		@Severity(SeverityLevel.NORMAL)
		@Description("setInactivityBudget() with a negative Duration falls back to the 30-minute default")
		void setInactivityBudget_withNegative_fallsBackToDefault() {
			// given
			ProxySessionRegistryTests.this.registry.setInactivityBudget(Duration.ofSeconds(-1));
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-1", mockTransport()));

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then — default 30-minute budget keeps the freshly-created session alive
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("reap()")
	class Reap {

		@Test
		@Story("Closed session eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("reap() evicts a session that is already closed, routing eviction through removeAndClose()")
		void reap_withClosedSession_evictsItViaRemoveAndClose() {
			// given
			final McpClientTransport transport = mockTransport();
			final ProxySession session = sessionWith("s-1", transport);
			ProxySessionRegistryTests.this.registry.put(session);
			session.close();

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then — the session was removed from the registry
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
			assertThat(ProxySessionRegistryTests.this.registry.get("s-1")).isNull();
		}

		@Test
		@Story("Idle session eviction")
		@Severity(SeverityLevel.CRITICAL)
		@Description("reap() evicts a live session whose lastActivity is older than the inactivity budget")
		void reap_withIdleSession_evictsIt() throws InterruptedException {
			// given
			ProxySessionRegistryTests.this.registry.setInactivityBudget(Duration.ofMillis(1));
			final McpClientTransport transport = mockTransport();
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-idle", transport));
			Thread.sleep(5);

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
			// removeAndClose closed the transport a second time (session.close() is
			// idempotent)
			verify(transport).closeGracefully();
		}

		@Test
		@Story("Live session kept")
		@Severity(SeverityLevel.CRITICAL)
		@Description("reap() keeps a session that is open and whose lastActivity is within the budget")
		void reap_withRecentlyActiveSession_keepsIt() {
			// given — default 30-minute budget; session was just created
			ProxySessionRegistryTests.this.registry.put(sessionWith("s-live", mockTransport()));

			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryTests.this.registry.size()).isEqualTo(1);
		}

		@Test
		@Story("Empty registry")
		@Severity(SeverityLevel.MINOR)
		@Description("reap() on an empty registry is a no-op and leaves size at zero")
		void reap_withEmptyRegistry_isNoOp() {
			// when
			ProxySessionRegistryTests.this.registry.reap();

			// then
			assertThat(ProxySessionRegistryTests.this.registry.size()).isZero();
		}

	}

	@Nested
	@DisplayName("post-shutdown registration")
	class AfterCloseAll {

		@Test
		@Story("Shutdown ordering")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A session registered after the shutdown sweep is closed immediately instead of being "
				+ "silently kept — a GET /sse still connecting upstream when ContextClosedEvent fires used to "
				+ "land in the map afterwards and hold its stream open for the whole graceful phase")
		void put_afterCloseAll_closesTheLateSessionAndDoesNotRegisterIt() {
			// given
			ProxySessionRegistryTests.this.registry.closeAll();
			final ProxySession late = sessionWith("late", mockTransport());

			// when
			ProxySessionRegistryTests.this.registry.put(late);

			// then
			assertThat(late.isClosed()).as("late session closed on arrival").isTrue();
			assertThat(ProxySessionRegistryTests.this.registry.size()).as("late session not registered").isZero();
			assertThat(ProxySessionRegistryTests.this.registry.get("late")).isNull();
		}

	}

}
