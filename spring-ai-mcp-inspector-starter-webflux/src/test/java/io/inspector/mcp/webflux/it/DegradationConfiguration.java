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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * Registered as a plain class via {@code @SpringBootTest(classes=...)} (lite mode): a
 * top-level {@code @TestConfiguration} class inside the component-scanned
 * {@code io.inspector.mcp.webflux.it} package would leak into every sibling IT context
 * and break their {@code mcpSyncServer} bootstrap, so this fixture deliberately carries
 * no {@code @Configuration} stereotype.
 *
 * <p>
 * R-DEGRADE v13 fixture: a lazy {@code List<SyncToolSpecification>} bean that records the
 * probe flag and then throws. In the degradation ITs the MCP server auto-configuration is
 * disabled, so nothing consumes the list at bootstrap; the introspection endpoint reads
 * it per-bean (R-READ) and must degrade instead of failing the report.
 */
class DegradationConfiguration {

	/** Set to {@code true} the moment the failing bean is instantiated. */
	static final AtomicBoolean REQUESTED = new AtomicBoolean(false);

	@Bean
	@Lazy
	List<McpServerFeatures.SyncToolSpecification> failingSpecs() {
		DegradationConfiguration.REQUESTED.set(true);
		throw new IllegalStateException("introspection-degrade-fixture");
	}

}
