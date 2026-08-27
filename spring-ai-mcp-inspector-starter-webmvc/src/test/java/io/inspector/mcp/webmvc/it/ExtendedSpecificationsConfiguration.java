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

package io.inspector.mcp.webmvc.it;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;

/**
 * Registered as a plain class via {@code @SpringBootTest(classes=...)} (lite mode): a
 * top-level {@code @TestConfiguration} class inside the component-scanned
 * {@code io.inspector.mcp.webmvc.it} package would leak into every sibling IT context
 * (Boot 4.1 does not exclude it from scanning), so this fixture deliberately carries no
 * {@code @Configuration} stereotype.
 *
 * <p>
 * Declared (programmatic) stateless spec fixtures for the introspection ITs (R-STATELESS
 * v10): a stateless sync tool and a stateless sync resource, both as {@code List<...>}
 * registry beans (R10).
 */
class ExtendedSpecificationsConfiguration {

	@Bean
	List<McpStatelessServerFeatures.SyncToolSpecification> statelessToolSpecs() {
		final McpSchema.Tool tool = McpSchema.Tool.builder("statelessTool", Map.of("type", "object"))
			.description("Stateless sync tool")
			.build();
		return List.of(McpStatelessServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((transportContext, request) -> McpSchema.CallToolResult.builder(List.of()).build())
			.build());
	}

	@Bean
	List<McpStatelessServerFeatures.SyncResourceSpecification> statelessResourceSpecs() {
		return List.of(new McpStatelessServerFeatures.SyncResourceSpecification(
				McpSchema.Resource.builder("inspector://stateless", "statelessResource").build(),
				(transportContext, request) -> new McpSchema.ReadResourceResult(List.of())));
	}

}
