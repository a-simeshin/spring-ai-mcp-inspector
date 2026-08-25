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
 * Hook invoked by {@link AuthProfileStore} whenever a profile leaves the store (delete /
 * clear / clearBySession / removeExpired / update) so the owning OAuth2 machinery can
 * drop the profile's cached tokens and stored secrets.
 *
 * @author Artem Simeshin
 */
public interface TokenEvictor {

	/**
	 * Drops every credential and cached token belonging to {@code profileId}. After
	 * eviction a subsequent token lookup for the profile must fail closed (never fall
	 * back to a stale secret).
	 * @param profileId the profile whose credentials/tokens are removed
	 */
	void evict(String profileId);

}
