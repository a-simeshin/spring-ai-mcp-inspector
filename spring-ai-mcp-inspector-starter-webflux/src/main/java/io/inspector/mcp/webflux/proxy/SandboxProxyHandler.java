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
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import io.inspector.mcp.core.proxy.SandboxCspSerializer;

/**
 * Reactive handler for {@code GET /mcp-inspector-api/sandbox}, mirroring the WebMVC
 * {@link io.inspector.mcp.webmvc.proxy.SandboxProxyController}.
 *
 * <p>
 * Serves the sandbox-proxy HTML page with a restrictive CSP and {@code no-store} caching.
 * The optional {@code csp} query parameter (JSON-encoded CSP overrides per
 * {@code @mcp-ui/client} SandboxConfig docs) is merged into ONE effective policy; the
 * App-declared domains are validated as strict CSP host-source expressions by the shared
 * {@link SandboxCspSerializer}.
 *
 * @author Artem Simeshin
 */
public class SandboxProxyHandler {

	private static final Logger LOG = LoggerFactory.getLogger(SandboxProxyHandler.class);

	private static final String SANDBOX_PROXY_RESOURCE = "mcp-inspector-bundle/sandbox-proxy.html";

	// One effective policy: CSP3 ignores repeated directives, so concatenating a
	// second policy would never broaden this baseline (see parseAndSerializeCsp).
	private static final String DEFAULT_CSP = SandboxCspSerializer.serialize(null);

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private volatile String cachedHtml;

	/**
	 * Serves the sandbox proxy HTML page.
	 * @param request the incoming request, may carry an optional {@code csp} query param
	 * @return the sandbox proxy HTML page with security headers
	 */
	public Mono<ServerResponse> serveSandbox(final ServerRequest request) {
		final String cspParam = request.queryParam("csp").orElse(null);

		// Cap param length at 4 KB -> 400
		if (cspParam != null && cspParam.length() > SandboxCspSerializer.CSP_PARAM_MAX_LENGTH) {
			return ServerResponse.badRequest().bodyValue("CSP parameter exceeds maximum length");
		}

		final String csp = parseAndSerializeCsp(cspParam);

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
	String parseAndSerializeCsp(final String cspParam) {
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
