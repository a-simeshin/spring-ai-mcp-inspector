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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ProxyReconnectPolicy}: only transport-level failures that prove the
 * upstream target is dead count as death (and so suppress the browser reconnect loop);
 * everything else keeps the normal cadence.
 *
 * @author Artem Simeshin
 */
class ProxyReconnectPolicyTests {

	@Test
	void connectionRefusedIsUpstreamDeath() {
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(new ConnectException("refused"))).isTrue();
	}

	@Test
	void dnsFailureIsUpstreamDeath() {
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(new UnknownHostException("no-such-host"))).isTrue();
	}

	@Test
	void timeoutIsUpstreamDeath() {
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(new SocketTimeoutException("timed out"))).isTrue();
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(new TimeoutException("timed out"))).isTrue();
	}

	@Test
	void unclassifiableFailureIsNotUpstreamDeath() {
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(new IllegalStateException("boom"))).isFalse();
	}

	@Test
	void wrappedTransportDeathIsUpstreamDeath() {
		final IllegalStateException wrapped = new IllegalStateException("handshake failed",
				new ConnectException("refused"));
		assertThat(ProxyReconnectPolicy.isUpstreamDeath(wrapped)).isTrue();
	}

}
