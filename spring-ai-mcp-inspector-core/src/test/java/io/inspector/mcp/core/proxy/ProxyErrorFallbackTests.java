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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the terminal SSE error payload of the 1.x line: the simple
 * message+reason object both stacks deliver on the {@code error} event of a dead
 * upstream.
 *
 * @author Artem Simeshin
 */
class ProxyErrorFallbackTests {

	@Test
	@DisplayName("upstream death payload carries the fixed message and the classified reason")
	void upstreamDeathPayload_carriesMessageAndClassifiedReason() {
		final var payload = ProxyErrorFallback.upstreamDeathPayload(new ConnectException("Connection refused"));
		assertThat(payload.get("message")).isEqualTo(ProxyErrorFallback.UPSTREAM_DEATH_MESSAGE);
		assertThat(payload.get("reason")).isEqualTo("connection_refused");
	}

	@Test
	@DisplayName("an unclassifiable failure still has a payload: reason falls back to unknown")
	void upstreamDeathPayload_unclassifiableFallsBackToUnknownReason() {
		final var payload = ProxyErrorFallback.upstreamDeathPayload(new IllegalStateException("boom"));
		assertThat(payload.get("message")).isEqualTo(ProxyErrorFallback.UPSTREAM_DEATH_MESSAGE);
		assertThat(payload.get("reason")).isEqualTo("unknown");
	}

	@Test
	@DisplayName("isUpstreamDeath mirrors the reconnect policy classification")
	void isUpstreamDeath_mirrorsPolicy() {
		assertThat(ProxyErrorFallback.isUpstreamDeath(new ConnectException("Connection refused"))).isTrue();
		assertThat(ProxyErrorFallback.isUpstreamDeath(new IllegalStateException("boom"))).isFalse();
	}

	@Test
	@DisplayName("payloadJson serialises both fields into the SSE data line")
	void payloadJson_serialisesBothFields() {
		final String json = ProxyErrorFallback.payloadJson(new ObjectMapper(),
				new ConnectException("Connection refused"));
		assertThat(json).contains(ProxyErrorFallback.UPSTREAM_DEATH_MESSAGE).contains("connection_refused");
	}

}
