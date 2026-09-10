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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

	private static final int CSP_PARAM_MAX_LENGTH = 4096;

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
		if (cspParam != null && cspParam.length() > CSP_PARAM_MAX_LENGTH) {
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
	 * Parse the {@code csp} query parameter as a JSON object (McpUiResourceCsp) and
	 * serialize to a CSP header string. Malformed JSON returns the all-'none' default. A
	 * null or blank param returns the page-level DEFAULT_CSP.
	 * @param cspParam the raw csp query param value
	 * @return the CSP header string
	 */
	String parseAndSerializeCsp(final String cspParam) {
		if (cspParam == null || cspParam.isBlank()) {
			return DEFAULT_CSP;
		}
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> parsed = OBJECT_MAPPER.readValue(cspParam, Map.class);
			if (containsDangerousOrigin(parsed)) {
				LOG.warn("Rejected csp param containing javascript:/data: origins");
				return DEFAULT_CSP
						+ "; default-src 'none'; connect-src 'none'; img-src 'none'; script-src 'unsafe-inline' 'none'; style-src 'unsafe-inline' 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'";
			}
			final String innerCsp = CspSerializer.serialize(parsed);
			return DEFAULT_CSP + "; " + innerCsp;
		}
		catch (final Exception ex) {
			LOG.warn("Failed to parse csp param, using all-none default: {}", ex.getMessage());
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
