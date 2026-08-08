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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.McpSyncClient;

import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;

/**
 * Servlet-stack per-session state. Holds the loopback MCP client, the mutable list of
 * roots advertised to the server, the pending server-to-client request bridge, and the
 * in-flight OAuth state / token (Auth Debugger).
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

	/**
	 * Tears the loopback client down and waits for it. {@code close()} would not do:
	 * {@code McpTransport.close()} is {@code closeGracefully().subscribe()}, so the
	 * session-termination request is merely dispatched. On context close that races
	 * Boot's graceful-shutdown phase — the server-side session is still holding its
	 * {@code GET /mcp} stream open when the wait starts, and the whole
	 * {@code spring.lifecycle.timeout-per-shutdown-phase} gets paid. {@code
	 * closeGracefully()} blocks until the teardown lands (bounded by the SDK at 10s) and
	 * never throws, which is what the reactive {@code SessionContext} already does.
	 */
	void closeQuietly() {
		this.pendingServerRequests.clear();
		if (this.client != null) {
			try {
				this.client.closeGracefully();
			}
			catch (final Exception ignored) {
				try {
					this.client.close();
				}
				catch (final Exception ignored2) {
					/* best-effort */
				}
			}
		}
	}

}
