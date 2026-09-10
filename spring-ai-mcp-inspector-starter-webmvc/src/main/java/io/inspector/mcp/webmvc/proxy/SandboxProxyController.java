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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

	@GetMapping(value = "/sandbox", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> serveSandbox(@RequestParam(value = "csp", required = false) final String cspParam)
			throws IOException {
		final ClassPathResource resource = new ClassPathResource(SANDBOX_PROXY_RESOURCE);
		if (!resource.exists()) {
			LOG.error("Sandbox proxy resource not found on classpath: {}", SANDBOX_PROXY_RESOURCE);
			return ResponseEntity.internalServerError().build();
		}
		final String html;
		try (InputStream is = resource.getInputStream()) {
			html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}

		final String csp = (cspParam != null && !cspParam.isBlank()) ? cspParam : DEFAULT_CSP;

		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			.header("Content-Security-Policy", csp)
			.cacheControl(CacheControl.noStore())
			.body(html);
	}

}
