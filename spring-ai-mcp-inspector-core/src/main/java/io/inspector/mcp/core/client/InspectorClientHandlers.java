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

package io.inspector.mcp.core.client;

import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Optional set of client-side callbacks registered on a loopback {@code McpSyncClient}.
 *
 * <p>
 * The inspector uses these to bridge server-to-client requests (sampling / elicitation)
 * into the UI via the SSE channel: the registered {@link Function}s are invoked on a
 * transport thread, push a notification to the UI, then block until the UI replies via
 * {@code POST /api/jsonrpc/respond}.
 *
 * @param sampling handler for {@code sampling/createMessage} requests; may be
 * {@code null}
 * @param elicitation handler for {@code elicitation/create} requests; may be {@code null}
 * @author Artem Simeshin
 */
public record InspectorClientHandlers(Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> sampling,
		Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitation) {

	/** Returns a handlers tuple with no callbacks registered. */
	public static InspectorClientHandlers none() {
		return new InspectorClientHandlers(null, null);
	}
}
