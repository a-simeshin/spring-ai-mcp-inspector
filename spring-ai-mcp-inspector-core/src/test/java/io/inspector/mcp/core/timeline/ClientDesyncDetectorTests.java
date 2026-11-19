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
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncFinding;
import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncType;
import io.inspector.mcp.core.timeline.ClientHandlerScanner.HandlerBinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link ClientDesyncDetector}: orphan handler, unnamed handler and orphan
 * client detection plus the WARN + timeline event emission with
 * {@code endpoint=client-diagnostics}.
 *
 * @author Artem Simeshin
 */
class ClientDesyncDetectorTests {

	private final RecordingTimelineService timeline = new RecordingTimelineService();

	private static HandlerBinding binding(final String kind, final String clientName, final String beanName,
			final String methodName) {
		return new HandlerBinding(kind, clientName, beanName, beanName + ".Type", methodName);
	}

	private static ClientDesyncDetector detectorOver(final ClientHandlerScanner scanner,
			final ClientConnectionRegistry registry, final TimelineService timelineService) {
		return new ClientDesyncDetector(scanner, registry, timelineService);
	}

	@Test
	@DisplayName("constructor rejects null collaborators")
	void constructorRejectsNulls() {
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		final ClientConnectionRegistry registry = new ClientConnectionRegistry();
		final RecordingTimelineService service = new RecordingTimelineService();
		assertThatIllegalArgumentException().isThrownBy(() -> detectorOver(null, registry, service));
		assertThatIllegalArgumentException().isThrownBy(() -> detectorOver(scanner, null, service));
		assertThatIllegalArgumentException().isThrownBy(() -> detectorOver(scanner, registry, null));
	}

