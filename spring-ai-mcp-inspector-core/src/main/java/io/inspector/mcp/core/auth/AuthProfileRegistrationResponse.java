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

package io.inspector.mcp.core.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Response of {@code POST /mcp-inspector/api/auth-profile}. For an auth-code PENDING
 * creation it additionally carries the server-issued one-time {@code state} and the
 * {@code authorizationUrl} the browser must open.
 *
 * @param profileId server-issued opaque profile id
 * @param state one-time CSRF state for the PENDING auth-code flow (absent otherwise)
 * @param authorizationUrl IdP authorization URL for the PENDING auth-code flow (absent
 * otherwise)
 * @author Artem Simeshin
 */
@JsonInclude(Include.NON_NULL)
public record AuthProfileRegistrationResponse(String profileId, String state, String authorizationUrl) {

	/** Plain registration response (no auth-code fields). */
	public static AuthProfileRegistrationResponse of(final String profileId) {
		return new AuthProfileRegistrationResponse(profileId, null, null);
	}

	/** PENDING auth-code registration response with the server-issued state. */
	public static AuthProfileRegistrationResponse pending(final String profileId, final String state,
			final String authorizationUrl) {
		return new AuthProfileRegistrationResponse(profileId, state, authorizationUrl);
	}

}
