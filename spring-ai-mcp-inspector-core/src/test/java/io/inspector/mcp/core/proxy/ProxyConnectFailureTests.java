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
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProxyConnectFailure}. */
@Epic("MCP Inspector Core")
@Feature("Proxy connect failure classification")
class ProxyConnectFailureTests {

	@Nested
	@DisplayName("classify()")
	class Classify {

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A ConnectionRefused is classified as connection_refused with a human-readable message")
		void classify_connectException_returnsConnectionRefused() {
			// given / when
			final ProxyConnectFailure failure = ProxyConnectFailure
				.classify(new ConnectException("Connection refused"));

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.CONNECTION_REFUSED);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An UnknownHostException is classified as dns")
		void classify_unknownHostException_returnsDns() {
			// given / when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(new UnknownHostException("no-such-host"));

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.DNS);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.NORMAL)
		@Description("SocketTimeoutException, HttpTimeoutException and TimeoutException are classified as timeout")
		void classify_timeoutExceptions_returnsTimeout() {
			// given / when / then
			assertThat(ProxyConnectFailure.classify(new SocketTimeoutException("read timed out")).reason())
				.isEqualTo(ProxyConnectFailure.Reason.TIMEOUT);
			assertThat(ProxyConnectFailure.classify(new HttpTimeoutException("request timed out")).reason())
				.isEqualTo(ProxyConnectFailure.Reason.TIMEOUT);
			assertThat(ProxyConnectFailure.classify(new TimeoutException("upstream did not answer")).reason())
				.isEqualTo(ProxyConnectFailure.Reason.TIMEOUT);
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.NORMAL)
		@Description("An unrecognized exception is classified as unknown without throwing")
		void classify_unrecognizedException_returnsUnknown() {
			// given / when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(new IllegalStateException("bad state"));

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.UNKNOWN);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.NORMAL)
		@Description("An McpTransportException with 'Server Not Found' is classified as not_found")
		void classify_mcpTransportExceptionWithServerNotFound_returnsNotFound() {
			// given - the SDK throws McpTransportException when the upstream
			// server responds with 404 (wrong URL path, e.g. /api instead of /mcp)
			final McpTransportException notFound = new McpTransportException(
					"Server Not Found. Status code:404 GET http://localhost:9999/api");

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(notFound);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.NOT_FOUND);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("A protocol-level reply from a live server (404 session not found) is unknown, not a transport failure - the pump must not tear the session down for it")
		void classify_sessionNotFoundFromLiveServer_returnsUnknown() {
			// given - the SDK surfaces the server's 404 answer to a session-id POST as
			// McpTransportSessionNotFoundException; the upstream is alive, only the
			// session is gone, so this must NOT classify as a connect failure
			final RuntimeException sessionNotFound = new RuntimeException(
					"Session not found for session ID: 8e50e393-f938-4b6a-8903-0e8ce7806bb2 not found on the server");

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(sessionNotFound);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.UNKNOWN);
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.NORMAL)
		@Description("A wrapped cause chain (Reactor-style) is unwrapped to the recognizable root cause")
		void classify_wrappedCause_returnsInnerReason() {
			// given - e.g. what Reactor's block() surfaces: an unchecked wrapper around
			// the network exception
			final RuntimeException wrapper = new RuntimeException("block() terminated",
					new ConnectException("Connection refused"));

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(wrapper);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.CONNECTION_REFUSED);
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An UnknownHostException wrapped in ConnectException (as the MCP SDK does) is classified as dns, not connection_refused")
		void classify_unknownHostWrappedInConnectException_returnsDns() {
			// given - the MCP SDK StreamableHttpClientTransport wraps
			// UnknownHostException into ConnectException before the proxy sees
			// the cause chain, so classify must descend into the ConnectException
			// cause to find the DNS root
			final ConnectException wrapped = new ConnectException("Connection refused");
			wrapped.initCause(new UnknownHostException("this-host-does-not-exist.invalid"));

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(wrapped);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.DNS);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An UnresolvedAddressException wrapped in ConnectException (as the Java HTTP client does for unresolved hosts) is classified as dns")
		void classify_unresolvedAddressWrappedInConnectException_returnsDns() {
			// given - the Java HTTP client (used by the MCP SDK) surfaces
			// unresolved host names as UnresolvedAddressException wrapped in
			// ConnectException. The proxy must descend into the cause to find
			// the DNS marker and classify as dns, not connection_refused.
			final ConnectException wrapped = new ConnectException();
			wrapped.initCause(new UnresolvedAddressException());

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(wrapped);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.DNS);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An UnknownHostException nested two levels deep under ConnectException under RuntimeException is classified as dns")
		void classify_unknownHostNestedDeepInCauseChain_returnsDns() {
			// given - Reactor wraps the SDK's ConnectException in a RuntimeException;
			// the SDK itself wrapped UnknownHostException in the ConnectException.
			// Chain: RuntimeException -> ConnectException -> UnknownHostException
			final ConnectException connectEx = new ConnectException("Connection refused");
			connectEx.initCause(new UnknownHostException("no-such-host.invalid"));
			final RuntimeException wrapper = new RuntimeException("block() terminated", connectEx);

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(wrapper);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.DNS);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An UnresolvedAddressException nested two levels deep under ConnectException under RuntimeException is classified as dns")
		void classify_unresolvedAddressNestedDeepInCauseChain_returnsDns() {
			// given - Reactor wraps the SDK's ConnectException in a
			// CompletionException/RuntimeException; the Java HTTP client
			// wrapped UnresolvedAddressException in the ConnectException.
			// Chain: RuntimeException -> ConnectException -> UnresolvedAddressException
			final ConnectException connectEx = new ConnectException();
			connectEx.initCause(new UnresolvedAddressException());
			final RuntimeException wrapper = new RuntimeException("block() terminated", connectEx);

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(wrapper);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.DNS);
			assertThat(failure.message()).isNotBlank();
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.NORMAL)
		@Description("A bare ConnectException without an UnknownHostException cause stays connection_refused")
		void classify_connectExceptionWithoutDnsCause_remainsConnectionRefused() {
			// given - a plain ConnectException with an unrelated cause must not
			// be misclassified as DNS
			final ConnectException connectEx = new ConnectException("Connection refused");
			connectEx.initCause(new IllegalStateException("socket closed"));

			// when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(connectEx);

			// then
			assertThat(failure.reason()).isEqualTo(ProxyConnectFailure.Reason.CONNECTION_REFUSED);
		}

		@Test
		@Story("Classification")
		@Severity(SeverityLevel.MINOR)
		@Description("Classify never returns null for any thrown error")
		void classify_neverReturnsNull() {
			// given / when
			final ProxyConnectFailure failure = ProxyConnectFailure.classify(new RuntimeException("boom"));

			// then
			assertThat(failure).isNotNull();
			assertThat(failure.reason()).isNotNull();
		}

	}

	@Nested
	@DisplayName("Reason wire values")
	class ReasonWire {

		@Test
		@Story("Wire contract")
		@Severity(SeverityLevel.NORMAL)
		@Description("Wire values are stable lowercase identifiers for the HTTP error payload")
		void reason_wireValues_areStableLowercaseIdentifiers() {
			// given / when / then
			assertThat(ProxyConnectFailure.Reason.TIMEOUT.wire()).isEqualTo("timeout");
			assertThat(ProxyConnectFailure.Reason.CONNECTION_REFUSED.wire()).isEqualTo("connection_refused");
			assertThat(ProxyConnectFailure.Reason.DNS.wire()).isEqualTo("dns");
			assertThat(ProxyConnectFailure.Reason.NOT_FOUND.wire()).isEqualTo("not_found");
			assertThat(ProxyConnectFailure.Reason.UNKNOWN.wire()).isEqualTo("unknown");
		}

	}

}
