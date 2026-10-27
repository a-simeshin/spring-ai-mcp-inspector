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
package io.inspector.mcp.itfixtures;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Minimal raw MCP streamable-HTTP endpoint answering inside the application itself, so
 * the loopback client of {@code /connect} performs a real HTTP handshake against it. The
 * {@code initialize} result declares SEP-2640 in the canonical
 * {@code capabilities.extensions} location only.
 *
 * <p>
 * Lives in {@code io.inspector.mcp.itfixtures}, deliberately OUTSIDE the demo app's
 * {@code io.inspector.mcp.demo} component-scan root: Spring Framework 7 dropped the
 * {@code @RequestMapping}-only handler fallback, so the type needs the
 * {@code @Controller} stereotype to be mapped, and with the stereotype it must stay
 * invisible to sibling IT contexts. It only comes alive where a lite-mode source
 * registers it as an explicit bean (see SkillsConnectCapabilityIT).
 */
@Controller
@RequestMapping(MockSkillsMcpEndpoint.ENDPOINT)
public class MockSkillsMcpEndpoint {

	/** Endpoint path the IT redirects the inspector loopback client to. */
	public static final String ENDPOINT = "/mock-skills-mcp";

	private static final String SKILLS_EXT_ID = "io.modelcontextprotocol/skills";

	/**
	 * Handles one JSON-RPC frame per POST, like a minimal streamable-HTTP MCP server.
	 */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> post(@RequestBody final Map<String, Object> frame) {
		final Object id = frame.get("id");
		if (id == null) {
			// notification -> 202, per the streamable-HTTP transport spec
			return ResponseEntity.accepted().build();
		}
		final String method = String.valueOf(frame.get("method"));
		final Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		if ("initialize".equals(method)) {
			@SuppressWarnings("unchecked")
			final Map<String, Object> params = (Map<String, Object>) frame.getOrDefault("params", Map.of());
			response.put("result",
					initializeResult(String.valueOf(params.getOrDefault("protocolVersion", "2025-06-18"))));
			return ResponseEntity.ok().header("Mcp-Session-Id", "mock-skills-1").body(response);
		}
		// every other method: JSON-RPC method-not-found, like a server without the
		// requested capability
		final Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", -32601);
		error.put("message", "Method not found");
		error.put("data", Map.of("method", method));
		response.put("error", error);
		return ResponseEntity.ok().header("Mcp-Session-Id", "mock-skills-1").body(response);
	}

	@DeleteMapping
	public ResponseEntity<Void> delete() {
		return ResponseEntity.ok().build();
	}

	/**
	 * The canonical SEP-2640 declaration: {@code capabilities.extensions} only, no
	 * {@code _meta} fallback. This shape is what the SDK's typed model drops.
	 */
	private static Map<String, Object> initializeResult(final String protocolVersion) {
		final Map<String, Object> skillsSettings = new LinkedHashMap<>();
		skillsSettings.put("directoryRead", true);
		final Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put(SKILLS_EXT_ID, skillsSettings);
		final Map<String, Object> resources = new LinkedHashMap<>();
		resources.put("listChanged", false);
		final Map<String, Object> capabilities = new LinkedHashMap<>();
		capabilities.put("tools", Map.of());
		capabilities.put("resources", resources);
		capabilities.put("extensions", extensions);
		final Map<String, Object> serverInfo = new LinkedHashMap<>();
		serverInfo.put("name", "mock-skills-connect-server");
		serverInfo.put("version", "0.0.1");
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("protocolVersion", protocolVersion);
		result.put("capabilities", capabilities);
		result.put("serverInfo", serverInfo);
		return result;
	}

}
