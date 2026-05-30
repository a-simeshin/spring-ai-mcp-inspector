/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class ProxyFetchController {

	private static final Logger LOG = LoggerFactory.getLogger(ProxyFetchController.class);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	@PostMapping(path = "/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Object> fetch(@RequestBody JsonNode body) {
		JsonNode urlNode = body.get("url");
		if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "missing or invalid url"));
		}
		URI uri;
		try {
			uri = URI.create(urlNode.asText());
		}
		catch (Exception ex) {
			return ResponseEntity.badRequest().body(Map.of("error", "invalid URL"));
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return ResponseEntity.badRequest().body(Map.of("error", "only http/https URLs are allowed"));
		}

		JsonNode init = body.get("init");
		String method = (init != null && init.hasNonNull("method")) ? init.get("method").asText("GET") : "GET";
		String reqBody = (init != null && init.hasNonNull("body")) ? init.get("body").asText("") : "";

		HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(30))
			.method(method.toUpperCase(), reqBody.isEmpty() ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(reqBody));
		if (init != null && init.has("headers")) {
			JsonNode headers = init.get("headers");
			headers.fields().forEachRemaining(e -> {
				try {
					reqBuilder.header(e.getKey(), e.getValue().asText());
				}
				catch (Exception ignored) {
					// restricted header names (e.g. Host) raise IllegalArgumentException;
					// skip.
				}
			});
		}

		try {
			HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
			Map<String, String> respHeaders = new LinkedHashMap<>();
			response.headers().map().forEach((k, v) -> {
				if (!v.isEmpty()) {
					respHeaders.put(k, v.get(0));
				}
			});
			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
			envelope.put("status", response.statusCode());
			envelope.put("statusText", "");
			envelope.put("headers", respHeaders);
			envelope.put("body", response.body());
			return ResponseEntity.status(response.statusCode()).body(envelope);
		}
		catch (Exception ex) {
			LOG.warn("proxy /fetch failed for {}: {}", uri, ex.toString());
			return ResponseEntity.status(502)
				.body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
		}
	}

}
