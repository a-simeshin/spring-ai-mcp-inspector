/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.tools;

import java.time.Duration;
import java.time.Instant;

import io.inspector.mcp.demo.tools.TaskRegistry.Entry;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskRegistry} state machine transitions and TTL eviction.
 */
class TaskRegistryTests {

	private TaskRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new TaskRegistry();
	}

	@Test
	void createTaskReturnsEntryInWorkingState() {
		// when
		final Entry entry = registry.createTask(Duration.ofMinutes(2), 2, "demo://task/status");

		// then
		Assertions.assertThat(entry).isNotNull();
		Assertions.assertThat(entry.status.get()).isEqualTo("working");
		Assertions.assertThat(entry.createdAt).isNotNull();
		Assertions.assertThat(entry.ttl).isEqualTo(Duration.ofMinutes(2));
		Assertions.assertThat(entry.pollIntervalSeconds).isEqualTo(2);
		Assertions.assertThat(entry.keepAliveUrl).isEqualTo("demo://task/status");
		// Result and error must be null initially
		Assertions.assertThat(entry.result.get()).isNull();
		Assertions.assertThat(entry.error.get()).isNull();
	}

	@Test
	void getTaskReturnsNullForUnknownTask() {
		Assertions.assertThat(registry.getTaskEntry("non-existent")).isNull();
	}

	@Test
	void getTaskReturnsRegisteredEntry() {
		// given
		final Entry created = registry.createTask(Duration.ofMinutes(1), 3, "demo://task/status");

		// when
		final Entry found = registry.getTaskEntry(created.taskId);

		// then
		Assertions.assertThat(found).isSameAs(created);
	}

	@Test
	void completeTaskTransitionsToSuccessWithResult() {
		// given
		final Entry entry = registry.createTask(Duration.ofMinutes(1), 2, "demo://task/status");
		final Object result = "report content";

		// when
		final boolean transitioned = registry.completeTask(entry.taskId, result);

		// then
		Assertions.assertThat(transitioned).isTrue();
		Assertions.assertThat(entry.status.get()).isEqualTo("success");
		Assertions.assertThat(entry.result.get()).isEqualTo("report content");
		Assertions.assertThat(entry.error.get()).isNull();
	}

	@Test
	void failTaskTransitionsToFailedWithError() {
		// given
		final Entry entry = registry.createTask(Duration.ofMinutes(1), 2, "demo://task/status");

		// when
		final boolean transitioned = registry.failTask(entry.taskId, "something went wrong");

		// then
		Assertions.assertThat(transitioned).isTrue();
		Assertions.assertThat(entry.status.get()).isEqualTo("failed");
		Assertions.assertThat(entry.error.get()).isEqualTo("something went wrong");
		Assertions.assertThat(entry.result.get()).isNull();
	}

	@Test
	void cancelTaskTransitionsToCancelled() {
		// given
		final Entry entry = registry.createTask(Duration.ofMinutes(1), 2, "demo://task/status");

		// when
		final boolean transitioned = registry.cancelTaskLegacy(entry.taskId);

		// then
		Assertions.assertThat(transitioned).isTrue();
		Assertions.assertThat(entry.status.get()).isEqualTo("cancelled");
	}

	@Test
	void completeTaskOnNonExistingEntryReturnsFalse() {
		Assertions.assertThat(registry.completeTask("no-such-task", "result")).isFalse();
	}

	@Test
	void failTaskOnNonExistingEntryReturnsFalse() {
		Assertions.assertThat(registry.failTask("no-such-task", "error")).isFalse();
	}

	@Test
	void cancelTaskOnNonExistingEntryReturnsFalse() {
		Assertions.assertThat(registry.cancelTaskLegacy("no-such-task")).isFalse();
	}

	@Test
	void transitionFromTerminalStateIsRejected() {
		// given: task already completed
		final Entry entry = registry.createTask(Duration.ofMinutes(1), 2, "demo://task/status");
		registry.completeTask(entry.taskId, "done");

		// when: attempting to transition from 'success'
		final boolean reComplete = registry.completeTask(entry.taskId, "again");
		final boolean reFail = registry.failTask(entry.taskId, "again");
		final boolean reCancel = registry.cancelTaskLegacy(entry.taskId);

		// then: all rejected (CAS fails because status is no longer 'working')
		Assertions.assertThat(reComplete).isFalse();
		Assertions.assertThat(reFail).isFalse();
		Assertions.assertThat(reCancel).isFalse();
		Assertions.assertThat(entry.status.get()).isEqualTo("success");
	}

	@Test
	void keepAliveReturnsFalseForUnknownTask() {
		Assertions.assertThat(registry.keepAlive("no-such-task")).isFalse();
	}

	@Test
	void keepAliveRefreshesExpiry() throws Exception {
		// given
		final Entry entry = registry.createTask(Duration.ofMinutes(1), 2, "demo://task/status");
		final Instant before = entry.lastKeepAlive.get();

		// when: sleep briefly so the timestamp differs
		Thread.sleep(5);
		final boolean kept = registry.keepAlive(entry.taskId);

		// then
		Assertions.assertThat(kept).isTrue();
		Assertions.assertThat(entry.lastKeepAlive.get()).isAfter(before);
	}

	@Test
	void evictExpiredTasksRemovesTerminalEntriesPastTtl() {
		// given: several tasks in different states
		final Duration shortTtl = Duration.ofMillis(50);
		final Entry workingEntry = registry.createTask(shortTtl, 2, "demo://task/status");
		final Entry successEntry = registry.createTask(shortTtl, 2, "demo://task/status");
		final Entry failedEntry = registry.createTask(shortTtl, 2, "demo://task/status");

		registry.completeTask(successEntry.taskId, "done");
		registry.failTask(failedEntry.taskId, "failed");

		// when: advance time past the TTL by sleeping
		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		registry.evictExpiredTasks();

		// then: terminal tasks are evicted, working task remains
		Assertions.assertThat(registry.getTaskEntry(workingEntry.taskId))
			.as("working task must survive eviction")
			.isNotNull();
		Assertions.assertThat(registry.getTaskEntry(successEntry.taskId))
			.as("terminal success task past TTL must be evicted")
			.isNull();
		Assertions.assertThat(registry.getTaskEntry(failedEntry.taskId))
			.as("terminal failed task past TTL must be evicted")
			.isNull();
	}

	@Test
	void evictDoesNotRemoveNonTerminalEntries() {
		// given: working task with short TTL
		final Entry entry = registry.createTask(Duration.ofMillis(50), 2, "demo://task/status");

		try {
			Thread.sleep(100);
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		registry.evictExpiredTasks();

		// then: working task is NOT evicted even though TTL elapsed
		Assertions.assertThat(registry.getTaskEntry(entry.taskId)).as("working task must not be evicted").isNotNull();
		Assertions.assertThat(entry.status.get()).isEqualTo("working");
	}

	@Test
	void keepAlivePreventsEvictionOfTerminalTasks() throws Exception {
		// given: success task with moderate TTL
		final Duration ttl = Duration.ofMillis(500);
		final Entry entry = registry.createTask(ttl, 2, "demo://task/status");
		registry.completeTask(entry.taskId, "result");

		// when: keep-alive refreshes expiry, then wait less than TTL
		Thread.sleep(50);
		registry.keepAlive(entry.taskId);
		// stay inside the 500ms window from the keepAlive call
		Thread.sleep(200);
		registry.evictExpiredTasks();

		// then: still present because keepAlive refreshed the deadline
		Assertions.assertThat(registry.getTaskEntry(entry.taskId))
			.as("keep-alive must prevent eviction of terminal task")
			.isNotNull();
	}

}