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
import java.util.LinkedHashMap;
import java.util.Map;

import io.inspector.mcp.demo.tools.TaskRegistry.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Demo MCP tools that emulate the SEP-1686 long-running task protocol.
 *
 * <p>
 * A tool call with a task parameter returns immediately with a handle ({@code taskId},
 * {@code status}, {@code keepAlive}, {@code ttl}, {@code pollInterval}). The actual
 * computation runs in a background thread pool; the client polls
 * {@link #getTaskStatus(String)} to observe the transition from {@code working} to
 * {@code success} or {@code failed}.
 *
 * <p>
 * Hint declarations: these tools are read-only and non-destructive.
 */
@Component
public class DemoTaskEmulatingToolsProvider {

	private static final Logger LOG = LoggerFactory.getLogger(DemoTaskEmulatingToolsProvider.class);

	/** Cap for the {@code durationSeconds} parameter. */
	private static final int MAX_DURATION_SECONDS = 60;

	/** Default TTL for completed tasks before eviction. */
	private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

	/** Default poll interval in seconds. */
	private static final int DEFAULT_POLL_INTERVAL = 2;

	private final TaskRegistry taskRegistry;

	private final ThreadPoolTaskExecutor taskExecutor;

	public DemoTaskEmulatingToolsProvider(final TaskRegistry taskRegistry, final ThreadPoolTaskExecutor taskExecutor) {
		this.taskRegistry = taskRegistry;
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Start a long-running report generation in the background.
	 * @param durationSeconds how long the simulated work should take (1..
	 * {@value #MAX_DURATION_SECONDS})
	 * @param shouldFail if {@code true}, the background work fails instead of succeeding
	 * @return a task handle map with {@code taskId}, {@code status}, {@code keepAlive},
	 * {@code ttl}, and {@code pollInterval}
	 */
	@McpTool(name = "generateReport",
			description = "Start a long-running report generation. Returns immediately with a task handle the client polls for completion.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public Map<String, Object> generateReport(
			@McpToolParam(description = "simulated duration in seconds (1..60)",
					required = true) Integer durationSeconds,
			@McpToolParam(description = "if true the task fails instead of succeeding",
					required = false) Boolean shouldFail) {
		final int duration = durationSeconds == null ? 10
				: Math.max(1, Math.min(durationSeconds, MAX_DURATION_SECONDS));
		final boolean fail = shouldFail != null && shouldFail;
		final String keepAliveUrl = "demo://task/status";
		final Entry entry = taskRegistry.createTask(DEFAULT_TTL, DEFAULT_POLL_INTERVAL, keepAliveUrl);

		taskExecutor.submit(() -> {
			try {
				Thread.sleep(Duration.ofSeconds(duration).toMillis());
				if (fail) {
					taskRegistry.failTask(entry.taskId, "Simulated failure after " + duration + "s");
					LOG.debug("Task {} failed as configured", entry.taskId);
				}
				else {
					final Map<String, Object> report = new LinkedHashMap<>();
					report.put("summary", "Report generated successfully");
					report.put("durationSeconds", duration);
					report.put("timestamp", System.currentTimeMillis());
					taskRegistry.completeTask(entry.taskId, report);
					LOG.debug("Task {} completed after {}s", entry.taskId, duration);
				}
			}
			catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				LOG.debug("Task {} interrupted", entry.taskId);
			}
		});

		return toHandleMap(entry);
	}

	/**
	 * Poll the status of a previously started task.
	 * @param taskId the task identifier
	 * @return a map describing the current task state, or an error map if the task is
	 * unknown
	 */
	@McpTool(name = "getTaskStatus",
			description = "Poll the status of a long-running task initiated with generateReport",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public Map<String, Object> getTaskStatus(
			@McpToolParam(description = "task id returned by generateReport", required = true) String taskId) {
		final Entry entry = taskRegistry.getTaskEntry(taskId);
		if (entry == null) {
			return Map.of("error", "Task not found (may have been evicted)");
		}
		return toStatusMap(entry);
	}

	/**
	 * Cancel a task that is still in the {@code working} state.
	 * @param taskId the task to cancel
	 * @return a map indicating whether the cancellation was applied
	 */
	@McpTool(name = "cancelTask", description = "Cancel a running long-running task",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public Map<String, Object> cancelTask(
			@McpToolParam(description = "task id to cancel", required = true) String taskId) {
		final boolean cancelled = taskRegistry.cancelTaskLegacy(taskId);
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("taskId", taskId);
		result.put("cancelled", cancelled);
		return result;
	}

	/**
	 * Refresh the keep-alive on a task so the sweeper holds it longer.
	 * @param taskId the task to refresh
	 * @return a map confirming the action
	 */
	@McpTool(name = "keepAliveTask", description = "Refresh a task's keep-alive to prevent premature eviction",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public Map<String, Object> keepAliveTask(
			@McpToolParam(description = "task id to refresh", required = true) String taskId) {
		final boolean ok = taskRegistry.keepAlive(taskId);
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("taskId", taskId);
		result.put("kept", ok);
		return result;
	}

	private static Map<String, Object> toHandleMap(final Entry entry) {
		final Map<String, Object> map = new LinkedHashMap<>();
		map.put("taskId", entry.taskId);
		map.put("status", entry.status.get());
		map.put("keepAlive", entry.keepAliveUrl);
		map.put("ttl", (int) entry.ttl.toSeconds());
		map.put("pollInterval", entry.pollIntervalSeconds);
		return map;
	}

	private static Map<String, Object> toStatusMap(final Entry entry) {
		final Map<String, Object> map = new LinkedHashMap<>();
		map.put("taskId", entry.taskId);
		map.put("status", entry.status.get());
		map.put("keepAlive", entry.keepAliveUrl);
		map.put("pollInterval", entry.pollIntervalSeconds);
		final Object result = entry.result.get();
		if (result != null) {
			map.put("result", result);
		}
		final String error = entry.error.get();
		if (error != null) {
			map.put("error", error);
		}
		return map;
	}

}