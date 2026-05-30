/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

/**
 * Default HTTP header that the inspector UI will pre-populate for outgoing MCP requests.
 * Carried as part of the {@link InspectorBootstrap} payload.
 *
 * @param name header name (never {@code null})
 * @param value header value (never {@code null})
 * @param enabled whether the header is enabled by default in the UI
 */
public record CustomHeader(String name, String value, boolean enabled) {

	/**
	 * Convenience factory that builds an enabled header.
	 * @param name header name
	 * @param value header value
	 * @return new {@link CustomHeader} with {@code enabled=true}
	 */
	public static CustomHeader of(String name, String value) {
		return new CustomHeader(name, value, true);
	}
}
