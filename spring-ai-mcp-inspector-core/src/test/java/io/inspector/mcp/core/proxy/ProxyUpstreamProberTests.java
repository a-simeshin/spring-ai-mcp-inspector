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
import com.fasterxml.jackson.databind.ObjectMapper;
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

	private final ObjectMapper mapper = new ObjectMapper();

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

	private static void sleep(final long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Nested
	@DisplayName("probe() \u2014 idle session detection")
	class IdleDetection {

		@Test
		@Story("Probe sent when session is idle beyond threshold")
		@Severity(SeverityLevel.CRITICAL)
		@Description("a session whose lastActivity is older than the idle threshold receives a ping probe")
		void probe_sendsPingForIdleSession() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.sleep(FAST_IDLE_THRESHOLD.toMillis() + 50);

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
				.willReturn(Mono.error(new RuntimeException("probe failed")));
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.sleep(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			verify(ProxyUpstreamProberTests.this.transport, timeout(2000)).sendMessage(any());
			// failUpstream runs async on boundedElastic - poll until it completes
			final long deadline = System.currentTimeMillis() + 3000;
			while (!ProxyUpstreamProberTests.this.session.isUpstreamTerminated()
					&& System.currentTimeMillis() < deadline) {
				ProxyUpstreamProberTests.sleep(50);
			}
			assertThat(ProxyUpstreamProberTests.this.session.isUpstreamTerminated()).isTrue();
		}

		@Test
		@Story("No probe when lastActivity is null")
		@Severity(SeverityLevel.NORMAL)
		@Description("a session with null lastActivity is not probed")
		void probe_skipsNullLastActivity() throws Exception {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Create a session and set its lastActivity to null via reflection,
			// because the constructor always sets it to Instant.now().
			final Sinks.Many<JsonNode> bt = Sinks.many().unicast().onBackpressureBuffer();
			final Sinks.Many<JsonNode> tb = Sinks.many().replay().limit(64);
			final ProxySession nullLastSession = new ProxySession("s-null", ProxyUpstreamProberTests.this.transport, bt,
					tb);
			// Remove the original session from the registry so it does not get probed.
			ProxyUpstreamProberTests.this.registry.removeAndClose(ProxyUpstreamProberTests.this.session.sessionId());
			final java.lang.reflect.Field lastActivityField = ProxySession.class.getDeclaredField("lastActivity");
			lastActivityField.setAccessible(true);
			lastActivityField.set(nullLastSession, null);
			ProxyUpstreamProberTests.this.registry.put(nullLastSession);
			// Wait beyond idle threshold so the prober would consider this session idle
			// if lastActivity were non-null.
			ProxyUpstreamProberTests.sleep(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			// The null-lastActivity session should not be probed, and the
			// transport should never receive sendMessage either.
			verify(ProxyUpstreamProberTests.this.transport, never()).sendMessage(any());
		}

		@Test
		@Story("Probe response is registered as a probe ID")
		@Severity(SeverityLevel.NORMAL)
		@Description("the probe request ID is registered in the session so McpProxy can filter the response")
		void probe_registersProbeId() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.sleep(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(ProxyUpstreamProberTests.this.transport, timeout(1000)).sendMessage(captor.capture());
			final McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) captor.getValue();
			final Object id = req.id();
			assertThat(id).isInstanceOf(Integer.class);
			assertThat(ProxyUpstreamProberTests.this.session.isProbeId((Integer) id)).isTrue();
		}

		@Test
		@Story("Response-level timeout when sendMessage accepts but no response arrives")
		@Severity(SeverityLevel.CRITICAL)
		@Description("when sendMessage returns Mono.empty() (HTTP 202 accepted) but no JSON-RPC "
				+ "response arrives, the response-level deadline is set and the probe ID is cleaned up")
		void probe_timesOutAfterAcceptedPostWithoutResponse() {
			// given
			given(ProxyUpstreamProberTests.this.transport.sendMessage(any())).willReturn(Mono.empty());
			// Wait beyond the idle threshold so the prober considers this session idle
			ProxyUpstreamProberTests.sleep(FAST_IDLE_THRESHOLD.toMillis() + 50);

			// when
			ProxyUpstreamProberTests.this.prober.probe();

			// then
			// sendMessage must have been called (HTTP 202 accepted)
			final ArgumentCaptor<JSONRPCMessage> captor = ArgumentCaptor.forClass(JSONRPCMessage.class);
			verify(ProxyUpstreamProberTests.this.transport, timeout(2000)).sendMessage(captor.capture());
			final McpSchema.JSONRPCRequest req = (McpSchema.JSONRPCRequest) captor.getValue();
			assertThat(req.method()).isEqualTo("ping");
			// The probe ID was registered in the session so McpProxy can filter
			// the response. The response-level deadline is scheduled asynchronously
			// to clean up the probe ID if the response never arrives.
			final Object id = req.id();
			assertThat(id).isInstanceOf(Integer.class);
			assertThat(ProxyUpstreamProberTests.this.session.isProbeId((Integer) id)).isTrue();

			// Wait for the response-level deadline to fire and clean up the probe ID.
			// The deadline is Mono.delay(probeTimeout) which is FAST_PROBE (100ms).
			// The async chain (sendMessage → onComplete → Mono.delay → callback)
			// runs on boundedElastic and parallel schedulers.
			ProxyUpstreamProberTests.sleep(5000);

			// The probe ID must be removed after the deadline fires.
			assertThat(ProxyUpstreamProberTests.this.session.isProbeId((Integer) id)).isFalse();
			// The session must be terminated via failUpstream.
			assertThat(ProxyUpstreamProberTests.this.session.isUpstreamTerminated()).isTrue();
		}

	}

}
