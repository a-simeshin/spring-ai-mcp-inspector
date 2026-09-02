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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

import io.modelcontextprotocol.spec.McpTransportException;

/**
 * Classified outcome of a failed connection attempt to an upstream MCP server.
 *
 * <p>
 * The proxy controllers previously collapsed every connect failure into a flat message
 * and lost the cause. This record keeps the two facts the browser actually needs - a
 * machine-readable {@link Reason} and a human-readable message - and lets the HTTP layer
 * map the reason onto a status code (504 for {@link Reason#TIMEOUT}, 502 otherwise) and a
 * structured {@code MCP_CONNECT_FAILED} JSON payload.
 *
 * @author Artem Simeshin
 * @param reason the machine-readable failure category (never {@code null})
 * @param message the human-readable failure description (never {@code null})
 */
public record ProxyConnectFailure(Reason reason, String message) {

	/**
	 * Machine-readable failure category. Serialized verbatim as the {@code reason} field
	 * of the {@code MCP_CONNECT_FAILED} error payload.
	 */
	public enum Reason {

		/** The upstream server accepted the connection but did not answer in time. */
		TIMEOUT("timeout"),

		/** The upstream server refused the connection (nothing listens on the port). */
		CONNECTION_REFUSED("connection_refused"),

		/** The upstream host name could not be resolved. */
		DNS("dns"),

		/** The upstream server responded with 404 Not Found. */
		NOT_FOUND("not_found"),

		/** Any other failure that cannot be classified. */
		UNKNOWN("unknown");

		private final String wire;

		Reason(final String wire) {
			this.wire = wire;
		}

		/**
		 * The wire representation of this reason.
		 * @return the wire representation used in the HTTP error payload
		 */
		public String wire() {
			return this.wire;
		}

	}

	public static ProxyConnectFailure classify(final Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof UnknownHostException || current instanceof UnresolvedAddressException) {
				return new ProxyConnectFailure(Reason.DNS, "could not resolve the MCP server host name");
			}
			if (current instanceof ConnectException) {
				// The MCP SDK (StreamableHttpClientTransport) wraps DNS failures
				// into ConnectException before the proxy sees the cause chain.
				// The Java HTTP client surfaces unresolved hosts as
				// UnresolvedAddressException (NIO path), while classic socket
				// code surfaces them as UnknownHostException. Descend into the
				// ConnectException cause chain to check for either DNS marker;
				// only if none is found do we classify as connection refused.
				Throwable cause = current.getCause();
				while (cause != null) {
					if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
						return new ProxyConnectFailure(Reason.DNS, "could not resolve the MCP server host name");
					}
					cause = cause.getCause();
				}
				return new ProxyConnectFailure(Reason.CONNECTION_REFUSED, "connection to the MCP server was refused");
			}
			if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
					|| current instanceof TimeoutException) {
				return new ProxyConnectFailure(Reason.TIMEOUT, "connection to the MCP server timed out");
			}
			if (current instanceof McpTransportException && current.getMessage() != null
					&& current.getMessage().contains("Server Not Found")) {
				return new ProxyConnectFailure(Reason.NOT_FOUND, "server responded with 404: check the URL");
			}
			current = current.getCause();
		}
		return new ProxyConnectFailure(Reason.UNKNOWN, "failed to connect to the MCP server");
	}

}
