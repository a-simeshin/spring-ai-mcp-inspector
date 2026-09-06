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
 * Where an API-key profile attaches its key to outbound requests.
 *
 * @author Artem Simeshin
 */
public enum ApiKeyPlacement {

	/** The key travels in a request header named {@code keyName}. */
	HEADER,

	/** The key travels as a query parameter named {@code keyName}. */
	QUERY,

}
