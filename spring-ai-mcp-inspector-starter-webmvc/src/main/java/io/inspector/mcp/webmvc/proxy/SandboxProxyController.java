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

import io.inspector.mcp.core.proxy.SandboxCspSerializer;

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
 * {@code @mcp-ui/client} SandboxConfig docs). When present, the App-declared domains are
 * validated as strict CSP host-source expressions and merged into ONE effective policy;
 * values that are not host-source expressions never reach the header.
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
	 * Default CSP for the sandbox proxy page itself: the serializer's baseline with no
	 * App domains (every network-fetch directive locked to 'none'). Kept as ONE policy:
	 * CSP3 parsing ignores repeated directives, so a second, concatenated policy would
	 * never broaden the baseline.
	 */
	private static final String DEFAULT_CSP = SandboxCspSerializer.serialize(null);

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@GetMapping(value = "/sandbox", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> serveSandbox(@RequestParam(value = "csp", required = false) final String cspParam)
			throws IOException {
		// Check oversize BEFORE loading the resource (fail-fast for invalid params)
		if (cspParam != null && cspParam.length() > SandboxCspSerializer.CSP_PARAM_MAX_LENGTH) {
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
	 * Parse the {@code csp} query parameter as a JSON object (McpUiResourceCsp) and merge
	 * it into ONE effective policy at the directive level. Concatenating the page
	 * baseline with a serialized App policy cannot work: CSP3 parsing ignores repeated
	 * directives, so the baseline occurrences (connect-src 'none', the page script-src)
	 * would always win and the App-declared domains would never apply. Malformed JSON and
	 * a null or blank param all return the page baseline {@link #DEFAULT_CSP} with no App
	 * domains. Domain values are validated by the serializer: only strict CSP host-source
	 * expressions enter the policy, so {@code javascript:}/{@code data:} origins, values
	 * with separators or whitespace, and quoted keywords are dropped.
	 * @param cspParam the raw csp query param value
	 * @return the CSP header string: exactly one occurrence of every directive
	 */
	static String parseAndSerializeCsp(final String cspParam) {
		if (cspParam == null || cspParam.isBlank()) {
			return DEFAULT_CSP;
		}
		final Map<String, Object> parsed;
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> map = OBJECT_MAPPER.readValue(cspParam, Map.class);
			parsed = map;
		}
		catch (final Exception ex) {
			LOG.warn("Failed to parse csp param, using page baseline default: {}", ex.getMessage());
			// Malformed JSON -> page baseline (do NOT echo raw value)
			return DEFAULT_CSP;
		}
		return SandboxCspSerializer.serialize(parsed);
	}

}
