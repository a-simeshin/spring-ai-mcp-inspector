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

package io.inspector.mcp.core.introspect.model;

/**
 * Discriminator of an introspection element.
 *
 * <p>
 * Resources whose URI contains a {@code {placeholder}} are classified
 * {@link #RESOURCE_TEMPLATE} (R-TEMPLATE), mirroring Spring AI 2.0 where a URI-template
 * resource is served as a template, not as a plain resource.
 *
 * @author Artem Simeshin
 */
public enum McpElementKind {

	/** A tool declared via {@code @McpTool} or a tool specification. */
	TOOL,

	/** A resource declared via {@code @McpResource} or a resource specification. */
	RESOURCE,

	/** A resource whose URI contains a {@code {placeholder}} (R-TEMPLATE v11). */
	RESOURCE_TEMPLATE

}
