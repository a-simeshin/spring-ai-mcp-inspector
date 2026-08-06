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

import java.net.URI;

/**
 * Resolves the upstream target the proxy should connect to from the (possibly relative or
 * blank) {@code url} the browser sends.
 *
 * <p>
 * Background: the embedded inspector is a <em>same-origin</em> tool — its proxy target is
 * the MCP server running in the very same process (loopback). The upstream React client,
 * however, echoes the configured server URL back into every proxy call as a
 * {@code ?url=...} query parameter. If that value is an absolute {@code http://localhost}
 * /{@code http://127.0.0.1} URL, a reverse-proxy WAF (e.g. ModSecurity + OWASP CRS rule
 * 934110/931100) flags it as an SSRF/RFI attempt and drops the request before it ever
 * reaches the app.
 *
 * <p>
 * To stay WAF-friendly — the same way {@code springdoc-openapi} serves the OpenAPI spec
 * same-origin at a relative path instead of pointing Swagger UI at an absolute
 * {@code ?url=} — the inspector advertises a <em>relative</em> default ({@code /mcp}) and
 * resolves it back to the loopback origin <em>server-side</em>:
 *
 * <ul>
 * <li>an <em>absolute</em> URL (has a scheme) is returned unchanged — back-compat for an
 * explicit, non-loopback target;</li>
 * <li>an <em>absolute path</em> ({@code /mcp}, {@code /sse}) resolves against
 * {@code http://127.0.0.1:<port>};</li>
 * <li>a <em>blank</em> url resolves to {@code http://127.0.0.1:<port><defaultPath>};</li>
 * <li>a <em>protocol-relative</em> ({@code //host}) input is rejected as an SSRF
 * vector.</li>
 * </ul>
 *
 * @author Artem Simeshin
 */
public final class ProxyTargetResolver {

	/** Loopback host used for the resolved base — matches the loopback MCP client. */
	private static final String LOOPBACK_HOST = "127.0.0.1";

	private ProxyTargetResolver() {
	}

	/**
	 * Absolute loopback origin (no trailing slash), e.g. {@code http://127.0.0.1:8080}.
	 * @param port the embedded server port (must be positive)
	 * @return the loopback base origin
	 * @throws IllegalArgumentException if {@code port} is not positive
	 */
	public static String loopbackBase(final int port) {
		if (port <= 0) {
			throw new IllegalArgumentException("inspector loopback port not initialised yet: " + port);
		}
		return "http://" + LOOPBACK_HOST + ":" + port;
	}

	/**
	 * Resolves a possibly relative/blank browser-supplied {@code url} to the absolute URI
	 * the proxy connects to.
	 * @param url the target url from the {@code ?url=} query param; may be {@code null},
	 * blank, an absolute URL, or an absolute path
	 * @param port the embedded server port for loopback resolution
	 * @param defaultPath the loopback path to use when {@code url} is blank (e.g.
	 * {@code /mcp} for streamable, {@code /sse} for SSE)
	 * @return the resolved absolute {@link URI}
	 * @throws IllegalArgumentException for protocol-relative ({@code //host}) or
	 * otherwise unresolvable inputs
	 */
	public static URI resolve(final String url, final int port, final String defaultPath) {
		final String trimmed = (url != null) ? url.trim() : "";
		if (!trimmed.isEmpty()) {
			if (trimmed.startsWith("//")) {
				throw new IllegalArgumentException("protocol-relative target URLs are not allowed: " + url);
			}
			final URI parsed = URI.create(trimmed);
			if (parsed.getScheme() != null) {
				// absolute URL (has scheme) → explicit target, pass through unchanged
				return parsed;
			}
			if (trimmed.startsWith("/")) {
				// same-origin absolute path → resolve against the loopback origin
				return URI.create(loopbackBase(port) + trimmed);
			}
			throw new IllegalArgumentException(
					"target URL must be absolute (http[s]://...) or an absolute path (/...): " + url);
		}
		final String path = (defaultPath != null && !defaultPath.isBlank()) ? defaultPath : "/";
		return URI.create(loopbackBase(port) + (path.startsWith("/") ? path : "/" + path));
	}

}
