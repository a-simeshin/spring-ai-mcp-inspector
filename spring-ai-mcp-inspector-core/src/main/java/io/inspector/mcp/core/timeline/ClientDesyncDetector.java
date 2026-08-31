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

package io.inspector.mcp.core.timeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correlates {@code @Mcp*} handler bindings (from {@link ClientHandlerScanner}) with
 * configured MCP clients (from {@link ClientConfigReader}) and detects four classes of
 * silent desyncs:
 *
 * <ol>
 * <li><b>Orphan handler</b>: a handler references a client name that does not exist in
 * {@code spring.ai.mcp.client.*.connections}. A typo in {@code clients()} silently
 * produces a dead handler that never fires.</li>
 * <li><b>Orphan client</b>: a client is configured but no handler is bound to it where
 * one is expected. The client runs but sampling/elicitation/logging/ progress callbacks
 * will answer {@code METHOD_NOT_FOUND}.</li>
 * <li><b>Transport mismatch</b>: an HTTP-configured connection has a stdio-style property
 * ({@code command}), or a stdio-configured connection has an HTTP-style property
 * ({@code url}). This is a misconfiguration of the transport family. A conflicting extra
 * property (both {@code url} and {@code command} on the same connection) is also
 * reported.</li>
 * <li><b>Duplicate handler bindings</b>: the same handler kind is bound to the same
 * client name by more than one bean/method. Only one survives at runtime; the others are
 * silently lost.</li>
 * </ol>
 *
 * @author Artem Simeshin
 */
public final class ClientDesyncDetector {

	private ClientDesyncDetector() {
	}

	/**
	 * Runs all four detectors against the given inputs and returns the combined list of
	 * findings.
	 * @param handlerBindings the scanned {@code @Mcp*} handler bindings
	 * @param clientConfigs the configured MCP clients keyed by name
	 * @return a list of desync findings (never {@code null})
	 */
	public static List<DesyncFinding> detect(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Map<String, ClientConfigReader.ClientConfig> clientConfigs) {
		final List<DesyncFinding> findings = new ArrayList<>();
		findings.addAll(detectOrphanHandlers(handlerBindings, clientConfigs));
		findings.addAll(detectOrphanClients(handlerBindings, clientConfigs));
		findings.addAll(detectTransportMismatches(clientConfigs));
		findings.addAll(detectDuplicateBindings(handlerBindings));
		return List.copyOf(findings);
	}

	/**
	 * Detector 1: a handler references a client name not present in the configuration.
	 * @param handlerBindings the scanned bindings
	 * @param clientConfigs the configured clients
	 * @return findings for orphan handlers
	 */
	static List<DesyncFinding> detectOrphanHandlers(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Map<String, ClientConfigReader.ClientConfig> clientConfigs) {
		final List<DesyncFinding> findings = new ArrayList<>();
		final Set<String> configuredNames = clientConfigs.keySet();
		for (final ClientHandlerScanner.HandlerBinding binding : handlerBindings) {
			if (ClientHandlerScanner.ALL_CLIENTS.equals(binding.clientName())) {
				continue;
			}
			if (!configuredNames.contains(binding.clientName())) {
				findings.add(new DesyncFinding(DesyncType.ORPHAN_HANDLER, binding.clientName(), binding.handlerKind(),
						binding.beanName() + "#" + binding.methodName(), "Handler references client '"
								+ binding.clientName() + "' which is not configured in spring.ai.mcp.client.*"));
			}
		}
		return findings;
	}

	/**
	 * Detector 2: a client is configured but no handler is bound to it where one is
	 * expected. Spring AI supports clients with no sampling/elicitation/logging/progress
	 * callbacks, so a missing handler is only a desync when at least one other client of
	 * the same transport family has a handler bound to it. This signals intent: the user
	 * set up handlers for this transport type but forgot one client.
	 * @param handlerBindings the scanned bindings
	 * @param clientConfigs the configured clients
	 * @return findings for orphan clients
	 */
	static List<DesyncFinding> detectOrphanClients(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Map<String, ClientConfigReader.ClientConfig> clientConfigs) {
		if (handlerBindings.isEmpty()) {
			return List.of();
		}
		final Set<String> boundClients = ClientHandlerScanner.explicitClientNames(handlerBindings);
		final boolean hasWildcard = handlerBindings.stream()
			.anyMatch((b) -> ClientHandlerScanner.ALL_CLIENTS.equals(b.clientName()));
		if (hasWildcard) {
			return List.of();
		}
		final List<DesyncFinding> findings = new ArrayList<>();
		for (final Map.Entry<String, ClientConfigReader.ClientConfig> entry : clientConfigs.entrySet()) {
			final String clientName = entry.getKey();
			final ClientConfigReader.ClientConfig config = entry.getValue();
			if (boundClients.contains(clientName)) {
				continue;
			}
			final boolean siblingHasHandler = clientConfigs.values()
				.stream()
				.anyMatch((c) -> !c.name().equals(clientName) && c.transportType().equals(config.transportType())
						&& boundClients.contains(c.name()));
			if (!siblingHasHandler) {
				continue;
			}
			findings.add(new DesyncFinding(DesyncType.ORPHAN_CLIENT, clientName, "any", null,
					"Client '" + clientName + "' (transport: " + config.transportType()
							+ ") is configured but has no @Mcp* handler bound to it, while a sibling client"
							+ " of the same transport type does. Did you forget a handler?"));
		}
		return findings;
	}

