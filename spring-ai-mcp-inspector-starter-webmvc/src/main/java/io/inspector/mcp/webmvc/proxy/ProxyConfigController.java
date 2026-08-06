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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.transport.DetectedTransport;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.core.transport.TransportType;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;

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
 *   "defaultServerUrl": "/mcp"
 * }
 * </pre>
 *
 * <p>
 * {@code defaultServerUrl} is the <em>relative</em> same-origin path of the detected MCP
 * endpoint (e.g. {@code /mcp}). It is intentionally not an absolute
 * {@code http://localhost:<port>/mcp} URL: the SPA echoes it into the proxy {@code ?url=}
 * query, where an absolute localhost value trips reverse-proxy WAF SSRF rules. The proxy
 * resolves the relative path back to the loopback server-side.
 * {@link InspectorServerPortHolder} is still used to tell whether the embedded server has
 * started.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class ProxyConfigController {

	private final TransportDetector transportDetector;

	private final InspectorServerPortHolder portHolder;

	public ProxyConfigController(final TransportDetector transportDetector,
			final InspectorServerPortHolder portHolder) {
		this.transportDetector = transportDetector;
		this.portHolder = portHolder;
	}

	@GetMapping("/config")
	public Map<String, Object> config() {
		final DetectedTransport detected = this.transportDetector.detect();

		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("defaultEnvironment", Map.of());
		body.put("defaultCommand", "");
		body.put("defaultArgs", "");
		body.put("defaultTransport", mapTransport(detected.type()));
		body.put("defaultServerUrl", buildServerUrl(detected));
		return body;
	}

	private static String mapTransport(final TransportType type) {
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

	private String buildServerUrl(final DetectedTransport detected) {
		final int port = this.portHolder.port();
		if (port <= 0 || detected == null || detected.type() == TransportType.UNKNOWN
				|| detected.type() == TransportType.STDIO_NO_HTTP) {
			return "";
		}
		String path = detected.endpoint();
		if (path == null || path.isBlank()) {
			path = "/mcp";
		}
		// Advertise a RELATIVE same-origin path (e.g. "/mcp"), not an absolute
		// "http://localhost:<port>/mcp". The SPA echoes this value into the proxy
		// ?url= query; an absolute localhost target trips reverse-proxy WAF SSRF rules
		// (OWASP CRS 934110). The proxy resolves the relative path back to the loopback
		// server-side (ProxyTargetResolver) — mirroring how springdoc serves the spec
		// same-origin instead of pointing Swagger UI at an absolute ?url=.
		return path;
	}

}
