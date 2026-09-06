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
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;

import io.inspector.mcp.core.config.McpInspectorProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Unit tests for {@link ProxyUpstreamProber}. */
@Epic("MCP Inspector Core")
@Feature("Upstream liveness probing")
class ProxyUpstreamProberTests {

	private static final Duration FAST_PROBE = Duration.ofMillis(100);

	private static final Duration FAST_IDLE_THRESHOLD = Duration.ofMillis(50);

	private ProxySessionRegistry registry;

	private ProxyUpstreamProber prober;

	private McpClientTransport transport;

	private ProxySession session;

	private Sinks.Many<JsonNode> browserToTarget;

	private Sinks.Many<JsonNode> targetToBrowser;

	@BeforeEach
	void setUp() {
		this.registry = new ProxySessionRegistry();
		this.transport = mock(McpClientTransport.class);
		given(this.transport.closeGracefully()).willReturn(Mono.empty());

		final McpInspectorProperties.Timeouts timeouts = new McpInspectorProperties.Timeouts();
		timeouts.setUpstreamProbeInterval(FAST_PROBE);
		timeouts.setUpstreamProbeTimeout(FAST_PROBE);
		timeouts.setUpstreamProbeIdleThreshold(FAST_IDLE_THRESHOLD);

		this.prober = new ProxyUpstreamProber(this.registry, timeouts);

		this.browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		this.targetToBrowser = Sinks.many().replay().limit(64);
		this.session = new ProxySession("s-1", this.transport, this.browserToTarget, this.targetToBrowser);
		this.registry.put(this.session);
	}

	private static void await(final long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Nested
	@DisplayName("probe(): idle session detection")
	class IdleDetection {

		@Test
		@Story("Probe sent when session is idle beyond threshold")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a session whose lastActivity is older than the idle threshold receives a ping probe")
		void probe_sendsPingForIdleSession() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.await(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(ProxyUpstreamProberTests.this.transport, timeout(1000)).sendMessage(captor.capture());
			final JSONRPCMessage sent = captor.getValue();
			assertThat(sent).isInstanceOf(McpSchema.JSONRPCRequest.class);
			final McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) sent;
			assertThat(req.method()).isEqualTo("ping");
		}

		@Test
		@Story("No probe for recently active session")
		@Severity(SeverityLevel.NORMAL)
		@Description("a session with recent activity is not probed")
		void probe_skipsRecentlyActiveSession() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Touch the session just before the probe
			ProxyUpstreamProberTests.this.session.touch();

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			verify(ProxyUpstreamProberTests.this.transport, never()).sendMessage(any());
		}

		@Test
		@Story("No probe for closed session")
		@Severity(SeverityLevel.NORMAL)
		@Description("a closed session is not probed")
		void probe_skipsClosedSession() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			ProxyUpstreamProberTests.this.session.close();

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			verify(ProxyUpstreamProberTests.this.transport, never()).sendMessage(any());
		}

		@Test
		@Story("Probe failure triggers failUpstream")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when sendMessage errors, the session is terminated via failUpstream")
		void probe_failUpstreamOnSendError() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new java.net.ConnectException("connection refused")));
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.await(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			verify(ProxyUpstreamProberTests.this.transport, timeout(2000)).sendMessage(any());
			// failUpstream runs async on boundedElastic - poll until it completes
			final long deadline = System.currentTimeMillis() + 3000;
			while (!ProxyUpstreamProberTests.this.session.isUpstreamTerminated()
					&& System.currentTimeMillis() < deadline) {
				ProxyUpstreamProberTests.await(50);
			}
			assertThat(ProxyUpstreamProberTests.this.session.isUpstreamTerminated()).isTrue();
		}

		@Test
		@Story("Unknown probe failure does NOT fail the session")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when sendMessage errors with an unclassified error (e.g. HTTP 4xx), "
				+ "the session is NOT terminated (only transport-level failures justify teardown)")
		void probe_unknownError_doesNotFailSession() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any()))
				.willReturn(Mono.error(new RuntimeException("unexpected 400 Bad Request")));
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.await(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			verify(ProxyUpstreamProberTests.this.transport, timeout(2000)).sendMessage(any());
			// The session must NOT be failed for UNKNOWN errors
			assertThat(ProxyUpstreamProberTests.this.session.isUpstreamTerminated()).isFalse();
		}

		@Test
		@Story("Probe response is registered as a probe ID")
		@Severity(SeverityLevel.NORMAL)
		@Description("the probe request ID is registered in the session so McpProxy can filter the response")
		void probe_registersProbeId() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.await(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(ProxyUpstreamProberTests.this.transport, timeout(1000)).sendMessage(captor.capture());
			final McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) captor.getValue();
			final Object id = req.id();
			assertThat(id).isInstanceOf(String.class);
			assertThat((String) id).startsWith("mcpi-probe-");
			assertThat(ProxyUpstreamProberTests.this.session.isProbeId((String) id)).isTrue();
		}

	}

}
