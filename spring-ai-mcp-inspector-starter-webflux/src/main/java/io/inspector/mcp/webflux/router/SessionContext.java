/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webflux.router;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * Per-inspector-session state: the loopback {@link McpSyncClient} created on
 * {@code POST /api/connect} plus the SSE sink that pushes server-side notifications to
 * the UI's {@code /api/events} subscription. Additionally holds the mutable roots list,
 * the bridge for pending server-to-client requests (sampling / elicitation), and the
 * in-flight OAuth state / token used by the Auth Debugger.
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

	SessionContext(McpSyncClient client, Sinks.Many<ServerSentEvent<String>> sink) {
		this.client = client;
		this.sink = sink;
	}

	McpSyncClient client() {
		return client;
	}

	Sinks.Many<ServerSentEvent<String>> sink() {
		return sink;
	}

	List<RootDto> roots() {
		return roots;
	}

	PendingServerRequests pendingServerRequests() {
		return pendingServerRequests;
	}

	void replaceRoots(List<RootDto> next) {
		roots.clear();
		if (next != null) {
			roots.addAll(new ArrayList<>(next));
		}
	}

	String oauthState() {
		return oauthState;
	}

	void oauthState(String value) {
		this.oauthState = value;
	}

	String oauthTokenEndpoint() {
		return oauthTokenEndpoint;
	}

	void oauthTokenEndpoint(String value) {
		this.oauthTokenEndpoint = value;
	}

	String oauthClientId() {
		return oauthClientId;
	}

	void oauthClientId(String value) {
		this.oauthClientId = value;
	}

	String oauthRedirectUri() {
		return oauthRedirectUri;
	}

	void oauthRedirectUri(String value) {
		this.oauthRedirectUri = value;
	}

	OAuthTokenResponse oauthToken() {
		return oauthToken;
	}

	void oauthToken(OAuthTokenResponse value) {
		this.oauthToken = value;
	}

	void closeQuietly() {
		pendingServerRequests.clear();
		try {
			sink.tryEmitComplete();
		}
		catch (RuntimeException ignored) {
			/* sink already terminated */
		}
		if (client != null) {
			try {
				client.closeGracefully();
			}
			catch (RuntimeException ignored) {
				try {
					client.close();
				}
				catch (RuntimeException ignored2) {
					/* best-effort */
				}
			}
		}
	}

}
