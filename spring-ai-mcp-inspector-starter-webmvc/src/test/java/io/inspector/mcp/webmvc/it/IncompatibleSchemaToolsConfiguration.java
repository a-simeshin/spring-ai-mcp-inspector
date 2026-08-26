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

import io.modelcontextprotocol.server.McpServerFeatures;
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
 * Fixture for the incompatible-schema scenario: one tool whose input AND output schemas
 * carry an external {@code $ref}, an unresolved local {@code $ref} and an {@code anyOf}
 * union — exactly the six warnings the checker must report (R-OUTPUT-FIXTURE v11).
 */
class IncompatibleSchemaToolsConfiguration {

	@Bean
	List<McpServerFeatures.SyncToolSpecification> incompatibleSchemaSpecs() {
		final Map<String, Object> inputSchema = Map.of("type", "object", "properties",
				Map.of("payload", Map.of("$ref", "https://example.com/schemas/payload"), "missing",
						Map.of("$ref", "#/definitions/missing"), "choice",
						Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "number")))));
		final Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("payload", Map.of("$ref", "https://example.com/schemas/payload"), "missing",
						Map.of("$ref", "#/definitions/missing"), "choice",
						Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "number")))));
		final McpSchema.Tool tool = McpSchema.Tool.builder("incompatibleSchemaTool", inputSchema)
			.description("Tool with client-incompatible schemas")
			.outputSchema(outputSchema)
			.build();
		return List.of(McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> McpSchema.CallToolResult.builder(List.of()).build())
			.build());
	}

}
