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
 * Thrown when a token was acquired or exchanged against a stale profile generation,
 * meaning the profile was deleted or mutated while the OAuth exchange was in flight.
 * <p>
 * Callers must not store the tokens and should answer {@code 404 token_exchange_failed}:
 * the profile is gone; the tokens (even if the exchange succeeded) must not be retained.
 *
 * @author Artem Simeshin
 */
public class StaleProfileGenerationException extends RuntimeException {

	public StaleProfileGenerationException(final String profileId, final long expectedGeneration,
			final long currentGeneration) {
		super("profile " + profileId + " generation changed: expected " + expectedGeneration + ", current "
				+ currentGeneration + " : tokens discarded");
	}

}
