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
 * API-key authentication profile. The key is attached either as a request header or as a
 * query parameter, named {@code keyName}.
 *
 * @param name profile display name (required, unique per owner)
 * @param keyName the header / query parameter name carrying the key
 * @param keyValue the key value (never returned by summaries)
 * @param placement where the key is attached ({@code HEADER} or {@code QUERY})
 * @author Artem Simeshin
 */
public record ApiKeyProfile(String name, String keyName, String keyValue,
		ApiKeyPlacement placement) implements AuthProfile {

	@Override
	public AuthProfileType type() {
		return AuthProfileType.API_KEY;
	}

}
