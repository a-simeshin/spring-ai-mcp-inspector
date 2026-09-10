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

package io.inspector.mcp.webmvc.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the sandbox proxy HTML page ({@code GET /mcp-inspector-api/sandbox}) for the
 * SEP-1865 Apps lifecycle.
 *
 * <p>
 * The sandbox proxy page acts as the outer iframe in the double-iframe sandbox
 * architecture. It relays postMessage traffic between the host and the opaque-origin
 * inner view iframe. The page is loaded into an iframe by the {@code @mcp-ui/client}
 * AppFrame library, which waits for a {@code ui/notifications/sandbox-proxy-ready}
 * postMessage before proceeding with the App lifecycle.
 *
 * <p>
 * This endpoint is intentionally excluded from the {@link ProxyAuthFilter} set: the
 * sandbox page is loaded in an iframe whose document carries no headers, so it must be
 * reachable without authentication. This mirrors {@link ProxyHealthController} being
 * open. The page has no privileged behavior: it never calls the proxy API, only relays
 * postMessage.
 *
 * <p>
 * Response headers:
 * <ul>
 * <li>{@code Content-Type: text/html;charset=UTF-8}</li>
 * <li>{@code Content-Security-Policy}: restrictive default that allows only
 * {@code 'self'} scripts and styles (the proxy page's own inline JS is covered by
 * {@code 'unsafe-inline'})</li>
 * <li>{@code Cache-Control: no-store}: the page must always be fresh</li>
 * </ul>
 *
 * <p>
 * An optional {@code csp} query parameter may be supplied (JSON-encoded CSP overrides per
 * {@code @mcp-ui/client} SandboxConfig docs). When present, the CSP value is intersected
 * with the global denylist and returned as a response header. The denylist restricts
 * connect-src, frame-src, and resource domains to known-safe origins.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class SandboxProxyController {

	private static final Logger LOG = LoggerFactory.getLogger(SandboxProxyController.class);

	/**
	 * Classpath resource path for the sandbox proxy HTML page. Copied alongside the UI
	 * bundle into {@code mcp-inspector-bundle/} by the UI module's build.
	 */
	private static final String SANDBOX_PROXY_RESOURCE = "mcp-inspector-bundle/sandbox-proxy.html";

	/**
	 * Default CSP for the sandbox proxy page itself. At page level we only need
	 * script-src 'self' 'unsafe-inline' (for the proxy relay script) and the frame
	 * sandbox does the heavy lifting via the inner view iframe's opaque origin.
	 */
	private static final String DEFAULT_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'self'; base-uri 'none'; form-action 'none'";

	private static final int CSP_PARAM_MAX_LENGTH = 4096;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@GetMapping(value = "/sandbox", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> serveSandbox(@RequestParam(value = "csp", required = false) final String cspParam)
			throws IOException {
		// Check oversize BEFORE loading the resource (fail-fast for invalid params)
		if (cspParam != null && cspParam.length() > CSP_PARAM_MAX_LENGTH) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("CSP parameter exceeds maximum length");
		}

		final ClassPathResource resource = new ClassPathResource(SANDBOX_PROXY_RESOURCE);
		if (!resource.exists()) {
			LOG.error("Sandbox proxy resource not found on classpath: {}", SANDBOX_PROXY_RESOURCE);
			return ResponseEntity.internalServerError().build();
		}
		final String html;
		try (InputStream is = resource.getInputStream()) {
			html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}

		// Parse csp query param as JSON object -> serialize to CSP string
		final String csp = parseAndSerializeCsp(cspParam);

		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			.header("Content-Security-Policy", csp)
			.cacheControl(CacheControl.noStore())
			.body(html);
	}

	/**
	 * Parse the {@code csp} query parameter as a JSON object (McpUiResourceCsp) and
	 * serialize to a CSP header string. Malformed JSON returns the all-'none' default. A
	 * null or blank param returns the page-level DEFAULT_CSP.
	 * @param cspParam the raw csp query param value
	 * @return the CSP header string
	 */
	static String parseAndSerializeCsp(final String cspParam) {
		if (cspParam == null || cspParam.isBlank()) {
			return DEFAULT_CSP;
		}
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> parsed = OBJECT_MAPPER.readValue(cspParam, Map.class);
			// Reject javascript:/data: origins in any domain list
			if (containsDangerousOrigin(parsed)) {
				LOG.warn("Rejected csp param containing javascript:/data: origins");
				return DEFAULT_CSP
						+ "; default-src 'none'; connect-src 'none'; img-src 'none'; script-src 'unsafe-inline' 'none'; style-src 'unsafe-inline' 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'";
			}
			final String innerCsp = CspSerializer.serialize(parsed);
			// Intersect with page-level CSP: keep the page's own default-src,
			// script-src, style-src, frame-src 'self' for the proxy page itself.
			return DEFAULT_CSP + "; " + innerCsp;
		}
		catch (final Exception ex) {
			LOG.warn("Failed to parse csp param, using all-none default: {}", ex.getMessage());
			// Malformed JSON -> all-'none' default (do NOT echo raw value)
			return DEFAULT_CSP
					+ "; default-src 'none'; connect-src 'none'; img-src 'none'; script-src 'unsafe-inline' 'none'; style-src 'unsafe-inline' 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'";
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean containsDangerousOrigin(final Map<String, Object> csp) {
		final String[] keys = { "connectDomains", "resourceDomains", "frameDomains", "baseUriDomains" };
		for (final String key : keys) {
			final Object value = csp.get(key);
			if (value instanceof List<?> list) {
				for (final Object item : list) {
					if (item instanceof String s && (s.startsWith("javascript:") || s.startsWith("data:"))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// CspSerializer inner class (mirrors csp-serializer.ts)
	static final class CspSerializer {

		private static final String ALL_NONE_BASELINE = "default-src 'none'; connect-src 'none'; img-src 'none'; script-src 'unsafe-inline' 'none'; style-src 'unsafe-inline' 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'";

		static String serialize(final Map<String, Object> csp) {
			if (csp == null) {
				return ALL_NONE_BASELINE;
			}

			final List<String> parts = new ArrayList<>();
			parts.add("default-src 'none'");

			final List<String> connectDomains = safeList(csp.get("connectDomains"));
			if (!connectDomains.isEmpty()) {
				parts.add("connect-src " + String.join(" ", connectDomains));
			}
			else {
				parts.add("connect-src 'none'");
			}

			final List<String> resourceDomains = safeList(csp.get("resourceDomains"));
			if (!resourceDomains.isEmpty()) {
				final String joined = String.join(" ", resourceDomains);
				parts.add("img-src " + joined);
				parts.add("script-src 'unsafe-inline' " + joined);
				parts.add("style-src 'unsafe-inline' " + joined);
				parts.add("font-src " + joined);
				parts.add("media-src " + joined);
			}
			else {
				parts.add("img-src 'none'");
				parts.add("script-src 'unsafe-inline' 'none'");
				parts.add("style-src 'unsafe-inline' 'none'");
				parts.add("font-src 'none'");
				parts.add("media-src 'none'");
			}

			final List<String> frameDomains = safeList(csp.get("frameDomains"));
			if (!frameDomains.isEmpty()) {
				parts.add("frame-src " + String.join(" ", frameDomains));
			}
			else {
				parts.add("frame-src 'none'");
			}

			final List<String> baseUriDomains = safeList(csp.get("baseUriDomains"));
			if (!baseUriDomains.isEmpty()) {
				parts.add("base-uri " + String.join(" ", baseUriDomains));
			}
			else {
				parts.add("base-uri 'self'");
			}

			parts.add("form-action 'none'");

			return String.join("; ", parts);
		}

		@SuppressWarnings("unchecked")
		private static List<String> safeList(final Object value) {
			if (value instanceof List<?> rawList) {
				final List<String> result = new ArrayList<>();
				for (final Object item : rawList) {
					if (item instanceof String s) {
						result.add(s);
					}
				}
				return result;
			}
			return Collections.emptyList();
		}

	}

}
