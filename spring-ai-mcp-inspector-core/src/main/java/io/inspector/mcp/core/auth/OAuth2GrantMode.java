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

/**
 * OAuth2 grant mode of an {@link OAuth2Profile}.
 *
 * <p>
 * {@code CLIENT_CREDENTIALS} profiles are executed by the backend
 * ({@link OAuth2ClientCredentialsTokenManager}); {@code AUTHORIZATION_CODE} profiles are
 * executed by the browser in a two-phase PKCE flow
 * ({@link OAuth2AuthCodeTokenExchanger}).
 *
 * @author Artem Simeshin
 */
public enum OAuth2GrantMode {

	/** Client-credentials grant, executed backend-side. */
	CLIENT_CREDENTIALS,

	/** Authorization-code grant with PKCE, executed browser-side. */
	AUTHORIZATION_CODE,

}
