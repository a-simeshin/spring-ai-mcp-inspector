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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.StringUtils;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Detects silent desyncs between the {@code @Mcp*} handler beans found by
 * {@link ClientHandlerScanner} and the MCP clients configured through Spring AI, and
 * surfaces each finding as a WARN log line plus a structured timeline event with
 * {@code endpoint=client-diagnostics}.
 *
 * <p>
 * Detected desyncs:
 * <ol>
 * <li><b>Orphan handler</b>: an annotation names a client that is not configured. The
 * handler is dead: the client never binds it.</li>
 * <li><b>Unnamed handler</b>: an annotation names no client (empty {@code clients()}).
 * Spring AI rejects such an annotation at startup with {@code clients must not be
 * empty}, so the handler never binds either.</li>
 * <li><b>Orphan client</b>: a client is configured without any handler while a sibling
 * client of the same transport family has one. The unhandled client answers
 * server-initiated exchanges with {@code METHOD_NOT_FOUND}. The sibling signal expresses
 * intent: a plain client without any handler anywhere is not reported.</li>
 * </ol>
 *
 * <p>
 * The check runs once after all singletons are instantiated: the scanner sees every
 * handler bean type and the {@link ClientConnectionRegistry} carries every client whose
 * transport was wrapped at bean creation, independent of whether a connection was
 * established.
 *
 * @author Artem Simeshin
 */
public final class ClientDesyncDetector implements SmartInitializingSingleton {

	private static final Logger LOG = LoggerFactory.getLogger(ClientDesyncDetector.class);

	/** Value of the {@code endpoint} payload field on diagnostics events. */
	static final String DIAGNOSTICS_ENDPOINT = "client-diagnostics";

	/** Prefix of the correlation id of diagnostics events. */
	static final String CORRELATION_PREFIX = "mcpcd:";

	private final ClientHandlerScanner handlerScanner;

	private final ClientConnectionRegistry connectionRegistry;

	private final TimelineService timelineService;

	/**
	 * Creates a new desync detector.
	 * @param handlerScanner the scanner providing handler bindings (must not be
	 * {@code null})
	 * @param connectionRegistry the registry providing the configured clients (must not
	 * be {@code null})
	 * @param timelineService the timeline to append diagnostics events to (must not be
	 * {@code null})
	 */
	public ClientDesyncDetector(final ClientHandlerScanner handlerScanner,
			final ClientConnectionRegistry connectionRegistry, final TimelineService timelineService) {
		if (handlerScanner == null) {
			throw new IllegalArgumentException("handlerScanner must not be null");
		}
		if (connectionRegistry == null) {
			throw new IllegalArgumentException("connectionRegistry must not be null");
		}
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.handlerScanner = handlerScanner;
		this.connectionRegistry = connectionRegistry;
		this.timelineService = timelineService;
	}

	@Override
	public void afterSingletonsInstantiated() {
		try {
			final List<DesyncFinding> findings = detectAndCollect();
			for (final DesyncFinding finding : findings) {
				emitFinding(finding);
			}
			LOG.info("MCP client handler diagnostics: {} desync(s), {} handler binding(s), {} configured client(s)",
					findings.size(), this.handlerScanner.scan().size(),
					this.connectionRegistry.discoveredClients().size());
		}
		catch (final RuntimeException ex) {
			LOG.warn("MCP client handler diagnostics failed: {}", ex.toString());
		}
	}

	/**
	 * Scans handler bindings, reads the configured clients and runs both detectors.
	 * Exposed for testing.
	 * @return the list of findings (never {@code null})
	 */
	public List<DesyncFinding> detectAndCollect() {
		return detect(this.handlerScanner.scan(), this.connectionRegistry.discoveredClients());
	}

	/**
	 * Runs both detectors against the given inputs and returns the combined list of
	 * findings.
	 * @param handlerBindings the scanned {@code @Mcp*} handler bindings
	 * @param configuredClients the configured clients keyed by name with their transport
	 * type
	 * @return a list of desync findings (never {@code null})
	 */
	static List<DesyncFinding> detect(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Map<String, String> configuredClients) {
		final List<DesyncFinding> findings = new ArrayList<>();
		findings.addAll(detectOrphanHandlers(handlerBindings, configuredClients.keySet()));
		findings.addAll(detectOrphanClients(handlerBindings, configuredClients));
		return List.copyOf(findings);
	}

