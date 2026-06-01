/*
 * Copyright 2025-present the original author or authors.
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

package io.inspector.mcp.webflux.router;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;

/**
 * Per-inspector-session state: the loopback {@link McpSyncClient} created on
 * {@code POST /api/connect} plus the SSE sink that pushes server-side notifications to
 * the UI's {@code /api/events} subscription. Additionally holds the mutable roots list,
 * the bridge for pending server-to-client requests (sampling / elicitation), and the
 * in-flight OAuth state / token used by the Auth Debugger.
 *
 * @author Artem Simeshin
 */
final class SessionContext {

	private final McpSyncClient client;

	private final Sinks.Many<ServerSentEvent<String>> sink;

	private final List<RootDto> roots = new CopyOnWriteArrayList<>();

	private final PendingServerRequests pendingServerRequests = new PendingServerRequests();

	private volatile String oauthState;

	private volatile String oauthTokenEndpoint;

	private volatile String oauthClientId;

	private volatile String oauthRedirectUri;

	private volatile OAuthTokenResponse oauthToken;

	SessionContext(final McpSyncClient client, final Sinks.Many<ServerSentEvent<String>> sink) {
		this.client = client;
		this.sink = sink;
	}

	McpSyncClient client() {
		return this.client;
	}

	Sinks.Many<ServerSentEvent<String>> sink() {
		return this.sink;
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

	void closeQuietly() {
		this.pendingServerRequests.clear();
		try {
			this.sink.tryEmitComplete();
		}
		catch (final RuntimeException ignored) {
			/* sink already terminated */
		}
		if (this.client != null) {
			try {
				this.client.closeGracefully();
			}
			catch (final RuntimeException ignored) {
				try {
					this.client.close();
				}
				catch (final RuntimeException ignored2) {
					/* best-effort */
				}
			}
		}
	}

}
