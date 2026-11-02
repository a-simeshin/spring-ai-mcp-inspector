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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared resolution and validation of the per-phase connection budget behind the optional
 * {@code connectionTimeout} query parameter (seconds).
 *
 * <p>
 * Both servlet and reactive proxy stacks accept the parameter on the same endpoint set
 * ({@code POST /mcp}, {@code GET /sse}, {@code GET /stdio}) and must agree on how its raw
 * string value is parsed and rejected; this helper is the single place that logic lives.
 * Stacks keep their own transport wiring: only payload construction and parsing are
 * shared.
 *
 * @author Artem Simeshin
 */
public final class ProxyConnectionBudgets {

	/**
	 * Upper bound on the {@code connectionTimeout} parameter (seconds). A larger value
	 * would hold a servlet thread (or keep a session alive) for hours while the upstream
	 * stays silent; 600s matches the slowest legitimate bring-up this proxy is expected
	 * to serve.
	 */
	public static final long MAX_SECONDS = 600;

	private ProxyConnectionBudgets() {
	}

	/**
	 * Parses a raw {@code connectionTimeout} query parameter. An absent or blank value
	 * means "no override" and yields the configured default; any value that IS present
	 * must be a decimal integer of seconds within {@code 1..600}. There is no implicit
	 * unit and no rounding of fractional values, so {@code 1.5} is rejected rather than
	 * truncated, and {@code 0} / negative / huge values are rejected instead of being
	 * silently treated as absent.
	 * @param raw the raw parameter value ({@code null} when absent)
	 * @param configuredDefault the configured {@code timeouts.connection} default
	 * @return the budget, never {@code null}
	 * @throws ProxyConnectionBudgetException when a present value is malformed or out of
	 * range; the caller must answer 400 with the structured error body
	 */
	public static Duration parseOrDefault(final String raw, final Duration configuredDefault) {
		if (raw == null || raw.isBlank()) {
			return configuredDefault;
		}
		final long seconds;
		try {
			seconds = Long.parseLong(raw.trim());
		}
		catch (final NumberFormatException ex) {
			throw new ProxyConnectionBudgetException(
					"connectionTimeout must be a whole number of seconds between 1 and " + MAX_SECONDS, raw);
		}
		if (seconds < 1 || seconds > MAX_SECONDS) {
			throw new ProxyConnectionBudgetException(
					"connectionTimeout must be between 1 and " + MAX_SECONDS + " seconds, got " + seconds, raw);
		}
		return Duration.ofSeconds(seconds);
	}

	/**
	 * Builds the structured error body for a rejected {@code connectionTimeout} value:
	 * the existing {@code MCP_CONNECT_FAILED} contract (the one the UI and
	 * {@code NOTICE.d/connect-error-alert.txt} already document) with a
	 * {@code validation} reason, plus the offending value for diagnostics. Marked
	 * {@code retryable: false}: a malformed parameter will not heal on a retry.
	 * @param raw the raw parameter value the client sent
	 * @param detail the human-readable validation message
	 * @return the {@code error} object of the response body
	 */
	public static Map<String, Object> invalidParameterBody(final String raw, final String detail) {
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", "MCP_CONNECT_FAILED");
		error.put("reason", "validation");
		error.put("message", detail);
		error.put("connectionTimeout", (raw != null) ? raw : "");
		error.put("retryable", Boolean.FALSE);
		return Map.of("error", error);
	}

	/**
	 * Builds the structured error body for a per-phase budget overrun: the existing
	 * {@code MCP_CONNECT_FAILED} contract with a {@code timeout} reason, plus the phase,
	 * elapsed and budget numbers. No separate error code: the UI parses this contract
	 * ({@code connectErrors.ts}) and would not recognise a new one.
	 * @param ex the budget overrun raised by the proxy
	 * @return the {@code error} object of the response body
	 */
	public static Map<String, Object> timeoutBody(final ProxyConnectTimeoutException ex) {
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", "MCP_CONNECT_FAILED");
		error.put("reason", "timeout");
		error.put("phase", ex.phase().wire());
		error.put("elapsedMs", ex.elapsedMs());
		error.put("budgetMs", ex.budgetMs());
		error.put("message", ex.getMessage());
		error.put("retryable", Boolean.TRUE);
		return Map.of("error", error);
	}

	/**
	 * Thrown when the {@code connectionTimeout} query parameter fails validation.
	 */
	public static final class ProxyConnectionBudgetException extends RuntimeException {

		private final String rawValue;

		/**
		 * Constructs the exception.
		 * @param message the validation failure description
		 * @param rawValue the offending raw parameter value, for the error body
		 */
		public ProxyConnectionBudgetException(final String message, final String rawValue) {
			super(message);
			this.rawValue = rawValue;
		}

		/**
		 * Returns the offending raw parameter value.
		 * @return the raw value as sent by the client, never {@code null}
		 */
		public String rawValue() {
			return this.rawValue;
		}

	}

}
