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
 * Status-carrying exception for upstream failures — a non-2xx response from an upstream
 * HTTP endpoint (the target MCP server, or the OAuth2 token endpoint during a
 * client-credentials exchange).
 *
 * <p>
 * Call sites map it to the structured {@link ProxyErrorDto} contract (e.g. the
 * {@code 502 / token_exchange_failed} DTO for a failed initial OAuth2 client-credentials
 * acquire).
 *
 * @author Artem Simeshin
 */
public class ProxyUpstreamException extends RuntimeException {

	/** The upstream HTTP status that caused the failure. */
	private final int status;

	/**
	 * Creates a status-carrying upstream failure.
	 * @param status the upstream HTTP status
	 * @param message human-readable failure description
	 */
	public ProxyUpstreamException(final int status, final String message) {
		super(message);
		this.status = status;
	}

	/**
	 * Creates a status-carrying upstream failure with a cause.
	 * @param status the upstream HTTP status
	 * @param message human-readable failure description
	 * @param cause the underlying failure
	 */
	public ProxyUpstreamException(final int status, final String message, final Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	/**
	 * Returns the upstream HTTP status that caused the failure.
	 * @return the status code
	 */
	public int getStatus() {
		return this.status;
	}

}
