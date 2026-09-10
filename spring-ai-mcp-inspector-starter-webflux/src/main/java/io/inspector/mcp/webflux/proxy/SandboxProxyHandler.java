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

package io.inspector.mcp.webflux.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Reactive handler for {@code GET /mcp-inspector-api/sandbox}, mirroring the WebMVC
 * {@link io.inspector.mcp.webmvc.proxy.SandboxProxyController}.
 *
 * <p>
 * Serves the sandbox-proxy HTML page with a restrictive CSP and {@code no-store} caching.
 * The optional {@code csp} query parameter (JSON-encoded CSP overrides per
 * {@code @mcp-ui/client} SandboxConfig docs) overrides the default CSP when present.
 *
 * @author Artem Simeshin
 */
public class SandboxProxyHandler {

	private static final Logger LOG = LoggerFactory.getLogger(SandboxProxyHandler.class);

	private static final String SANDBOX_PROXY_RESOURCE = "mcp-inspector-bundle/sandbox-proxy.html";

	private static final String DEFAULT_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'self'; base-uri 'none'; form-action 'none'";

	private volatile String cachedHtml;

	/**
	 * Serves the sandbox proxy HTML page.
	 * @param request the incoming request, may carry an optional {@code csp} query param
	 * @return the sandbox proxy HTML page with security headers
	 */
	public Mono<ServerResponse> serveSandbox(final ServerRequest request) {
		final String cspParam = request.queryParam("csp").orElse(null);
		final String csp = (cspParam != null && !cspParam.isBlank()) ? cspParam : DEFAULT_CSP;

		return loadHtml()
			.flatMap((html) -> ServerResponse.ok()
				.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
				.header("Content-Security-Policy", csp)
				.cacheControl(CacheControl.noStore())
				.bodyValue(html))
			.switchIfEmpty(Mono
				.fromRunnable(() -> LOG.error("Sandbox proxy resource not found: {}", SANDBOX_PROXY_RESOURCE))
				.then(ServerResponse.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).build()));
	}

	private Mono<String> loadHtml() {
		if (this.cachedHtml != null) {
			return Mono.just(this.cachedHtml);
		}
		return Mono.fromCallable(() -> {
			final ClassPathResource resource = new ClassPathResource(SANDBOX_PROXY_RESOURCE);
			if (!resource.exists()) {
				return null;
			}
			try (InputStream is = resource.getInputStream()) {
				final String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				this.cachedHtml = html;
				return html;
			}
		}).onErrorMap(IOException.class, (ex) -> {
			LOG.error("Failed to read sandbox proxy resource: {}", SANDBOX_PROXY_RESOURCE, ex);
			return ex;
		});
	}

}
