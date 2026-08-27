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

package io.inspector.mcp.core.introspect.outside.resources;

import org.springframework.ai.mcp.annotation.McpResource;

/**
 * Negative fixture: declares a method-level {@code @McpResource} but is not a Spring
 * bean, so the introspector must flag it as OUTSIDE_COMPONENT_SCAN when its package is
 * inside a component-scan root (R-OUTSIDE-RESOURCE v11).
 */
public class OutsideMcpResource {

	@McpResource(uri = "demo://outside", name = "outsideResource")
	public String outsideResource() {
		return "outside";
	}

}
