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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.Assert;

/**
 * Payload of {@code POST /mcp-inspector/api/auth-profile/{profileId}/exchange} — phase 3
 * of the browser authorization-code flow (see {@link OAuth2AuthCodeTokenExchanger}).
 *
 * @param code the authorization code returned by the IdP callback
 * @param codeVerifier the PKCE verifier that produced the profile's {@code codeChallenge}
 * @param state the server-issued one-time state returned at PENDING profile creation
 * @author Artem Simeshin
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthProfileExchangeRequest(String code, String codeVerifier, String state) {

	/** Validates the request fields; throws on blanks (mapped to {@code 400}). */
	public void validate() {
		Assert.hasText(this.code, "code must not be blank");
		Assert.hasText(this.codeVerifier, "codeVerifier must not be blank");
		Assert.hasText(this.state, "state must not be blank");
	}

}
