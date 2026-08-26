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

/**
 * Signals that a proxy session could not be opened because connecting to the upstream MCP
 * server failed.
 *
 * <p>
 * Carries the classified {@link ProxyConnectFailure} so the HTTP layer can map it onto
 * the structured {@code MCP_CONNECT_FAILED} response without re-inspecting the cause. The
 * cause is attached for server-side logging only — it is never serialized into the HTTP
 * response.
 *
 * @author Artem Simeshin
 */
public final class ProxyConnectFailureException extends RuntimeException {

	private final ProxyConnectFailure failure;

	public ProxyConnectFailureException(final ProxyConnectFailure failure, final Throwable cause) {
		super(failure.message(), cause);
		this.failure = failure;
	}

	/**
	 * The classified connect failure carried by this exception.
	 * @return the classified connect failure (never {@code null})
	 */
	public ProxyConnectFailure failure() {
		return this.failure;
	}

}
