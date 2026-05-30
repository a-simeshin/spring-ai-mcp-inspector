/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.transport;

/**
 * Detected MCP server transport flavor.
 */
public enum TransportType {

	/**
	 * Legacy HTTP+SSE transport (separate {@code /sse} and {@code /message} endpoints).
	 */
	SSE,

	/** Modern streamable-HTTP transport (single endpoint with session ids). */
	STREAMABLE,

	/** Stateless HTTP transport (no session, no sampling/elicitation). */
	STATELESS,

	/** Pure stdio MCP server without any HTTP stack at all. */
	STDIO_NO_HTTP,

	/** Could not determine the transport from the environment. */
	UNKNOWN

}
