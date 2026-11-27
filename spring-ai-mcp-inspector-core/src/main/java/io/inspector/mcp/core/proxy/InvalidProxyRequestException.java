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
 * The proxy rejected the request before any network I/O: a restricted custom header name,
 * an illegal header value, an unsupported {@code transportType} or a missing stdio
 * command. This is the ONLY IllegalArgumentException subclass
 * {@link ProxyConnectFailure#classify(Throwable)} maps to
 * {@link ProxyConnectFailure.Reason#INVALID_REQUEST}: matching plain
 * {@link IllegalArgumentException} would also swallow
 * {@link java.nio.channels.UnresolvedAddressException} (which extends it and must stay a
 * DNS failure) and leak foreign messages such as {@code NumberFormatException}'s.
 *
 * <p>
 * The message must be built value-safe: it travels to the browser verbatim inside the
 * structured {@code MCP_CONNECT_FAILED} payload, so it may carry a header NAME and the
 * JDK's rejection reason but never a header value.
 *
 * @author Artem Simeshin
 */
public final class InvalidProxyRequestException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a value-safe rejection.
	 * @param message the browser-safe rejection reason (never {@code null})
	 */
	public InvalidProxyRequestException(final String message) {
		super(message);
	}

	/**
	 * Creates a value-safe rejection. The cause is usually {@code null} on purpose: the
	 * wrapped JDK exception may quote the rejected header value in its own message, so
	 * attaching it would leak the value through the cause chain.
	 * @param message the browser-safe rejection reason
	 * @param cause the JDK exception to wrap, or {@code null} to drop it value-safely
	 */
	public InvalidProxyRequestException(final String message, final Throwable cause) {
		super(message, cause);
	}

}
