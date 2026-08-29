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

/**
 * Timeline event model, service and traffic recorder for the MCP inspector.
 *
 * <p>
 * Provides the {@link io.inspector.mcp.core.timeline.TimelineEvent} model,
 * {@link io.inspector.mcp.core.timeline.TimelineService} interface and bounded in-memory
 * implementation, plus a {@link io.inspector.mcp.core.timeline.McpTrafficRecorder} that
 * intercepts JSON-RPC traffic at the proxy boundary.
 */
package io.inspector.mcp.core.timeline;