	@Test
	@DisplayName("a handler naming an unconfigured client is an orphan handler")
	void orphanHandlerFound() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "weather", "weatherHandler", "sample"),
				binding("logging", "typo", "typoHandler", "log"));
		final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanHandlers(bindings,
				java.util.Set.of("weather"));
		assertThat(findings).hasSize(1);
		final DesyncFinding finding = findings.get(0);
		assertThat(finding.type()).isEqualTo(DesyncType.ORPHAN_HANDLER);
		assertThat(finding.clientName()).isEqualTo("typo");
		assertThat(finding.handlerKind()).isEqualTo("logging");
		assertThat(finding.source()).isEqualTo("typoHandler#log");
		assertThat(finding.message()).contains("typo").doesNotContain("://");
	}

	@Test
	@DisplayName("an annotation naming no client is its own desync finding")
	void emptyClientsHandlerIsItsOwnFinding() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "", "wild", "sample"));
		final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanHandlers(bindings, java.util.Set.of());
		assertThat(findings).hasSize(1);
		final DesyncFinding finding = findings.get(0);
		assertThat(finding.type()).isEqualTo(DesyncType.EMPTY_CLIENTS);
		assertThat(finding.clientName()).isNull();
		assertThat(finding.handlerKind()).isEqualTo("sampling");
		assertThat(finding.source()).isEqualTo("wild#sample");
		assertThat(finding.message()).contains("clients must not be empty");
	}

	@Test
	@DisplayName("a configured client without a handler beside a bound sibling is an orphan client")
	void orphanClientFound() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "weather", "weatherHandler", "sample"));
		final Map<String, String> configured = Map.of("weather", "streamable-http", "maps", "streamable-http", "files",
				"stdio");
		final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, configured);
		assertThat(findings).hasSize(1);
		final DesyncFinding finding = findings.get(0);
		assertThat(finding.type()).isEqualTo(DesyncType.ORPHAN_CLIENT);
		assertThat(finding.clientName()).isEqualTo("maps");
		assertThat(finding.handlerKind()).isNull();
		assertThat(finding.source()).isNull();
		assertThat(finding.message()).contains("maps").contains("streamable-http");
	}

	@Test
	@DisplayName("a client whose only sibling is of another transport family is not an orphan")
	void differentTransportSiblingNotFlagged() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "weather", "weatherHandler", "sample"));
		final Map<String, String> configured = Map.of("weather", "streamable-http", "files", "stdio");
		assertThat(ClientDesyncDetector.detectOrphanClients(bindings, configured)).isEmpty();
	}

	@Test
	@DisplayName("a binding naming no client does not cover any configured client")
	void emptyClientsBindingDoesNotSuppressOrphanClients() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "", "wild", "sample"),
				binding("logging", "weather", "weatherHandler", "log"));
		final Map<String, String> configured = Map.of("weather", "streamable-http", "maps", "streamable-http");
		final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, configured);
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).type()).isEqualTo(DesyncType.ORPHAN_CLIENT);
		assertThat(findings.get(0).clientName()).isEqualTo("maps");
	}

	@Test
	@DisplayName("no bindings means no orphan clients")
	void noBindingsNoFindings() {
		assertThat(ClientDesyncDetector.detectOrphanClients(List.of(), Map.of("weather", "sse"))).isEmpty();
	}

	@Test
	@DisplayName("every configured client with a binding is not an orphan")
	void boundClientsNotFlagged() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "weather", "weatherHandler", "sample"),
				binding("progress", "maps", "mapsHandler", "progress"));
		final Map<String, String> configured = Map.of("weather", "sse", "maps", "sse");
		assertThat(ClientDesyncDetector.detectOrphanClients(bindings, configured)).isEmpty();
	}

	@Test
	@DisplayName("detect combines both finding types")
	void detectCombinesBothTypes() {
		final List<HandlerBinding> bindings = List.of(binding("sampling", "weather", "weatherHandler", "sample"),
				binding("logging", "typo", "typoHandler", "log"));
		final Map<String, String> configured = Map.of("weather", "streamable-http", "maps", "streamable-http");
		final List<DesyncFinding> findings = ClientDesyncDetector.detect(bindings, configured);
		assertThat(findings).extracting(DesyncFinding::type)
			.containsExactlyInAnyOrder(DesyncType.ORPHAN_CLIENT, DesyncType.ORPHAN_HANDLER);
	}

	@Test
	@DisplayName("emission logs WARN and appends a timeline event per finding")
	void emissionLogsWarnAndAppendsEvents() {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				DetectorTestConfig.class);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		final ClientConnectionRegistry registry = new ClientConnectionRegistry();
		registry.recordDiscovered("weather", "streamable-http");
		registry.recordDiscovered("maps", "streamable-http");
		final ClientDesyncDetector detector = detectorOver(scanner, registry, this.timeline);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		final Logger logger = (Logger) LoggerFactory.getLogger(ClientDesyncDetector.class);
		logger.addAppender(appender);
		try {
			detector.afterSingletonsInstantiated();
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		assertThat(this.timeline.events).hasSize(1);
		final TimelineEvent event = this.timeline.events.get(0);
		assertThat(event.type()).isEqualTo(TimelineEventType.APP_LOG);
		assertThat(event.correlationId()).startsWith("mcpcd:ORPHAN_CLIENT:maps");
		final JsonNode payload = event.payload();
		final String serialized = payload.toString();
		assertThat(payload.get("endpoint").asString()).isEqualTo("client-diagnostics");
		assertThat(payload.get("desyncType").asString()).isEqualTo("ORPHAN_CLIENT");
		assertThat(payload.get("clientName").asString()).isEqualTo("maps");
		assertThat(payload.get("message")).isNotNull();
		// No raw targets or credentials may leak into diagnostics payloads.
		assertThat(serialized).doesNotContain("://").doesNotContain("?");
		assertThat(appender.list).anyMatch((loggingEvent) -> loggingEvent.getLevel() == Level.WARN
				&& loggingEvent.getFormattedMessage().contains("ORPHAN_CLIENT"));
	}

	@Test
	@DisplayName("a clean setup logs no WARN and appends no events")
	void cleanStateEmitsNothing() {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				DetectorTestConfig.class);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		final ClientConnectionRegistry registry = new ClientConnectionRegistry();
		registry.recordDiscovered("weather", "streamable-http");
		registry.recordDiscovered("other", "stdio");
		final ClientDesyncDetector detector = detectorOver(scanner, registry, this.timeline);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		final Logger logger = (Logger) LoggerFactory.getLogger(ClientDesyncDetector.class);
		logger.addAppender(appender);
		try {
			detector.afterSingletonsInstantiated();
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		assertThat(this.timeline.events).isEmpty();
		assertThat(appender.list).noneMatch((loggingEvent) -> loggingEvent.getLevel() == Level.WARN);
	}

	@Test
	@DisplayName("an annotation naming no client is emitted through the pipeline")
	void emptyClientsEmittedThroughPipeline() {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				EmptyClientsDetectorConfig.class);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		final ClientConnectionRegistry registry = new ClientConnectionRegistry();
		registry.recordDiscovered("weather", "streamable-http");
		registry.recordDiscovered("maps", "streamable-http");
		final ClientDesyncDetector detector = detectorOver(scanner, registry, this.timeline);
		detector.afterSingletonsInstantiated();
		assertThat(this.timeline.events).extracting(TimelineEvent::correlationId)
			.containsExactlyInAnyOrder("mcpcd:EMPTY_CLIENTS", "mcpcd:ORPHAN_CLIENT:maps");
	}

	@Test
	@DisplayName("a failing scanner is contained without touching the timeline")
	void scannerFailureIsContained() {
		final ClientHandlerScanner failing = new ClientHandlerScanner() {

			@Override
			public List<HandlerBinding> scan() {
				throw new IllegalStateException("boom");
			}
		};
		final ClientDesyncDetector detector = detectorOver(failing, new ClientConnectionRegistry(), this.timeline);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		final Logger logger = (Logger) LoggerFactory.getLogger(ClientDesyncDetector.class);
		logger.addAppender(appender);
		try {
			detector.afterSingletonsInstantiated();
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		assertThat(this.timeline.events).isEmpty();
		assertThat(appender.list).anyMatch((loggingEvent) -> loggingEvent.getLevel() == Level.WARN
				&& loggingEvent.getFormattedMessage().contains("diagnostics failed"));
	}

	@Configuration
	static class DetectorTestConfig {

		@Bean
		DetectorTestHandler weatherHandler() {
			return new DetectorTestHandler();
		}

	}

	static class DetectorTestHandler {

		@McpSampling(clients = "weather")
		String sample(final String request) {
			return request;
		}

	}

	@Configuration
	static class EmptyClientsDetectorConfig {

		@Bean
		DetectorTestHandler weatherHandler() {
			return new DetectorTestHandler();
		}

		@Bean
		EmptyClientsDetectorHandler unnamedHandler() {
			return new EmptyClientsDetectorHandler();
		}

	}

	static class EmptyClientsDetectorHandler {

		@McpSampling(clients = {})
		String sample(final String request) {
			return request;
		}

	}

	/**
	 * Test double capturing appended events in order.
	 */
	static final class RecordingTimelineService implements TimelineService {

		private final List<TimelineEvent> events = new ArrayList<>();

		@Override
		public void append(final TimelineEvent event) {
			this.events.add(event);
		}

		@Override
		public List<TimelineEvent> query(final TimelineQuery query) {
			return List.of();
		}

		@Override
		public void clear() {
			this.events.clear();
		}

	}

}
