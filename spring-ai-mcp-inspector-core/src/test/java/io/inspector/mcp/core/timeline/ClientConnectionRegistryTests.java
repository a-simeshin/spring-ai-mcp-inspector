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

package io.inspector.mcp.core.timeline;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientConnectionRegistry} state transitions and counters.
 *
 * @author Artem Simeshin
 */
class ClientConnectionRegistryTests {

	private final ClientConnectionRegistry registry = new ClientConnectionRegistry();

	@Test
	@DisplayName("INITIALIZING to CONNECTED to CLOSED happy path")
	void initializingThenConnectedThenClosed() {
		this.registry.markInitializing("weather", "streamable-http", "https://api.example/mcp");
		assertThat(stateOf("weather")).isEqualTo(ClientConnectionRegistry.ConnectionState.INITIALIZING);
		this.registry.markConnected("weather");
		assertThat(stateOf("weather")).isEqualTo(ClientConnectionRegistry.ConnectionState.CONNECTED);
		this.registry.markClosed("weather");
		assertThat(stateOf("weather")).isEqualTo(ClientConnectionRegistry.ConnectionState.CLOSED);
	}

	@Test
	@DisplayName("INITIALIZING to FAILED to CLOSED failure path")
	void initializingThenFailedThenClosed() {
		this.registry.markInitializing("weather", "sse", "https://api.example/sse");
		this.registry.markFailed("weather", "connect timed out");
		assertThat(stateOf("weather")).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
		this.registry.markClosed("weather");
		assertThat(stateOf("weather")).isEqualTo(ClientConnectionRegistry.ConnectionState.CLOSED);
	}

	@Test
	@DisplayName("markConnected only transitions from INITIALIZING")
	void connectedOnlyFromInitializing() {
		this.registry.markInitializing("a", "stdio", "echo");
		this.registry.markFailed("a", "boom");
		this.registry.markConnected("a");
		assertThat(stateOf("a")).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
	}

	@Test
	@DisplayName("CLOSED is terminal for later failures")
	void closedIsTerminalForFailure() {
		this.registry.markInitializing("b", "sse", "https://api.example/sse");
		this.registry.markConnected("b");
		this.registry.markClosed("b");
		this.registry.markFailed("b", "late error");
		assertThat(stateOf("b")).isEqualTo(ClientConnectionRegistry.ConnectionState.CLOSED);
		final ClientConnectionRegistry.ClientConnectionSnapshot snapshot = snapshotOf("b");
		assertThat(snapshot.lastError()).isNull();
	}

	@Test
	@DisplayName("markFailed updates lastError while already FAILED")
	void failedStaysFailedAndUpdatesError() {
		this.registry.markInitializing("c", "stdio", "echo");
		this.registry.markFailed("c", "first");
		final Instant sinceAfterFirst = snapshotOf("c").since();
		this.registry.markFailed("c", "second");
		assertThat(stateOf("c")).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
		assertThat(snapshotOf("c").lastError()).isEqualTo("second");
		assertThat(snapshotOf("c").since()).isEqualTo(sinceAfterFirst);
	}

	@Test
	@DisplayName("a new connect attempt on a closed connection re-enters INITIALIZING")
	void reconnectAfterClosedReentersInitializing() {
		this.registry.markInitializing("d", "sse", "https://api.example/sse");
		this.registry.markConnected("d");
		this.registry.markClosed("d");
		this.registry.markInitializing("d", "sse", "https://api.example/sse");
		assertThat(stateOf("d")).isEqualTo(ClientConnectionRegistry.ConnectionState.INITIALIZING);
	}

	@Test
	@DisplayName("counters increment and message activity is recorded")
	void countersIncrement() {
		this.registry.recordOutbound("weather");
		this.registry.recordOutbound("weather");
		this.registry.recordInbound("weather");
		final ClientConnectionRegistry.ClientConnectionSnapshot snapshot = snapshotOf("weather");
		assertThat(snapshot.outCount()).isEqualTo(2L);
		assertThat(snapshot.inCount()).isEqualTo(1L);
		assertThat(snapshot.lastActivity()).isNotNull();
		// Message counters create the entry on first sight.
		assertThat(snapshot.state()).isEqualTo(ClientConnectionRegistry.ConnectionState.INITIALIZING);
		assertThat(snapshot.transportType()).isNull();
	}

	@Test
	@DisplayName("operations on unknown clients are safe no-ops for transitions")
	void unknownClientTransitionsAreNoOps() {
		this.registry.markConnected("ghost");
		this.registry.markFailed("ghost", "boom");
		this.registry.markClosed("ghost");
		assertThat(this.registry.snapshot()).isEmpty();
	}

	@Test
	@DisplayName("snapshot is sorted by name and carries provenance")
	void snapshotSortedAndCarriesProvenance() {
		this.registry.markInitializing("zulu", "sse", "https://api.example/sse");
		this.registry.markInitializing("alpha", "streamable-http", "https://api.example/mcp");
		this.registry.markConnected("alpha");
		this.registry.recordOutbound("alpha");
		this.registry.recordInbound("alpha");
		final List<ClientConnectionRegistry.ClientConnectionSnapshot> snapshots = this.registry.snapshot();
		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.get(0).name()).isEqualTo("alpha");
		assertThat(snapshots.get(1).name()).isEqualTo("zulu");
		final ClientConnectionRegistry.ClientConnectionSnapshot alpha = snapshots.get(0);
		assertThat(alpha.transportType()).isEqualTo("streamable-http");
		assertThat(alpha.target()).isEqualTo("https://api.example/mcp");
		assertThat(alpha.state()).isEqualTo(ClientConnectionRegistry.ConnectionState.CONNECTED);
		assertThat(alpha.since()).isNotNull();
		assertThat(alpha.lastActivity()).isNotNull();
		assertThat(alpha.inCount()).isEqualTo(1L);
		assertThat(alpha.outCount()).isEqualTo(1L);
		assertThat(alpha.lastError()).isNull();
	}

	@Test
	@DisplayName("markFailed records the error and transitions from CONNECTED")
	void connectedFailsWithError() {
		this.registry.markInitializing("e", "streamable-http", "https://api.example/mcp");
		this.registry.markConnected("e");
		this.registry.markFailed("e", "connection reset");
		final ClientConnectionRegistry.ClientConnectionSnapshot snapshot = snapshotOf("e");
		assertThat(snapshot.state()).isEqualTo(ClientConnectionRegistry.ConnectionState.FAILED);
		assertThat(snapshot.lastError()).isEqualTo("connection reset");
	}

	private ClientConnectionRegistry.ConnectionState stateOf(final String name) {
		return snapshotOf(name).state();
	}

	private ClientConnectionRegistry.ClientConnectionSnapshot snapshotOf(final String name) {
		return this.registry.snapshot().stream().filter((s) -> s.name().equals(name)).findFirst().orElseThrow();
	}

}
