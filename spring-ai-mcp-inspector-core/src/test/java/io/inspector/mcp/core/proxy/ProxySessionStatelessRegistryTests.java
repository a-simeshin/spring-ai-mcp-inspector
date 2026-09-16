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

import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import io.inspector.mcp.core.protocol.ProtocolMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for stateless session binding in {@link ProxySessionRegistry}.
 *
 * @author Artem Simeshin
 */
class ProxySessionStatelessRegistryTests {

	private ProxySessionRegistry registry;

	@BeforeEach
	void setUp() {
		this.registry = new ProxySessionRegistry();
	}

	@Test
	void putStatelessAndGetStateless_roundTrip() {
		final StatelessSessionKey key = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final ProxySession session = new ProxySession("s1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		session.protocolMode(ProtocolMode.STATELESS);
		session.statelessKey(key);

		this.registry.putStateless(session);
		assertThat(this.registry.getStateless(key)).isSameAs(session);
	}

	@Test
	void getStateless_unknownKey_returnsNull() {
		final StatelessSessionKey key = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		assertThat(this.registry.getStateless(key)).isNull();
	}

	@Test
	void removeStatelessAndClose_removesAndCloses() {
		final StatelessSessionKey key = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final ProxySession session = new ProxySession("s1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		session.protocolMode(ProtocolMode.STATELESS);
		session.statelessKey(key);

		this.registry.putStateless(session);
		assertThat(this.registry.removeStatelessAndClose(key)).isTrue();
		assertThat(this.registry.getStateless(key)).isNull();
		assertThat(session.isClosed()).isTrue();
	}

	@Test
	void removeStatelessAndClose_unknownKey_returnsFalse() {
		final StatelessSessionKey key = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		assertThat(this.registry.removeStatelessAndClose(key)).isFalse();
	}

	@Test
	void differentKeys_areIsolated() {
		final StatelessSessionKey key1 = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final StatelessSessionKey key2 = new StatelessSessionKey("http://localhost:8080/mcp", "fp2");
		final ProxySession s1 = new ProxySession("s1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		final ProxySession s2 = new ProxySession("s2", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		s1.protocolMode(ProtocolMode.STATELESS);
		s1.statelessKey(key1);
		s2.protocolMode(ProtocolMode.STATELESS);
		s2.statelessKey(key2);

		this.registry.putStateless(s1);
		this.registry.putStateless(s2);
		assertThat(this.registry.getStateless(key1)).isSameAs(s1);
		assertThat(this.registry.getStateless(key2)).isSameAs(s2);
	}

	@Test
	void sameKey_replacesSession() {
		final StatelessSessionKey key = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final ProxySession s1 = new ProxySession("s1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		final ProxySession s2 = new ProxySession("s2", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		s1.protocolMode(ProtocolMode.STATELESS);
		s1.statelessKey(key);
		s2.protocolMode(ProtocolMode.STATELESS);
		s2.statelessKey(key);

		this.registry.putStateless(s1);
		this.registry.putStateless(s2);
		assertThat(this.registry.getStateless(key)).isSameAs(s2);
	}

	@Test
	void legacyAndStateless_areIsolated() {
		final ProxySession legacy = new ProxySession("legacy-1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		final StatelessSessionKey statelessKey = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final ProxySession stateless = new ProxySession("stateless-1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		stateless.protocolMode(ProtocolMode.STATELESS);
		stateless.statelessKey(statelessKey);

		this.registry.put(legacy);
		this.registry.putStateless(stateless);

		assertThat(this.registry.get("legacy-1")).isSameAs(legacy);
		assertThat(this.registry.getStateless(statelessKey)).isSameAs(stateless);
		assertThat(this.registry.get("stateless-1")).isNull();
		assertThat(this.registry.size()).isEqualTo(2);
	}

	@Test
	void closeAll_drainsBothLegacyAndStateless() {
		final ProxySession legacy = new ProxySession("legacy-1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		final StatelessSessionKey statelessKey = new StatelessSessionKey("http://localhost:8080/mcp", "fp1");
		final ProxySession stateless = new ProxySession("stateless-1", mock(McpClientTransport.class),
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(10));
		stateless.protocolMode(ProtocolMode.STATELESS);
		stateless.statelessKey(statelessKey);

		this.registry.put(legacy);
		this.registry.putStateless(stateless);
		this.registry.closeAll();

		assertThat(this.registry.size()).isEqualTo(0);
		assertThat(legacy.isClosed()).isTrue();
		assertThat(stateless.isClosed()).isTrue();
	}

}
