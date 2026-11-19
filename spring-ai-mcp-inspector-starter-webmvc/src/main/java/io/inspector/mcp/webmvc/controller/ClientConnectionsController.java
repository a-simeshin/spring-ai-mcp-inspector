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

package io.inspector.mcp.webmvc.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.timeline.ClientConnectionRegistry;
import io.inspector.mcp.core.timeline.TargetSanitizer;

/**
 * REST controller exposing the {@link ClientConnectionRegistry} snapshot to the inspector
 * SPA.
 *
 * <p>
 * The route is mounted under
 * {@code ${spring.ai.mcp.inspector.path}/api/client-connections} and lives under the
 * guarded {@code ${path}/api/} prefix, so the existing {@code InspectorAuthFilter} covers
 * it without extra configuration.
 *
 * <p>
 * The bean is registered only when client capture is enabled
 * ({@code spring.ai.mcp.inspector.timeline.client-capture-enabled=true}), the same
 * condition that gates the registry itself.
 *
 * @author Artem Simeshin
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/api/client-connections")
public class ClientConnectionsController {

	private final ClientConnectionRegistry connectionRegistry;

	/**
	 * Creates the controller.
	 * @param connectionRegistry the client connection registry
	 */
	public ClientConnectionsController(final ClientConnectionRegistry connectionRegistry) {
		this.connectionRegistry = connectionRegistry;
	}

	/**
	 * Returns a snapshot of all tracked client connections, sorted by client name.
	 * @return the connection snapshots
	 */
	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ClientConnectionRegistry.ClientConnectionSnapshot> connections() {
		return this.connectionRegistry.snapshot().stream().map(ClientConnectionsController::sanitized).toList();
	}

	/**
	 * Copy of one connection with the failure description sanitized: raw exception text
	 * may embed credential-bearing URLs, which must never leave the endpoint verbatim.
	 * @param connection the registry snapshot entry
	 * @return the same entry with {@code lastError} sanitized
	 */
	private static ClientConnectionRegistry.ClientConnectionSnapshot sanitized(
			final ClientConnectionRegistry.ClientConnectionSnapshot connection) {
		return new ClientConnectionRegistry.ClientConnectionSnapshot(connection.name(), connection.transportType(),
				connection.target(), connection.state(), connection.since(), connection.lastActivity(),
				connection.inCount(), connection.outCount(), TargetSanitizer.sanitizeText(connection.lastError()));
	}

}
