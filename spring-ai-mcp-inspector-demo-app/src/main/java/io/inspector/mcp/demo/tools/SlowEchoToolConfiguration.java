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
package io.inspector.mcp.demo.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual registration of the {@code slowEcho} demo tool as a plain {@link ToolCallback}
 * bean — deliberately NOT an {@code @McpTool} method.
 *
 * <p>
 * Why manual: the Spring AI MCP annotation scanner always synthesizes a fully populated
 * {@code annotations} object (the MCP spec defaults) for annotation-less {@code @McpTool}
 * methods, so {@code tools/list} would carry
 * {@code readOnlyHint=false, destructiveHint=true} as if the server had declared them. A
 * raw {@link ToolCallback}, by contrast, is converted by the MCP server
 * auto-configuration from a {@link ToolDefinition}, which carries no annotations — the
 * {@code annotations} field is then omitted from the wire entry entirely (MCP SDK
 * serializes {@code Tool} with {@code NON_ABSENT} inclusion). The inspector UI renders
 * such an entry with muted "(default)" chips and the "Spec default, not declared by
 * server" tooltip, keeping the honest-hints demo path (issue #57) demonstrable
 * end-to-end.
 */
@Configuration
public class SlowEchoToolConfiguration {

	/** Mirror of the {@code text} parameter the annotation-based tools use. */
	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "text": {
			      "type": "string",
			      "description": "text to echo (slowly)"
			    }
			  },
			  "required": ["text"]
			}
			""";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Bean
	public ToolCallback slowEcho() {
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder()
					.name("slowEcho")
					.description("Echo text after a ~2 second delay")
					.inputSchema(INPUT_SCHEMA)
					.build();
			}

			@Override
			public String call(String toolInput) {
				try {
					Thread.sleep(2000L);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("slowEcho interrupted", e);
				}
				try {
					JsonNode node = SlowEchoToolConfiguration.this.objectMapper.readTree(toolInput);
					String text = node.has("text") ? node.get("text").asText() : null;
					if (text == null) {
						throw new IllegalArgumentException("slowEcho: missing required parameter 'text'");
					}
					return text;
				}
				catch (java.io.IOException e) {
					throw new IllegalArgumentException("slowEcho: unparseable arguments: " + toolInput, e);
				}
			}
		};
	}

}
