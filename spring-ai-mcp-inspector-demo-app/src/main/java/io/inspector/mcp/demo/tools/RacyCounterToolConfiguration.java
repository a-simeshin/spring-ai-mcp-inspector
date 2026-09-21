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

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deliberately thread-unsafe counter tool for the concurrency probe (issue #237).
 *
 * <p>
 * {@code racyCounter} uses a plain (non-atomic) {@code int} as shared mutable state.
 * Under N concurrent calls on one session, the increments race: some are lost, and the
 * returned values diverge. This is the negative-test tool for the concurrency probe: the
 * probe MUST detect either failures or diverging response bodies when run against this
 * tool.
 */
@Configuration
public class RacyCounterToolConfiguration {

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "delta": {
			      "type": "integer",
			      "description": "amount to add to the shared counter"
			    }
			  },
			  "required": ["delta"]
			}
			""";

	private int sharedCounter = 0;

	@Bean
	public ToolCallback racyCounter() {
		return new ToolCallback() {

			@Override
			public ToolDefinition getToolDefinition() {
				return ToolDefinition.builder()
					.name("racyCounter")
					.description("Deliberately racy counter: shared mutable state without synchronization")
					.inputSchema(INPUT_SCHEMA)
					.build();
			}

			@Override
			public String call(String toolInput) {
				// Read shared state, sleep to widen the race window, then write.
				// Multiple concurrent calls will interleave read and write,
				// producing lost updates and diverging response bodies.
				int current = sharedCounter;
				try {
					Thread.sleep(100L);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("racyCounter interrupted", e);
				}
				// Parse delta from input
				int delta = 0;
				try {
					tools.jackson.databind.JsonNode node = new tools.jackson.databind.ObjectMapper()
						.readTree(toolInput);
					delta = node.has("delta") ? node.get("delta").asInt() : 0;
				}
				catch (tools.jackson.core.JacksonException e) {
					// Unparseable input: delta stays 0
				}
				sharedCounter = current + delta;
				return String.valueOf(sharedCounter);
			}
		};
	}

}