	static List<DesyncFinding> detectOrphanHandlers(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Set<String> configuredClientNames) {
		final List<DesyncFinding> findings = new ArrayList<>();
		for (final ClientHandlerScanner.HandlerBinding binding : handlerBindings) {
			if (!StringUtils.hasText(binding.clientName())) {
				final String message = "Handler annotation names no client (empty clients() attribute); Spring AI"
						+ " rejects such an annotation at startup with 'clients must not be empty', so the"
						+ " handler never binds";
				findings.add(new DesyncFinding(DesyncType.EMPTY_CLIENTS, null, binding.handlerKind(), binding.source(),
						message));
			}
			else if (!configuredClientNames.contains(binding.clientName())) {
				findings.add(new DesyncFinding(DesyncType.ORPHAN_HANDLER, binding.clientName(), binding.handlerKind(),
						binding.source(), "Handler references client '" + binding.clientName()
								+ "' which is not configured; the handler never fires"));
			}
		}
		return findings;
	}

	static List<DesyncFinding> detectOrphanClients(final List<ClientHandlerScanner.HandlerBinding> handlerBindings,
			final Map<String, String> configuredClients) {
		if (handlerBindings.isEmpty()) {
			return List.of();
		}
		final Set<String> boundClients = new LinkedHashSet<>();
		for (final ClientHandlerScanner.HandlerBinding binding : handlerBindings) {
			if (StringUtils.hasText(binding.clientName())) {
				boundClients.add(binding.clientName());
			}
		}
		final List<DesyncFinding> findings = new ArrayList<>();
		for (final Map.Entry<String, String> entry : configuredClients.entrySet()) {
			final String clientName = entry.getKey();
			if (boundClients.contains(clientName)) {
				continue;
			}
			final String transportType = entry.getValue();
			boolean siblingHasHandler = false;
			for (final Map.Entry<String, String> sibling : configuredClients.entrySet()) {
				if (!sibling.getKey().equals(clientName) && sibling.getValue().equals(transportType)
						&& boundClients.contains(sibling.getKey())) {
					siblingHasHandler = true;
				}
			}
			if (!siblingHasHandler) {
				continue;
			}
			findings.add(new DesyncFinding(DesyncType.ORPHAN_CLIENT, clientName, null, null,
					"Client '" + clientName + "' (transport: " + transportType
							+ ") is configured without any @Mcp* handler while a sibling client of the same transport"
							+ " type has one; server-initiated exchanges answer METHOD_NOT_FOUND"));
		}
		return findings;
	}

	private void emitFinding(final DesyncFinding finding) {
		LOG.warn("MCP client desync [{}]: {}", finding.type(), finding.message());
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("endpoint", DIAGNOSTICS_ENDPOINT);
		payload.put("desyncType", finding.type().name());
		if (finding.clientName() != null) {
			payload.put("clientName", finding.clientName());
		}
		if (finding.handlerKind() != null) {
			payload.put("handlerKind", finding.handlerKind());
		}
		if (finding.source() != null) {
			payload.put("source", finding.source());
		}
		payload.put("message", finding.message());
		final String correlationId = CORRELATION_PREFIX + finding.type().name()
				+ ((finding.clientName() != null) ? ":" + finding.clientName() : "");
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(), correlationId, null,
				TimelineEventType.APP_LOG, Instant.now(), payload);
		this.timelineService.append(event);
	}

	/** The classes of silent desyncs this detector reports. */
	public enum DesyncType {

		/** A handler references a client name that is not configured. */
		ORPHAN_HANDLER,

		/** A handler annotation names no client: its clients() attribute is empty. */
		EMPTY_CLIENTS,

		/** A configured client has no handler while a same-transport sibling has one. */
		ORPHAN_CLIENT

	}

	/**
	 * Immutable record describing a single desync finding.
	 *
	 * @param type the kind of desync
	 * @param clientName the affected client name
	 * @param handlerKind the handler kind, or {@code null} when not handler-specific
	 * @param source the {@code beanName#method} identity, or {@code null} when not
	 * handler-specific
	 * @param message a human-readable description
	 */
	public record DesyncFinding(DesyncType type, String clientName, String handlerKind, String source, String message) {
	}

}
