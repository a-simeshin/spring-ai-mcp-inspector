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

/**
 * Thrown when a cancellation request cannot be accepted because the task is already in a
 * terminal state ({@code completed}, {@code failed}, or {@code cancelled}).
 *
 * @author Artem Simeshin
 */
public class TaskNotCancelableException extends Exception {

	public TaskNotCancelableException(final String taskId, final String currentStatus) {
		super("Cannot cancel task " + taskId + ": already in terminal status '" + currentStatus + "'");
	}

}
