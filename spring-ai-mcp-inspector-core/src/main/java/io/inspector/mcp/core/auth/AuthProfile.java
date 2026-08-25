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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed root of the named authentication profile model.
 *
 * <p>
 * Four kinds exist ({@link AuthProfileType}): OAuth2, bearer, API-key and custom headers.
 * Every profile carries a required {@code name} that is unique within the owning browser
 * session's {@link AuthProfileStore}. The {@code type} property is the JSON discriminator
 * — the concrete subtype is chosen from it during deserialization.
 *
 * <p>
 * Secret values (bearer tokens, API keys, client secrets, header values) live in the
 * profile records but are NEVER serialized back to the browser: summaries
 * ({@link AuthProfileSummary}) omit them.
 *
 * @author Artem Simeshin
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = OAuth2Profile.class, name = "OAUTH2"),
		@JsonSubTypes.Type(value = BearerProfile.class, name = "BEARER"),
		@JsonSubTypes.Type(value = ApiKeyProfile.class, name = "API_KEY"),
		@JsonSubTypes.Type(value = CustomHeadersProfile.class, name = "CUSTOM_HEADERS") })
public sealed interface AuthProfile permits OAuth2Profile, BearerProfile, ApiKeyProfile, CustomHeadersProfile {

	/**
	 * Returns the profile's display name. Required, non-blank and unique within the
	 * owning browser session.
	 * @return the profile name
	 */
	String name();

	/**
	 * Returns the profile kind discriminator ({@link AuthProfileType}).
	 * @return the profile type
	 */
	AuthProfileType type();

}