	/**
	 * Detector 3: transport-type mismatch in the client configuration. This catches both
	 * a wrong property for the transport family (e.g. stdio connection with a URL) and a
	 * conflicting extra property (e.g. SSE connection with both {@code url} and
	 * {@code command}).
	 * @param clientConfigs the configured clients
	 * @return findings for transport mismatches
	 */
	static List<DesyncFinding> detectTransportMismatches(
			final Map<String, ClientConfigReader.ClientConfig> clientConfigs) {
		final List<DesyncFinding> findings = new ArrayList<>();
		for (final ClientConfigReader.ClientConfig config : clientConfigs.values()) {
			final String transport = config.transportType();
			final String url = config.url();
			final String command = config.command();
			if (url == null && command == null) {
				continue;
			}
			if ("stdio".equals(transport)) {
				if (url != null) {
					findings.add(new DesyncFinding(DesyncType.TRANSPORT_MISMATCH, config.name(), null, url,
							"Client '" + config.name() + "' is configured as stdio but has a URL property: " + url
									+ ". Did you mean sse or streamable-http?"));
				}
				if (command != null && looksLikeUrl(command)) {
					findings.add(new DesyncFinding(DesyncType.TRANSPORT_MISMATCH, config.name(), null, command,
							"Client '" + config.name() + "' is configured as stdio but its command looks like a URL: "
									+ command + ". Did you mean sse or streamable-http?"));
				}
			}
			else {
				if (command != null) {
					findings.add(new DesyncFinding(DesyncType.TRANSPORT_MISMATCH, config.name(), null, command,
							"Client '" + config.name() + "' is configured as " + transport
									+ " but has a command property: " + command + ". Did you mean stdio?"));
				}
			}
			if (url != null && command != null) {
				findings.add(new DesyncFinding(DesyncType.TRANSPORT_MISMATCH, config.name(), null,
						"url=" + url + ", command=" + command,
						"Client '" + config.name() + "' has both url and command properties configured: " + "url=" + url
								+ ", command=" + command + ". Only one transport property is allowed per connection."));
			}
		}
		return findings;
	}

	/**
	 * Detector 4: the same handler kind is bound to the same client name by more than one
	 * bean/method.
	 * @param handlerBindings the scanned bindings
	 * @return findings for duplicate bindings
	 */
	static List<DesyncFinding> detectDuplicateBindings(
			final List<ClientHandlerScanner.HandlerBinding> handlerBindings) {
		final Map<String, List<ClientHandlerScanner.HandlerBinding>> byKindAndClient = new LinkedHashMap<>();
		for (final ClientHandlerScanner.HandlerBinding binding : handlerBindings) {
			final String key = binding.handlerKind() + ":" + binding.clientName();
			byKindAndClient.computeIfAbsent(key, (k) -> new ArrayList<>()).add(binding);
		}
		final List<DesyncFinding> findings = new ArrayList<>();
		for (final Map.Entry<String, List<ClientHandlerScanner.HandlerBinding>> entry : byKindAndClient.entrySet()) {
			if (entry.getValue().size() > 1) {
				final ClientHandlerScanner.HandlerBinding first = entry.getValue().get(0);
				final List<String> sources = new ArrayList<>();
				for (final ClientHandlerScanner.HandlerBinding b : entry.getValue()) {
					sources.add(b.beanName() + "#" + b.methodName() + b.methodDescriptor());
				}
				findings.add(new DesyncFinding(DesyncType.DUPLICATE_BINDING, first.clientName(), first.handlerKind(),
						String.join(", ", sources),
						"Handler kind '" + first.handlerKind() + "' is bound to client '" + first.clientName() + "' by "
								+ entry.getValue().size() + " methods: " + String.join(", ", sources)));
			}
		}
		return findings;
	}

	private static boolean looksLikeUrl(final String value) {
		return value != null && (value.startsWith("http://") || value.startsWith("https://"));
	}

	/**
	 * The four classes of silent desyncs this module detects.
	 */
	public enum DesyncType {

		/** A handler references a non-existent client name. */
		ORPHAN_HANDLER,
		/** A client has no handler bound to it. */
		ORPHAN_CLIENT,
		/** A client's transport-type and its URL/command property disagree. */
		TRANSPORT_MISMATCH,
		/** The same handler kind is bound to the same client by multiple methods. */
		DUPLICATE_BINDING

	}

	/**
	 * Immutable record describing a single desync finding.
	 *
	 * @param type the kind of desync
	 * @param clientName the affected client name
	 * @param handlerKind the handler kind, or {@code null} if not handler-specific
	 * @param source a string identifying the source (bean#method, detail, etc.)
	 * @param message a human-readable description
	 */
	public record DesyncFinding(DesyncType type, String clientName, String handlerKind, String source, String message) {
	}

}
