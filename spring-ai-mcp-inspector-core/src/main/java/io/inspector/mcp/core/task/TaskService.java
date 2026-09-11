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

package io.inspector.mcp.core.task;

import java.util.List;

/**
 * Service interface for the SEP-1686 task protocol methods consumed by the proxy layer.
 *
 * <p>
 * The proxy controllers call into this interface when they intercept {@code tasks/get}
 * and {@code tasks/cancel} JSON-RPC requests, instead of relaying them to the upstream
 * MCP server.
 *
 * <p>
 * This interface lives in core so both the proxy (starter) and the demo app can see it
 * without a circular dependency. The demo-app's {@code TaskRegistry} provides the
 * implementation.
 *
 * @author Artem Simeshin
 * @see TaskHandle
 */
public interface TaskService {

	/**
	 * Return the current {@link TaskHandle} for the given {@code taskId}.
	 * @param taskId the task identifier
	 * @return the current task handle
	 * @throws TaskNotFoundException if the task does not exist or has been evicted
	 */
	TaskHandle getTask(String taskId) throws TaskNotFoundException;

	/**
	 * Request cancellation of a working task.
	 * @param taskId the task identifier
	 * @return the updated task handle, now in the {@code cancelled} state
	 * @throws TaskNotFoundException if the task does not exist or has been evicted
	 * @throws TaskNotCancelableException if the task is already in a terminal state
	 */
	TaskHandle cancelTask(String taskId) throws TaskNotFoundException, TaskNotCancelableException;

	/**
	 * Return all currently tracked tasks as a list of {@link TaskHandle}s.
	 * @return an unmodifiable list of task handles; never {@code null}
	 */
	List<TaskHandle> listTasks();

}
