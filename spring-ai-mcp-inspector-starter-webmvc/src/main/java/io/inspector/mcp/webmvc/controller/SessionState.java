/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.inspector.mcp.core.client.PendingServerRequests;
import io.inspector.mcp.core.dto.RootDto;
import io.inspector.mcp.core.oauth.OAuthTokenResponse;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * Servlet-stack per-session state. Holds the loopback MCP client, the mutable list of
 * roots advertised to the server, the pending server-to-client request bridge, and the
 * in-flight OAuth state / token (Auth Debugger).
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

	SessionState(McpSyncClient client) {
		this.client = client;
	}

	McpSyncClient client() {
		return client;
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
		if (client != null) {
			try {
				client.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
		}
	}

}
