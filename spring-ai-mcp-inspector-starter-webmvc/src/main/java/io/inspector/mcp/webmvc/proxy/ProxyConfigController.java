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

import java.util.LinkedHashMap;
import java.util.Map;

import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the defaults the upstream client form pre-populates with. Mirrors the upstream
 * {@code GET /config} shape:
 *
 * <pre>
 * {
 *   "defaultEnvironment": {},
 *   "defaultCommand": "",
 *   "defaultArgs": "",
 *   "defaultTransport": "streamable-http" | "sse" | "",
 *   "defaultServerUrl": "http://localhost:&lt;port&gt;/mcp"
 * }
 * </pre>
 *
 * <p>
 * {@code defaultServerUrl} is composed from the local server port (captured at boot via
 * {@link InspectorServerPortHolder}) and the detected MCP endpoint path.
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class ProxyConfigController {

	private final TransportDetector transportDetector;

	private final InspectorServerPortHolder portHolder;

	public ProxyConfigController(TransportDetector transportDetector, InspectorServerPortHolder portHolder) {
		this.transportDetector = transportDetector;
		this.portHolder = portHolder;
	}

	@GetMapping("/config")
	public Map<String, Object> config() {
		DetectedTransport detected = transportDetector.detect();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("defaultEnvironment", Map.of());
		body.put("defaultCommand", "");
		body.put("defaultArgs", "");
		body.put("defaultTransport", mapTransport(detected.type()));
		body.put("defaultServerUrl", buildServerUrl(detected));
		return body;
	}

	private static String mapTransport(TransportType type) {
		if (type == null) {
			return "";
		}
		return switch (type) {
			case SSE -> "sse";
			case STREAMABLE, STATELESS -> "streamable-http";
			case STDIO_NO_HTTP -> "stdio";
			case UNKNOWN -> "";
		};
	}

	private String buildServerUrl(DetectedTransport detected) {
		int port = portHolder.port();
		if (port <= 0 || detected == null || detected.type() == TransportType.UNKNOWN
				|| detected.type() == TransportType.STDIO_NO_HTTP) {
			return "";
		}
		String path = detected.endpoint();
		if (path == null || path.isBlank()) {
			path = "/mcp";
		}
		return "http://localhost:" + port + path;
	}

}
