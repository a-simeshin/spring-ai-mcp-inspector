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

package io.inspector.mcp.webflux.it;

import java.time.Instant;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class TestToolsProvider {

	@McpTool(name = "echo", description = "Echo back input text")
	public String echo(@McpToolParam(description = "text", required = true) final String text) {
		return text;
	}

	@McpTool(name = "sum", description = "Sum two integers")
	public int sum(@McpToolParam(description = "a", required = true) final int a,
			@McpToolParam(description = "b", required = true) final int b) {
		return a + b;
	}

	@McpTool(name = "currentTime", description = "Current time in ISO-8601")
	public String currentTime() {
		return Instant.now().toString();
	}

}
