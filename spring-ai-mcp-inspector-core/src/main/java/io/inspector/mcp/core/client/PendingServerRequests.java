/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.util.Assert;

/**
 * Per-session registry of MCP server-to-client requests (e.g.
 * {@code sampling/createMessage}, {@code elicitation/create}) that are waiting for the
 * inspector UI to provide an answer.
 *
 * <p>
 * When the loopback {@code McpSyncClient} receives a request from the backing MCP server,
 * the handler creates a future via {@link #create(String)}, pushes the request payload to
 * the SSE channel and blocks on the future. The UI eventually calls
 * {@code POST /api/jsonrpc/respond?requestId=...} which dispatches to
 * {@link #complete(String, JsonNode)} (or
 * {@link #completeExceptionally(String, Throwable)}).
 */
public final class PendingServerRequests {

	private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

	/** Registers a new pending request and returns the future the caller should await. */
	public CompletableFuture<JsonNode> create(String requestId) {
		Assert.hasText(requestId, "requestId must not be blank");
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pending.put(requestId, future);
		return future;
	}

	/** Completes the future for {@code requestId} with the supplied JSON result. */
	public boolean complete(String requestId, JsonNode result) {
		CompletableFuture<JsonNode> future = pending.remove(requestId);
		if (future == null) {
			return false;
		}
		return future.complete(result);
	}

	/** Completes the future for {@code requestId} exceptionally. */
	public boolean completeExceptionally(String requestId, Throwable error) {
		CompletableFuture<JsonNode> future = pending.remove(requestId);
		if (future == null) {
			return false;
		}
		return future.completeExceptionally(error);
	}

	/** Drops all pending entries — used on session teardown. */
	public void clear() {
		pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("inspector session closed")));
		pending.clear();
	}

	/** Exposed for tests. */
	public int size() {
		return pending.size();
	}

}
