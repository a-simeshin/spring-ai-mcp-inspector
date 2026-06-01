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
 *
 * @author Artem Simeshin
 */
public final class PendingServerRequests {

	private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

	/**
	 * Registers a new pending request and returns the future the caller should await.
	 * @param requestId unique identifier for this pending request
	 * @return a {@link CompletableFuture} that completes when the UI responds
	 */
	public CompletableFuture<JsonNode> create(final String requestId) {
		Assert.hasText(requestId, "requestId must not be blank");
		final CompletableFuture<JsonNode> future = new CompletableFuture<>();
		this.pending.put(requestId, future);
		return future;
	}

	/**
	 * Completes the future for {@code requestId} with the supplied JSON result.
	 * @param requestId the request identifier to complete
	 * @param result the JSON result from the UI
	 * @return {@code true} if a matching pending request was found and completed
	 */
	public boolean complete(final String requestId, final JsonNode result) {
		final CompletableFuture<JsonNode> future = this.pending.remove(requestId);
		if (future == null) {
			return false;
		}
		return future.complete(result);
	}

	/**
	 * Completes the future for {@code requestId} exceptionally.
	 * @param requestId the request identifier to fail
	 * @param error the exception to propagate
	 * @return {@code true} if a matching pending request was found and failed
	 */
	public boolean completeExceptionally(final String requestId, final Throwable error) {
		final CompletableFuture<JsonNode> future = this.pending.remove(requestId);
		if (future == null) {
			return false;
		}
		return future.completeExceptionally(error);
	}

	/** Drops all pending entries — used on session teardown. */
	public void clear() {
		this.pending.values()
			.forEach((f) -> f.completeExceptionally(new IllegalStateException("inspector session closed")));
		this.pending.clear();
	}

	/**
	 * Returns the number of pending requests — exposed for tests.
	 * @return current pending request count
	 */
	public int size() {
		return this.pending.size();
	}

}
