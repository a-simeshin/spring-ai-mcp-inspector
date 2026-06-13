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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Generic outbound HTTP fetcher used by the upstream UI to test OAuth and other HTTPS
 * resources without dealing with CORS in the browser.
 *
 * <p>
 * Request body shape (matches upstream {@code /fetch}):
 *
 * <pre>
 * {
 *   "url": "https://example.com/.well-known/openid-configuration",
 *   "init": {
 *     "method": "GET",
 *     "headers": { "Accept": "application/json" },
 *     "body": null
 *   }
 * }
 * </pre>
 *
 * <p>
 * Only {@code http} / {@code https} schemes are allowed; other URLs return 400.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class ProxyFetchController {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyFetchController.class);

	private final McpInspectorProperties.Timeouts timeouts;

	private final HttpClient httpClient;

	public ProxyFetchController() {
		this(null);
	}

	@Autowired
	public ProxyFetchController(final McpInspectorProperties properties) {
		this.timeouts = (properties != null) ? properties.getTimeouts() : new McpInspectorProperties.Timeouts();
		this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeouts.getFetchConnect()).build();
	}

	@PostMapping(path = "/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> fetch(@RequestBody final JsonNode body) {
		final JsonNode urlNode = body.get("url");
		if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "missing or invalid url"));
		}
		final URI uri;
		try {
			uri = URI.create(urlNode.asText());
		}
		catch (final Exception ex) {
			return ResponseEntity.badRequest().body(Map.of("error", "invalid URL"));
		}
		final String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return ResponseEntity.badRequest().body(Map.of("error", "only http/https URLs are allowed"));
		}

		final JsonNode init = body.get("init");
		final String method = (init != null && init.hasNonNull("method")) ? init.get("method").asText("GET") : "GET";
		final String reqBody = (init != null && init.hasNonNull("body")) ? init.get("body").asText("") : "";

		final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
			.timeout(this.timeouts.getFetchRequest())
			.method(method.toUpperCase(), reqBody.isEmpty() ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(reqBody));
		if (init != null && init.has("headers")) {
			final JsonNode headers = init.get("headers");
			headers.properties().forEach((e) -> {
				try {
					reqBuilder.header(e.getKey(), e.getValue().asText());
				}
				catch (final Exception ignored) {
					// restricted header names (e.g. Host) raise IllegalArgumentException;
					// skip.
				}
			});
		}

		try {
			final HttpResponse<String> response = this.httpClient.send(reqBuilder.build(),
					HttpResponse.BodyHandlers.ofString());
			final Map<String, String> respHeaders = new LinkedHashMap<>();
			response.headers().map().forEach((k, v) -> {
				if (!v.isEmpty()) {
					respHeaders.put(k, v.get(0));
				}
			});
			final Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
			envelope.put("status", response.statusCode());
			envelope.put("statusText", "");
			envelope.put("headers", respHeaders);
			envelope.put("body", response.body());
			return ResponseEntity.status(response.statusCode()).body(envelope);
		}
		catch (final Exception ex) {
			LOG.warn("proxy /fetch failed for {}: {}", uri, ex.toString());
			return ResponseEntity.status(502)
				.body(Map.of("error", (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName()));
		}
	}

}
