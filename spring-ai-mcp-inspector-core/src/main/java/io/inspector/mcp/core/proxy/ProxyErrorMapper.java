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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;

/**
 * Maps transport failures to the structured {@link ProxyErrorDto} contract (D3), with the
 * exact literal table:
 *
 * <table>
 * <caption>status → DTO mapping, gated by {@link TransportKind}</caption>
 * <tr>
 * <th>status</th>
 * <th>code</th>
 * <th>applies to</th>
 * </tr>
 * <tr>
 * <td>400</td>
 * <td>{@code bad_request}</td>
 * <td>SSE only</td>
 * </tr>
 * <tr>
 * <td>401</td>
 * <td>{@code unauthorized}</td>
 * <td>SSE + STREAMABLE (authz exception)</td>
 * </tr>
 * <tr>
 * <td>403</td>
 * <td>{@code forbidden}</td>
 * <td>SSE + STREAMABLE (authz exception)</td>
 * </tr>
 * <tr>
 * <td>404</td>
 * <td>{@code session_not_found}</td>
 * <td>SSE only</td>
 * </tr>
 * <tr>
 * <td>3xx</td>
 * <td>{@code redirect}</td>
 * <td>SSE ONLY</td>
 * </tr>
 * </table>
 *
 * <p>
 * Status extraction is bounded and exact: the mapper first checks for
 * {@link McpHttpClientTransportAuthorizationException} (reading
 * {@code getResponseInfo().statusCode()}), otherwise it extracts the first HTTP status
 * via {@code \b([1-5][0-9][0-9])\b} from the exception message/cause chain. Any other
 * failure yields {@code null} — the caller falls back to the legacy 502/504 behaviour and
 * never fabricates a DTO.
 *
 * @author Artem Simeshin
 */
public final class ProxyErrorMapper {

	/** Bounded status pattern used when no typed exception carries the status. */
	private static final Pattern HTTP_STATUS = Pattern.compile("\\b([1-5][0-9][0-9])\\b");

	private static final String CODE_BAD_REQUEST = "bad_request";

	private static final String CODE_UNAUTHORIZED = "unauthorized";

	private static final String CODE_FORBIDDEN = "forbidden";

	private static final String CODE_SESSION_NOT_FOUND = "session_not_found";

	private static final String CODE_REDIRECT = "redirect";

	private static final String REASON_BAD_REQUEST = "Invalid or missing auth profile or session reference.";

	private static final String GUIDANCE_BAD_REQUEST = "Check the profile fields and profileId, then reconnect.";

	private static final String REASON_UNAUTHORIZED = "The MCP server rejected the request as unauthenticated.";

	private static final String GUIDANCE_UNAUTHORIZED = "Verify the token/API key. OAuth2 profiles refresh and retry once automatically.";

	private static final String REASON_FORBIDDEN = "The MCP server rejected the request as not permitted.";

	private static final String GUIDANCE_FORBIDDEN = "Verify the credential scope or permissions, then reconnect.";

	private static final String REASON_SESSION_NOT_FOUND = "The proxy session no longer exists or has expired.";

	private static final String GUIDANCE_SESSION_NOT_FOUND = "Reconnect to establish a new session.";

	private static final String REASON_REDIRECT = "The MCP server redirected the request and it was not followed.";

	private static final String GUIDANCE_REDIRECT = "Check the server URL; redirects are not followed automatically.";

	private ProxyErrorMapper() {
		// utility class
	}

	/**
	 * Maps {@code error} to the structured DTO for {@code kind}, or {@code null} when the
	 * failure carries no mappable status (legacy 502/504 path).
	 * @param error the transport failure
	 * @param kind the transport kind gating the mapping
	 * @return the DTO, or {@code null}
	 */
	public static ProxyErrorDto map(final Throwable error, final TransportKind kind) {
		final Optional<Integer> status = extractStatus(error);
		if (status.isEmpty()) {
			return null;
		}
		final int code = status.get();
		if (code >= 300 && code < 400) {
			// Redirect is SSE-ONLY; streamable 3xx → null → legacy 502/504.
			return (kind == TransportKind.SSE)
					? new ProxyErrorDto(code, CODE_REDIRECT, REASON_REDIRECT, GUIDANCE_REDIRECT, null) : null;
		}
		return switch (code) {
			case 400 -> (kind == TransportKind.SSE)
					? new ProxyErrorDto(code, CODE_BAD_REQUEST, REASON_BAD_REQUEST, GUIDANCE_BAD_REQUEST, null) : null;
			case 401 -> new ProxyErrorDto(code, CODE_UNAUTHORIZED, REASON_UNAUTHORIZED, GUIDANCE_UNAUTHORIZED, null);
			case 403 -> new ProxyErrorDto(code, CODE_FORBIDDEN, REASON_FORBIDDEN, GUIDANCE_FORBIDDEN, null);
			case 404 -> (kind == TransportKind.SSE) ? new ProxyErrorDto(code, CODE_SESSION_NOT_FOUND,
					REASON_SESSION_NOT_FOUND, GUIDANCE_SESSION_NOT_FOUND, null) : null;
			default -> null;
		};
	}

	/**
	 * Extracts the HTTP status from a transport failure: the typed
	 * {@link McpHttpClientTransportAuthorizationException} status first, then the first
	 * {@code \b([1-5][0-9][0-9])\b} match in the message/cause chain.
	 * @param error the failure to inspect
	 * @return the status, or empty when none is present
	 */
	public static Optional<Integer> extractStatus(final Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof McpHttpClientTransportAuthorizationException authz
					&& authz.getResponseInfo() != null) {
				final int status = authz.getResponseInfo().statusCode();
				if (status > 0) {
					return Optional.of(status);
				}
			}
			if (current.getMessage() != null) {
				final Matcher matcher = HTTP_STATUS.matcher(current.getMessage());
				if (matcher.find()) {
					return Optional.of(Integer.parseInt(matcher.group(1)));
				}
			}
		}
		return Optional.empty();
	}

}
