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

package io.inspector.mcp.webmvc.auth;

/**
 * Request-attribute names shared between the inspector auth filter and the auth-profile /
 * proxy controllers.
 *
 * @author Artem Simeshin
 */
public final class InspectorSessionAttributes {

	/**
	 * Request attribute holding the validated session-owner id (set by
	 * {@code InspectorAuthFilter} after the {@code X-MCP-Inspector-Auth} guard passes and
	 * the signed session cookie is resolved).
	 */
	public static final String OWNER_ID = "io.inspector.mcp.inspector.ownerId";

	private InspectorSessionAttributes() {
		// constants holder
	}

}
