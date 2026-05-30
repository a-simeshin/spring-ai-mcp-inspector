/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for Jackson serialization of {@link InspectorBootstrap}. */
class InspectorBootstrapJacksonTest {

	@Test
	void serialize_emitsExpectedShape() throws Exception {
		InspectorBootstrap bootstrap = new InspectorBootstrap();
		bootstrap.setAuthToken("token-abc");
		bootstrap.setProxyAddress("/mcp-inspector-api");
		bootstrap.setDetectedTransport("streamable-http");
		bootstrap.setDetectedUrl("/mcp");
		bootstrap.setDefaultUrl("https://example.com/mcp");
		bootstrap.setDefaultTransport("sse");
		bootstrap.setDefaultHeaders(List.of(new CustomHeader("X-Tenant", "acme", true)));
		bootstrap.setServerEntries(List
			.of(new ServerEntry("internal", "https://internal/mcp", "streamable-http", Map.of("X-Env", "prod"))));
		bootstrap.getExtra().put("featureFlag", "on");

		String json = new ObjectMapper().writeValueAsString(bootstrap);

		assertThat(json).contains("\"authToken\":\"token-abc\"")
			.contains("\"proxyAddress\":\"/mcp-inspector-api\"")
			.contains("\"detectedTransport\":\"streamable-http\"")
			.contains("\"detectedUrl\":\"/mcp\"")
			.contains("\"defaultUrl\":\"https://example.com/mcp\"")
			.contains("\"defaultTransport\":\"sse\"")
			.contains("\"defaultHeaders\"")
			.contains("\"X-Tenant\"")
			.contains("\"serverEntries\"")
			.contains("\"internal\"")
			.contains("\"extra\"")
			.contains("\"featureFlag\":\"on\"");
	}

}
