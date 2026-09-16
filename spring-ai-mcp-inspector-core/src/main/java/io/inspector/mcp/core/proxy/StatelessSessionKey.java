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

import java.util.Objects;

/**
 * Composite key that binds proxy state for stateless MCP servers (2026-07-28).
 *
 * <p>
 * Under the stateless protocol there is no {@code Mcp-Session-Id} header: the pair
 * {@code (serverUrl, authProfileFingerprint)} replaces it as the identity under which the
 * proxy registers sessions, caches tokens and routes frames. The fingerprint is an opaque
 * hash of the authentication profile (e.g. the Authorization header value plus any
 * custom-auth headers), so two profiles against the same URL never share a session or a
 * cached token.
 *
 * @param serverUrl the resolved target server URL
 * @param authProfileFingerprint opaque fingerprint of the authentication profile; never
 * {@code null} (use {@link AuthProfileHasher#ANONYMOUS} when no auth is configured)
 * @author Artem Simeshin
 */
public record StatelessSessionKey(String serverUrl, String authProfileFingerprint) {

	/**
	 * Compact constructor: defends against {@code null} components.
	 */
	public StatelessSessionKey {
		Objects.requireNonNull(serverUrl, "serverUrl");
		Objects.requireNonNull(authProfileFingerprint, "authProfileFingerprint");
	}

}
