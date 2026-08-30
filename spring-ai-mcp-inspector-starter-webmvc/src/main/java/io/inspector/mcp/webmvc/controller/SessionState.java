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

package io.inspector.mcp.webmvc.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;

/**
 * Servlet-stack per-session state. Holds the loopback MCP client, the mutable list of
 * roots advertised to the server, the pending server-to-client request bridge, the
 * in-flight OAuth state / token (Auth Debugger), and the initialize-handshake snapshot.
 *
 * @author Artem Simeshin
 */
final class SessionState {

	private final McpSyncClient client;

	private final List<RootDto> roots = new CopyOnWriteArrayList<>();

	private final PendingServerRequests pendingServerRequests = new PendingServerRequests();

	private volatile String oauthState;

	private volatile String oauthTokenEndpoint;

	private volatile String oauthClientId;

	private volatile String oauthRedirectUri;

	private volatile OAuthTokenResponse oauthToken;

	private volatile InitializeSnapshot initializeSnapshot;

	SessionState(final McpSyncClient client) {
		this.client = client;
	}

	McpSyncClient client() {
		return this.client;
	}

	List<RootDto> roots() {
		return this.roots;
	}

	PendingServerRequests pendingServerRequests() {
		return this.pendingServerRequests;
	}

	void replaceRoots(final List<RootDto> next) {
		this.roots.clear();
		if (next != null) {
			this.roots.addAll(new ArrayList<>(next));
		}
	}

	String oauthState() {
		return this.oauthState;
	}

	void oauthState(final String value) {
		this.oauthState = value;
	}

	String oauthTokenEndpoint() {
		return this.oauthTokenEndpoint;
	}

	void oauthTokenEndpoint(final String value) {
		this.oauthTokenEndpoint = value;
	}

	String oauthClientId() {
		return this.oauthClientId;
	}

	void oauthClientId(final String value) {
		this.oauthClientId = value;
	}

	String oauthRedirectUri() {
		return this.oauthRedirectUri;
	}

	void oauthRedirectUri(final String value) {
		this.oauthRedirectUri = value;
	}

	OAuthTokenResponse oauthToken() {
		return this.oauthToken;
	}

	void oauthToken(final OAuthTokenResponse value) {
		this.oauthToken = value;
	}

	InitializeSnapshot initializeSnapshot() {
		return this.initializeSnapshot;
	}

	void initializeSnapshot(final InitializeSnapshot value) {
		this.initializeSnapshot = value;
	}

	/**
	 * Tears the loopback client down and waits for it. Plain {@code close()} would not
	 * do: {@code McpTransport.close()} is {@code closeGracefully().subscribe()}, so the
	 * session-termination request is merely dispatched. On context close that races
	 * Boot's graceful-shutdown phase — the server-side session is still holding its
	 * {@code GET /mcp} stream open when the wait starts, and the whole
	 * {@code spring.lifecycle.timeout-per-shutdown-phase} gets paid.
	 *
	 * <p>
	 * The result of {@code closeGracefully()} has to be checked, because it never throws:
	 * it blocks 10s, catches {@code RuntimeException}, logs "Client didn't close within
	 * timeout" and returns {@code false}. A {@code catch} around it is dead code, which
	 * left the wedged-transport case — the one that matters during shutdown — with
	 * nothing forcing it down. {@code close()} reaches {@code McpAsyncClient.close()},
	 * which does {@code initializer.close(); transport.close();} synchronously, so it is
	 * a real forcing step rather than a second no-op.
	 */
	void closeQuietly() {
		this.pendingServerRequests.clear();
		if (this.client == null) {
			return;
		}
		boolean closed = false;
		try {
			closed = this.client.closeGracefully();
		}
		catch (final Exception ignored) {
			/* documented not to happen; forced below either way */
		}
		if (!closed) {
			try {
				this.client.close();
			}
			catch (final Exception ignored) {
				/* best-effort */
			}
		}
	}

	/**
	 * Immutable snapshot of the MCP initialize handshake: what the loopback client
	 * requested, what the server negotiated, and the server's identity / capabilities.
	 *
	 * @param clientRequestedVersion the protocol version the loopback client sends in the
	 * {@code InitializeRequest}
	 * @param negotiatedVersion the protocol version the server responded with in the
	 * {@code InitializeResult}
	 * @param serverName the server's name ({@code serverInfo.name()})
	 * @param serverVersion the server's version ({@code serverInfo.version()})
	 * @param capabilities a map of the server's advertised capabilities
	 */
	record InitializeSnapshot(String clientRequestedVersion, String negotiatedVersion, String serverName,
			String serverVersion, Map<String, Object> capabilities) {

		/**
		 * Builds a snapshot from the server's {@link InitializeResult}.
		 * @param result the raw result returned by the loopback client's
		 * {@code initialize()} call; may be {@code null} (returns {@code null})
		 * @return a new snapshot with extracted fields, or {@code null} if result is
		 * {@code null}
		 */
		static InitializeSnapshot from(final McpSchema.InitializeResult result) {
			if (result == null) {
				return null;
			}
			final Map<String, Object> caps = new LinkedHashMap<>();
			if (result.capabilities() != null) {
				final McpSchema.ServerCapabilities sc = result.capabilities();
				if (sc.completions() != null) {
					caps.put("completions", sc.completions());
				}
				if (sc.experimental() != null) {
					caps.put("experimental", sc.experimental());
				}
				if (sc.logging() != null) {
					caps.put("logging", sc.logging());
				}
				if (sc.prompts() != null) {
					caps.put("prompts", sc.prompts());
				}
				if (sc.resources() != null) {
					caps.put("resources", sc.resources());
				}
				if (sc.tools() != null) {
					caps.put("tools", sc.tools());
				}
			}
			return new InitializeSnapshot(result.protocolVersion(), result.protocolVersion(),
					result.serverInfo().name(), result.serverInfo().version(), caps);
		}

	}

}
