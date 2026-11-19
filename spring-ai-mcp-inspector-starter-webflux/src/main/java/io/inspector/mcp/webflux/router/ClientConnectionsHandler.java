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

package io.inspector.mcp.webflux.router;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.timeline.ClientConnectionRegistry;
import io.inspector.mcp.core.timeline.TargetSanitizer;

/**
 * Reactive HTTP handler for {@code GET ${path}/api/client-connections}. Mirrors the
 * WebMVC {@code ClientConnectionsController} contract exactly: the same registry snapshot
 * payload, sorted by client name, the same field order in every entry.
 *
 * <p>
 * Sits behind {@code InspectorAuthWebFilter} because the route lives under
 * {@code ${path}/api/}, which is the filter's guarded prefix - the same protection the
 * servlet stack gives the controller via {@code InspectorAuthFilter}.
 *
 * <p>
 * The bean is registered only when client capture is enabled
 * ({@code spring.ai.mcp.inspector.timeline.client-capture-enabled=true}), the same
 * condition that gates the registry itself.
 *
 * @author Artem Simeshin
 */
public class ClientConnectionsHandler {

	private final ClientConnectionRegistry connectionRegistry;

	/**
	 * Creates the handler.
	 * @param connectionRegistry the client connection registry
	 */
	public ClientConnectionsHandler(final ClientConnectionRegistry connectionRegistry) {
		this.connectionRegistry = connectionRegistry;
	}

	/**
	 * Returns a snapshot of all tracked client connections, sorted by client name.
	 * @param request the incoming request (no query parameters)
	 * @return the connection snapshots as JSON
	 */
	public Mono<ServerResponse> snapshot(final ServerRequest request) {
		final List<ClientConnectionRegistry.ClientConnectionSnapshot> connections = this.connectionRegistry.snapshot()
			.stream()
			.map(ClientConnectionsHandler::sanitized)
			.toList();
		return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(connections);
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
