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
 * A SEP-1686 task handle - the state of a long-running operation.
 *
 * @param taskId unique identifier for this task
 * @param status current state ({@code working}, {@code completed}, {@code failed},
 * {@code cancelled}, or {@code input_required})
 * @param statusMessage optional human-readable description of the current state
 * @param createdAt ISO-8601 timestamp when the task was created
 * @param lastUpdatedAt ISO-8601 timestamp when the task status was last updated
 * @param ttl time in milliseconds from creation before the task may be deleted
 * @param pollInterval suggested time in milliseconds between status checks
 * @author Artem Simeshin
 */
public record TaskHandle(String taskId, String status, String statusMessage, String createdAt, String lastUpdatedAt,
		long ttl, long pollInterval) {

}
