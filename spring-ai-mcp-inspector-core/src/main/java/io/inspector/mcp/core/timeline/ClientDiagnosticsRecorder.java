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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the handler-client correlation diagnostics at application startup and emits the
 * results as structured diagnostic events into the timeline recorder and as WARN-level
 * log messages.
 *
 * <p>
 * On {@link SmartInitializingSingleton#afterSingletonsInstantiated()} (after all
 * singleton beans, including {@code @Mcp*} handler beans, are created) this component:
 * <ol>
 * <li>scans the context for {@code @Mcp*} handler bindings via
 * {@link ClientHandlerScanner};</li>
 * <li>reads the configured MCP clients via {@link ClientConfigReader};</li>
 * <li>runs {@link ClientDesyncDetector#detect} to find silent desyncs;</li>
 * <li>emits one {@link TimelineEvent} per finding with payload
 * {@code endpoint=client-diagnostics} into the {@link TimelineService};</li>
 * <li>logs each finding at WARN level.</li>
 * </ol>
 *
 * <p>
 * The diagnostic is accessible through the existing timeline query API
 * ({@link TimelineService#query}), so the UI can surface it without changes to the query
 * contract.
 *
 * @author Artem Simeshin
 */
public final class ClientDiagnosticsRecorder implements ApplicationContextAware, SmartInitializingSingleton {

	private static final Logger LOG = LoggerFactory.getLogger(ClientDiagnosticsRecorder.class);

	private final TimelineService timelineService;

	private ApplicationContext applicationContext;

	/**
	 * Creates a new diagnostics recorder.
	 * @param timelineService the timeline to append diagnostic events to (must not be
	 * {@code null})
	 */
	public ClientDiagnosticsRecorder(final TimelineService timelineService) {
		if (timelineService == null) {
			throw new IllegalArgumentException("timelineService must not be null");
		}
		this.timelineService = timelineService;
	}

	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (this.applicationContext == null) {
			return;
		}
		try {
			runDiagnostics();
		}
		catch (final RuntimeException ex) {
			LOG.warn("Client handler diagnostics failed: {}", ex.getMessage(), ex);
		}
	}

	/**
	 * Runs the diagnostics, emits events, logs warnings. Exposed for testing.
	 * @return the list of findings (never {@code null})
	 */
	List<ClientDesyncDetector.DesyncFinding> runDiagnostics() {
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(this.applicationContext);
		final List<ClientHandlerScanner.HandlerBinding> bindings = scanner.scanHandlers();
		final Environment environment = this.applicationContext.getEnvironment();
		final ClientConfigReader reader = new ClientConfigReader(environment);
		final Map<String, ClientConfigReader.ClientConfig> clients = reader.readClients();
		final List<ClientDesyncDetector.DesyncFinding> findings = ClientDesyncDetector.detect(bindings, clients);
		for (final ClientDesyncDetector.DesyncFinding finding : findings) {
			emitFinding(finding);
		}
		if (findings.isEmpty()) {
			LOG.info("MCP client handler diagnostics: no desyncs detected ({} clients, {} handlers)", clients.size(),
					bindings.size());
		}
		else {
			LOG.warn("MCP client handler diagnostics: {} desync(s) detected ({} clients, {} handlers)", findings.size(),
					clients.size(), bindings.size());
		}
		return findings;
	}

	private void emitFinding(final ClientDesyncDetector.DesyncFinding finding) {
		LOG.warn("MCP client desync [{}]: {}", finding.type(), finding.message());
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("endpoint", "client-diagnostics");
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
		final TimelineEvent event = new TimelineEvent(UUID.randomUUID().toString(),
				"mcpcd:" + finding.type().name() + ":" + finding.clientName(), null, TimelineEventType.APP_LOG,
				Instant.now(), payload);
		this.timelineService.append(event);
	}

}
