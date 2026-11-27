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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live outbound MCP client connections captured by
 * {@link RecordingMcpClientTransport}. Tracks a per-client state machine INITIALIZING
 * &rarr; CONNECTED | FAILED &rarr; CLOSED plus inbound/outbound message counters. State
 * transitions are fed from {@code connect}, {@code sendMessage}, the wrapped exception
 * handler and {@code closeGracefully} of the recording transport; a new {@code connect}
 * attempt on a closed connection re-enters INITIALIZING.
 *
 * @author Artem Simeshin
 */
public final class ClientConnectionRegistry {

	private final Map<String, ClientState> clients = new ConcurrentHashMap<>();

	private final Map<String, String> discoveredClients = new ConcurrentHashMap<>();

	/**
	 * Marks a connect attempt for the client. Creates the entry when absent and
	 * (re-)enters INITIALIZING.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType a transport type label, may be {@code null}
	 * @param target the sanitized connection target, may be {@code null}
	 */
	public void markInitializing(final String clientName, final String transportType, final String target) {
		this.clients.computeIfAbsent(clientName, ClientState::new).initialize(transportType, target);
	}

	/**
	 * Records that a client transport was discovered at bean creation, independent of the
	 * connection lifecycle: this is the set of configured clients used by
	 * {@link ClientDesyncDetector}, populated by {@link RecordingTransportPostProcessor}
	 * when it wraps the transport, before any connect attempt.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param transportType a transport type label, may be {@code null}
	 */
	public void recordDiscovered(final String clientName, final String transportType) {
		this.discoveredClients.putIfAbsent(clientName, transportType);
	}

	/**
	 * Returns a snapshot of all discovered clients keyed by name with their transport
	 * type.
	 * @return the discovered clients (never {@code null})
	 */
	public Map<String, String> discoveredClients() {
		return Map.copyOf(this.discoveredClients);
	}

	/**
	 * Marks the client connected. Only transitions from INITIALIZING.
	 * @param clientName the configured client name (must not be {@code null})
	 */
	public void markConnected(final String clientName) {
		final ClientState state = this.clients.get(clientName);
		if (state != null) {
			state.connected();
		}
	}

	/**
	 * Marks the client failed with the given error. Transitions from INITIALIZING and
	 * CONNECTED; CLOSED is terminal and ignores later failures.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param error the failure description (may be {@code null})
	 */
	public void markFailed(final String clientName, final String error) {
		final ClientState state = this.clients.get(clientName);
		if (state != null) {
			state.failed(error);
		}
	}

	/**
	 * Marks the client closed. Transitions from any non-CLOSED state.
	 * @param clientName the configured client name (must not be {@code null})
	 */
	public void markClosed(final String clientName) {
		final ClientState state = this.clients.get(clientName);
		if (state != null) {
			state.closed();
		}
	}

	/**
	 * Counts one inbound JSON-RPC message for the client. Creates the entry when absent.
	 * @param clientName the configured client name (must not be {@code null})
	 */
	public void recordInbound(final String clientName) {
		this.clients.computeIfAbsent(clientName, ClientState::new).inbound();
	}

	/**
	 * Counts one outbound JSON-RPC message for the client. Creates the entry when absent.
	 * @param clientName the configured client name (must not be {@code null})
	 */
	public void recordOutbound(final String clientName) {
		this.clients.computeIfAbsent(clientName, ClientState::new).outbound();
	}

	/**
	 * Returns a snapshot of all tracked connections, sorted by client name.
	 * @return the snapshots, one per tracked client
	 */
	public List<ClientConnectionSnapshot> snapshot() {
		return this.clients.values()
			.stream()
			.map(ClientState::snapshot)
			.sorted(Comparator.comparing(ClientConnectionSnapshot::name))
			.toList();
	}

	/** Connection lifecycle states. */
	public enum ConnectionState {

		/** A connect attempt is in flight. */
		INITIALIZING,

		/** The connect mono completed successfully. */
		CONNECTED,

		/** The connect mono failed or the wrapped exception handler fired. */
		FAILED,

		/** {@code closeGracefully} completed successfully. */
		CLOSED

	}

	/**
	 * Immutable snapshot of one client connection.
	 *
	 * @param name the configured client name
	 * @param transportType a transport type label, or {@code null} when not yet known
	 * @param target the sanitized connection target, or {@code null} when not resolvable
	 * @param state the current lifecycle state
	 * @param since the instant the current state was entered
	 * @param lastActivity the instant of the last inbound or outbound message, or
	 * {@code null} when no message was seen
	 * @param inCount the number of inbound JSON-RPC messages
	 * @param outCount the number of outbound JSON-RPC messages
	 * @param lastError the last failure description, or {@code null} when none
	 */
	public record ClientConnectionSnapshot(String name, String transportType, String target, ConnectionState state,
			Instant since, Instant lastActivity, long inCount, long outCount, String lastError) {
	}

	/**
	 * Mutable per-client state guarded by the instance monitor.
	 */
	private static final class ClientState {

		private final String name;

		private String transportType;

		private String target;

		private ConnectionState state = ConnectionState.INITIALIZING;

		private Instant since = Instant.now();

		private Instant lastActivity;

		private long inCount;

		private long outCount;

		private String lastError;

		private ClientState(final String name) {
			this.name = name;
		}

		private synchronized void initialize(final String transportType, final String target) {
			this.transportType = transportType;
			this.target = target;
			this.state = ConnectionState.INITIALIZING;
			this.since = Instant.now();
		}

		private synchronized void connected() {
			if (this.state == ConnectionState.INITIALIZING) {
				this.state = ConnectionState.CONNECTED;
				this.since = Instant.now();
			}
		}

		private synchronized void failed(final String error) {
			if (this.state == ConnectionState.CLOSED) {
				return;
			}
			this.lastError = error;
			if (this.state != ConnectionState.FAILED) {
				this.state = ConnectionState.FAILED;
				this.since = Instant.now();
			}
		}

		private synchronized void closed() {
			if (this.state != ConnectionState.CLOSED) {
				this.state = ConnectionState.CLOSED;
				this.since = Instant.now();
			}
		}

		private synchronized void inbound() {
			this.inCount++;
			this.lastActivity = Instant.now();
		}

		private synchronized void outbound() {
			this.outCount++;
			this.lastActivity = Instant.now();
		}

		private synchronized ClientConnectionSnapshot snapshot() {
			return new ClientConnectionSnapshot(this.name, this.transportType, this.target, this.state, this.since,
					this.lastActivity, this.inCount, this.outCount, this.lastError);
		}

	}

}
