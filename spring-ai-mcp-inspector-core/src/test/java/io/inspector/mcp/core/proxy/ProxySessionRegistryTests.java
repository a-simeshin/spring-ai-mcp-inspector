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

package io.inspector.mcp.core.proxy;

import com.fasterxml.jackson.databind.JsonNode;
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

}
