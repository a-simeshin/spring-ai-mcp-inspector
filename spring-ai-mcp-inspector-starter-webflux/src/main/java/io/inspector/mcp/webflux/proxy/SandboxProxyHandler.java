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

	// One effective policy: CSP3 ignores repeated directives, so concatenating a
	// second policy would never broaden this baseline (see parseAndSerializeCsp).
	private static final String DEFAULT_CSP = CspSerializer.serialize(null);

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
	 * Parse the {@code csp} query parameter as a JSON object (McpUiResourceCsp) and merge
	 * it into ONE effective policy at the directive level. Concatenating the page
	 * baseline with a serialized App policy cannot work: CSP3 parsing ignores repeated
	 * directives, so the baseline occurrences (connect-src 'none', the page script-src)
	 * would always win and the App-declared domains would never apply. Malformed JSON,
	 * javascript:/data: origins, and a null or blank param all return the page baseline
	 * {@link #DEFAULT_CSP} with no App domains.
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
		// Reject javascript:/data: origins in any domain list
		if (containsDangerousOrigin(parsed)) {
			LOG.warn("Rejected csp param containing javascript:/data: origins");
			return DEFAULT_CSP;
		}
		return CspSerializer.serialize(parsed);
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

		/**
		 * Serialize a McpUiResourceCsp object into ONE effective policy for the sandbox
		 * proxy response: the page baseline merged at the directive level with the
		 * App-declared domains. Every directive is emitted at most once (CSP3 ignores
		 * repeated directives, keeping only the first occurrence), so the App domains in
		 * this policy are the ones the browser actually enforces. A null csp yields the
		 * page baseline with every network-fetch directive locked to 'none'.
		 * @param csp the parsed McpUiResourceCsp object, or null for the page baseline
		 * @return the serialized policy with exactly one occurrence of every directive
		 */
		static String serialize(final Map<String, Object> csp) {
			final Map<String, Object> config = (csp != null) ? csp : Map.of();
			final List<String> connectDomains = safeList(config.get("connectDomains"));
			final List<String> resourceDomains = safeList(config.get("resourceDomains"));
			final List<String> frameDomains = safeList(config.get("frameDomains"));
			final List<String> baseUriDomains = safeList(config.get("baseUriDomains"));

			final List<String> parts = new ArrayList<>();
			parts.add("default-src 'none'");

			// The page's own relay script and style are inline, and the srcdoc guest
			// inherits this policy, so its inline scripts need 'unsafe-inline' too. App
			// resourceDomains broaden the SAME directive instead of a second, ignored
			// script-src.
			if (resourceDomains.isEmpty()) {
				parts.add("script-src 'self' 'unsafe-inline'");
				parts.add("style-src 'self' 'unsafe-inline'");
				parts.add("img-src 'none'");
				parts.add("font-src 'none'");
				parts.add("media-src 'none'");
			}
			else {
				final String joined = String.join(" ", resourceDomains);
				parts.add("script-src 'self' 'unsafe-inline' " + joined);
				parts.add("style-src 'self' 'unsafe-inline' " + joined);
				parts.add("img-src " + joined);
				parts.add("font-src " + joined);
				parts.add("media-src " + joined);
			}

			// connect-src: App-declared domains only, or 'none' when the App declares
			// none (the page itself performs no fetches).
			if (connectDomains.isEmpty()) {
				parts.add("connect-src 'none'");
			}
			else {
				parts.add("connect-src " + String.join(" ", connectDomains));
			}

			// frame-src keeps 'self': the page must stay able to host its own srcdoc
			// view iframe regardless of the App frameDomains.
			if (frameDomains.isEmpty()) {
				parts.add("frame-src 'self'");
			}
			else {
				parts.add("frame-src 'self' " + String.join(" ", frameDomains));
			}

			if (baseUriDomains.isEmpty()) {
				parts.add("base-uri 'none'");
			}
			else {
				parts.add("base-uri " + String.join(" ", baseUriDomains));
			}

			// SEP-1865 draft, "Sandbox proxy", item 5: object-src 'none' everywhere.
			parts.add("object-src 'none'");
			parts.add("form-action 'none'");
			parts.add("frame-ancestors 'self'");

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
