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

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Resources declared via {@code @McpResource} (and one unnamed {@code @McpTool}) for the
 * WebMvc introspection IT classes.
 *
 * <p>
 * Registered as a bean inside the {@code io.inspector.mcp.webmvc.it} scan root, so the
 * introspection report attaches a reflected {@code source} (bean/method) to each element.
 * The {@code fileTemplate} method declares a URI template and is classified
 * {@code RESOURCE_TEMPLATE}; {@code unnamedResource} and {@code unnamedTool} carry no
 * annotation name and fall back to the method name (R-NAME-FALLBACK).
 */
@Component
public class TestResourcesProvider {

	@McpResource(uri = "inspector://greeting", name = "greeting", description = "Greeting resource")
	public String greeting() {
		return "hello";
	}

	@McpResource(uri = "inspector://files/{id}", name = "fileTemplate", description = "File template resource")
	public String fileTemplate(final String id) {
		return "file " + id;
	}

	@McpResource(uri = "inspector://unnamed")
	public String unnamedResource() {
		return "note";
	}

	@McpTool
	public String unnamedTool() {
		return "tool";
	}

}
