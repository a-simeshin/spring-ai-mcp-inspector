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
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link PendingServerRequests}. */
@Epic("MCP Inspector Core")
@Feature("Pending server-to-client requests")
class PendingServerRequestsTest {

	private final PendingServerRequests pending = new PendingServerRequests();

	private static final JsonNode RESULT = new TextNode("answer");

	@Nested
	@DisplayName("create()")
	class Create {

		@Test
		@Story("Registration")
		@Severity(SeverityLevel.CRITICAL)
		@Description("create() registers a pending future and increments the registry size")
		void create_withRequestId_registersAwaitingFuture() {
			// given
			final String requestId = "req-1";

			// when
			final CompletableFuture<JsonNode> future = pending.create(requestId);

			// then
			assertThat(future).isNotNull().isNotDone();
			assertThat(pending.size()).isEqualTo(1);
		}

		@Test
		@Story("Validation")
		@Severity(SeverityLevel.NORMAL)
		@Description("create() rejects a blank requestId")
		void create_withBlankRequestId_throwsIllegalArgument() {
			// when & then
			assertThatThrownBy(() -> pending.create("  ")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("requestId");
		}

	}

	@Nested
	@DisplayName("complete()")
	class Complete {

		@Test
		@Story("Successful completion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("complete() resolves the registered future with the supplied result and drops the entry")
		void complete_whenRequestRegistered_resolvesFutureAndRemovesEntry() throws Exception {
			// given
			final CompletableFuture<JsonNode> future = pending.create("req-1");

			// when
			final boolean completed = pending.complete("req-1", RESULT);

			// then
			assertThat(completed).isTrue();
			assertThat(future.get()).isEqualTo(RESULT);
			assertThat(pending.size()).isZero();
		}

		@Test
		@Story("Miss")
		@Severity(SeverityLevel.NORMAL)
		@Description("complete() returns false when no future is registered for the requestId")
		void complete_whenRequestUnknown_returnsFalse() {
			// when
			final boolean completed = pending.complete("missing", RESULT);

			// then
			assertThat(completed).isFalse();
		}

	}

	@Nested
	@DisplayName("completeExceptionally()")
	class CompleteExceptionally {

		@Test
		@Story("Error completion")
		@Severity(SeverityLevel.CRITICAL)
		@Description("completeExceptionally() fails the registered future and drops the entry")
		void completeExceptionally_whenRequestRegistered_failsFutureAndRemovesEntry() {
			// given
			final CompletableFuture<JsonNode> future = pending.create("req-1");
			final RuntimeException error = new RuntimeException("boom");

			// when
			final boolean completed = pending.completeExceptionally("req-1", error);

			// then
			assertThat(completed).isTrue();
			assertThat(future).isCompletedExceptionally();
			assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class).hasCause(error);
			assertThat(pending.size()).isZero();
		}

		@Test
		@Story("Miss")
		@Severity(SeverityLevel.NORMAL)
		@Description("completeExceptionally() returns false when the requestId is unknown")
		void completeExceptionally_whenRequestUnknown_returnsFalse() {
			// when
			final boolean completed = pending.completeExceptionally("missing", new RuntimeException("boom"));

			// then
			assertThat(completed).isFalse();
		}

	}

	@Nested
	@DisplayName("clear()")
	class Clear {

		@Test
		@Story("Session teardown")
		@Severity(SeverityLevel.NORMAL)
		@Description("clear() fails every outstanding future and empties the registry")
		void clear_withPendingRequests_failsAllFuturesAndEmptiesRegistry() {
			// given
			final CompletableFuture<JsonNode> first = pending.create("req-1");
			final CompletableFuture<JsonNode> second = pending.create("req-2");

			// when
			pending.clear();

			// then
			assertThat(first).isCompletedExceptionally();
			assertThat(second).isCompletedExceptionally();
			assertThatThrownBy(first::get).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(IllegalStateException.class);
			assertThat(pending.size()).isZero();
		}

	}

	@Nested
	@DisplayName("size()")
	class Size {

		@Test
		@Story("Counting")
		@Severity(SeverityLevel.MINOR)
		@Description("size() reflects the number of outstanding futures")
		void size_afterMultipleCreates_countsOutstandingFutures() {
			// given
			pending.create("req-1");
			pending.create("req-2");

			// when
			final int size = pending.size();

			// then
			assertThat(size).isEqualTo(2);
		}

	}

}
