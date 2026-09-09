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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.inspector.mcp.core.task.TaskHandle;
import io.inspector.mcp.core.task.TaskNotCancelableException;
import io.inspector.mcp.core.task.TaskNotFoundException;
import io.inspector.mcp.core.task.TaskService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of long-running task handles, backed by a {@link ConcurrentHashMap}.
 *
 * <p>
 * Owns the lifecycle state machine:
 * <ul>
 * <li>{@code working} - the background computation is in progress</li>
 * <li>{@code success} - the computation completed successfully; result is available</li>
 * <li>{@code failed} - the computation terminated with an error</li>
 * <li>{@code cancelled} - the task was cancelled before completion</li>
 * </ul>
 *
 * <p>
 * Terminal-state tasks ({@code success}, {@code failed}, {@code cancelled}) are evicted
 * by a periodic sweeper after their {@link #ttl()} elapses. {@link #keepAlive(String)}
 * refreshes the expiry of any existing task.
 */
@Component
public class TaskRegistry implements TaskService {

	private static final Logger LOG = LoggerFactory.getLogger(TaskRegistry.class);

	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

	private final ConcurrentHashMap<String, Entry> tasks = new ConcurrentHashMap<>();

	/**
	 * Create a new task in the {@code working} state.
	 * @param ttl how long after terminal state the task record should be kept before
	 * eviction
	 * @param pollIntervalSeconds suggested poll interval in seconds for clients
	 * @param keepAliveUrl opaque URL the client uses to refresh the task lease
	 * @return the created entry
	 */
	public Entry createTask(final Duration ttl, final int pollIntervalSeconds, final String keepAliveUrl) {
		final String taskId = UUID.randomUUID().toString();
		final Instant now = Instant.now();
		final Entry entry = new Entry(taskId, now, ttl, pollIntervalSeconds, keepAliveUrl);
		tasks.put(taskId, entry);
		return entry;
	}

	/**
	 * Return the entry for the given {@code taskId}, or {@code null} if no such task
	 * exists (including evicted tasks). Alias for {@link #getTask(String)} kept for
	 * backward compatibility with demo tool usage.
	 */
	public Entry getTaskEntry(final String taskId) {
		return tasks.get(taskId);
	}

	/**
	 * Transition the given task from {@code working} to {@code cancelled}. Alias for
	 * {@link #cancelTask(String)} kept for backward compatibility with demo tool usage.
	 * @param taskId the task to cancel
	 * @return {@code true} if the transition was applied, {@code false} if the task was
	 * not in the {@code working} state or did not exist
	 */
	public boolean cancelTaskLegacy(final String taskId) {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			return false;
		}
		return entry.status.compareAndSet("working", "cancelled");
	}

	/**
	 * Transition the given task from {@code working} to {@code success}.
	 * @param taskId the task to complete
	 * @param result the result payload to attach
	 * @return {@code true} if the transition was applied, {@code false} if the task was
	 * not in the {@code working} state or did not exist
	 */
	public boolean completeTask(final String taskId, final Object result) {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			return false;
		}
		if (entry.status.compareAndSet("working", "success")) {
			entry.result.set(result);
			return true;
		}
		return false;
	}

	/**
	 * Transition the given task from {@code working} to {@code failed}.
	 * @param taskId the task to fail
	 * @param error the error message to attach
	 * @return {@code true} if the transition was applied, {@code false} if the task was
	 * not in the {@code working} state or did not exist
	 */
	public boolean failTask(final String taskId, final String error) {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			return false;
		}
		if (entry.status.compareAndSet("working", "failed")) {
			entry.error.set(error);
			return true;
		}
		return false;
	}

	/**
	 * Refresh the task's keep-alive: resets the expiry clock so the sweeper keeps the
	 * record for another TTL interval from now.
	 * @param taskId the task to refresh
	 * @return {@code true} if the task exists (even if terminal), {@code false} if the
	 * task was never registered or has been evicted
	 */
	public boolean keepAlive(final String taskId) {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			return false;
		}
		entry.lastKeepAlive.set(Instant.now());
		return true;
	}

	/**
	 * Periodic sweeper that evicts terminal-state tasks whose TTL has elapsed since their
	 * last keep-alive (or since they reached terminal state, whichever is later).
	 *
	 * <p>
	 * Runs every 30 seconds.
	 */
	@Scheduled(fixedRateString = "${demo.task-sweep-millis:30000}", initialDelay = 5000)
	public void evictExpiredTasks() {
		final Instant deadline = Instant.now();
		tasks.values().removeIf(entry -> {
			if (isTerminal(entry.status.get())) {
				final Instant ref = entry.lastKeepAlive.get();
				return ref != null && ref.plus(entry.ttl).isBefore(deadline);
			}
			return false;
		});
	}

	// ---------------------------------------------------------------------
	// TaskService implementation
	// ---------------------------------------------------------------------

	@Override
	public TaskHandle getTask(final String taskId) throws TaskNotFoundException {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			throw new TaskNotFoundException(taskId);
		}
		return toHandle(entry);
	}

	@Override
	public TaskHandle cancelTask(final String taskId) throws TaskNotFoundException, TaskNotCancelableException {
		final Entry entry = tasks.get(taskId);
		if (entry == null) {
			throw new TaskNotFoundException(taskId);
		}
		if (!entry.status.compareAndSet("working", "cancelled")) {
			final String current = entry.status.get();
			if (isTerminal(current)) {
				throw new TaskNotCancelableException(taskId, current);
			}
			// Should not happen: input_required is not in use yet.
			throw new TaskNotCancelableException(taskId, current);
		}
		return toHandle(entry);
	}

	private static TaskHandle toHandle(final Entry entry) {
		final String lastRef = entry.lastKeepAlive.get() != null ? ISO_FORMATTER.format(entry.lastKeepAlive.get())
				: ISO_FORMATTER.format(entry.createdAt);
		return new TaskHandle(entry.taskId, entry.status.get(), null, ISO_FORMATTER.format(entry.createdAt), lastRef,
				entry.ttl.toMillis(), entry.pollIntervalSeconds * 1000L);
	}

	private static boolean isTerminal(final String status) {
		return "success".equals(status) || "failed".equals(status) || "cancelled".equals(status);
	}

	/**
	 * A single tracked task and its lifecycle metadata.
	 */
	public static class Entry {

		final AtomicReference<String> status;

		final Instant createdAt;

		final AtomicReference<Instant> lastKeepAlive;

		final Duration ttl;

		final int pollIntervalSeconds;

		final String keepAliveUrl;

		final AtomicReference<Object> result;

		final AtomicReference<String> error;

		final String taskId;

		Entry(final String taskId, final Instant createdAt, final Duration ttl, final int pollIntervalSeconds,
				final String keepAliveUrl) {
			this.taskId = taskId;
			this.status = new AtomicReference<>("working");
			this.createdAt = createdAt;
			this.lastKeepAlive = new AtomicReference<>(createdAt);
			this.ttl = ttl;
			this.pollIntervalSeconds = pollIntervalSeconds;
			this.keepAliveUrl = keepAliveUrl;
			this.result = new AtomicReference<>(null);
			this.error = new AtomicReference<>(null);
		}

	}

}