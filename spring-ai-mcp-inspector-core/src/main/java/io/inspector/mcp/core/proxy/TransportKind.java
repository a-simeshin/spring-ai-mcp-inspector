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

package io.inspector.mcp.core.proxy;

/**
 * Transport kind of a proxied MCP connection, gating the D3 error mapping.
 *
 * <p>
 * The {@code redirect} DTO and the {@code bad_request} / {@code 404} DTOs are SSE-only: a
 * streamable-HTTP connection maps those statuses to {@code null} (legacy 502/504) because
 * the streamable transport surfaces authorization failures as typed exceptions while
 * everything else stays opaque.
 *
 * @author Artem Simeshin
 */
public enum TransportKind {

	/** Server-sent-events transport ({@code GET /sse} + message POSTs). */
	SSE,

	/** Streamable-HTTP transport ({@code POST/GET/DELETE /mcp}). */
	STREAMABLE,

}
