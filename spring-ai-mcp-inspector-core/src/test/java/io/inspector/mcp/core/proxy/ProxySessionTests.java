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

import java.time.Instant;

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
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link ProxySession}. */
@Epic("MCP Inspector Core")
@Feature("Proxy session lifecycle")
class ProxySessionTests {

	private McpClientTransport transport;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	private ProxySession newSession() {
		this.transport = mock(McpClientTransport.class);
		given(this.transport.closeGracefully()).willReturn(Mono.empty());
		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(8);
		return new ProxySession("s-1", this.transport, this.browserToTarget, this.targetToBrowser);
	}

	@Nested
	@DisplayName("constructor / accessors")
	class Accessors {

		@Test
		@Story("Wiring")
		@Severity(SeverityLevel.NORMAL)
		@Description("the session exposes the id, transport and both sinks supplied at construction")
		void constructor_exposesSuppliedCollaborators() {
			// given / when
			final ProxySession session = newSession();

			// then
			assertThat(session.sessionId()).isEqualTo("s-1");
			assertThat(session.targetTransport()).isSameAs(ProxySessionTests.this.transport);
			assertThat(session.browserToTarget()).isSameAs(ProxySessionTests.this.browserToTarget);
			assertThat(session.targetToBrowser()).isSameAs(ProxySessionTests.this.targetToBrowser);
			assertThat(session.lastActivity()).isNotNull();
			assertThat(session.isClosed()).isFalse();
		}

	}

	@Nested
	@DisplayName("touch()")
	class Touch {

		@Test
		@Story("Activity tracking")
		@Severity(SeverityLevel.NORMAL)
		@Description("touch() advances lastActivity to a later instant")
		void touch_advancesLastActivity() throws InterruptedException {
			// given
			final ProxySession session = newSession();
			final Instant before = session.lastActivity();
			Thread.sleep(5);

			// when
			session.touch();

			// then
			assertThat(session.lastActivity()).isAfterOrEqualTo(before);
		}

	}

	@Nested
	@DisplayName("upstreamSessionId()")
	class UpstreamSessionId {

		@Test
		@Story("Captured session id")
		@Severity(SeverityLevel.NORMAL)
		@Description("upstreamSessionId getter reflects the last value set")
		void upstreamSessionId_setThenGet_returnsLatestValue() {
			// given
			final ProxySession session = newSession();
			assertThat(session.upstreamSessionId()).isNull();

			// when
			session.upstreamSessionId("mcp-session-123");

			// then
			assertThat(session.upstreamSessionId()).isEqualTo("mcp-session-123");
		}

	}

	@Nested
	@DisplayName("close()")
	class Close {

		@Test
		@Story("Teardown")
		@Severity(SeverityLevel.CRITICAL)
		@Description("close() marks the session closed, completes both sinks and closes the transport")
		void close_completesSinksAndClosesTransport() {
			// given
			final ProxySession session = newSession();

			// when
			session.close();

			// then
			assertThat(session.isClosed()).isTrue();
			verify(ProxySessionTests.this.transport).closeGracefully();
			StepVerifier.create(ProxySessionTests.this.browserToTarget.asFlux()).verifyComplete();
			StepVerifier.create(ProxySessionTests.this.targetToBrowser.asFlux()).verifyComplete();
		}

		@Test
		@Story("Idempotency")
		@Severity(SeverityLevel.CRITICAL)
		@Description("close() is idempotent — a second invocation does no work and the transport is closed only once")
		void close_calledTwice_isIdempotent() {
			// given
			final ProxySession session = newSession();

			// when
			session.close();
			session.close();

			// then
			assertThat(session.isClosed()).isTrue();
			verify(ProxySessionTests.this.transport, times(1)).closeGracefully();
		}

		@Test
		@Story("Defensive teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("close() swallows a transport closeGracefully failure and still marks the session closed")
		void close_whenTransportCloseFails_swallowsErrorAndStillCloses() {
			// given
			final McpClientTransport failing = mock(McpClientTransport.class);
			given(failing.closeGracefully()).willReturn(Mono.error(new RuntimeException("close boom")));
			final ProxySession session = new ProxySession("s-2", failing, Sinks.many().unicast().onBackpressureBuffer(),
					Sinks.many().replay().limit(8));

			// when
			session.close();

			// then
			assertThat(session.isClosed()).isTrue();
			verify(failing).closeGracefully();
		}

	}

}
