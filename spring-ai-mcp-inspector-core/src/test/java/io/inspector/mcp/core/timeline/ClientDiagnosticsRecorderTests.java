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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncFinding;
import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClientDiagnosticsRecorder}.
 *
 * @author Artem Simeshin
 */
class ClientDiagnosticsRecorderTests {

	private BoundedTimelineService timelineService;

	private ClientDiagnosticsRecorder recorder;

	@BeforeEach
	void setUp() {
		this.timelineService = new BoundedTimelineService();
		this.recorder = new ClientDiagnosticsRecorder(this.timelineService);
	}

	@Nested
	@DisplayName("constructor")
	class Constructor {

		@Test
		@DisplayName("rejects null TimelineService")
		void rejectsNullTimelineService() {
			assertThatThrownBy(() -> new ClientDiagnosticsRecorder(null)).isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	@DisplayName("runDiagnostics")
	class RunDiagnostics {

		@Test
		@DisplayName("emits no findings when context is empty")
		void noFindingsWhenEmpty() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			context.refresh();
			ClientDiagnosticsRecorderTests.this.recorder.setApplicationContext(context);

			// when
			final List<DesyncFinding> findings = ClientDiagnosticsRecorderTests.this.recorder.runDiagnostics();

			// then
			assertThat(findings).isEmpty();
			assertThat(ClientDiagnosticsRecorderTests.this.timelineService.query(TimelineQuery.all())).isEmpty();
		}

		@Test
		@DisplayName("emits orphan handler finding for typo in clients()")
		void emitsOrphanHandlerFinding() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.stdio.connections.realServer.command", "echo");
			context.setEnvironment(env);
			context.registerBean("handlerBean", HandlerWithTypo.class);
			context.refresh();
			ClientDiagnosticsRecorderTests.this.recorder.setApplicationContext(context);

			// when
			final List<DesyncFinding> findings = ClientDiagnosticsRecorderTests.this.recorder.runDiagnostics();

			// then
			assertThat(findings).isNotEmpty();
			assertThat(findings).anyMatch((f) -> f.type() == DesyncType.ORPHAN_HANDLER);
			final List<TimelineEvent> events = ClientDiagnosticsRecorderTests.this.timelineService
				.query(TimelineQuery.all());
			assertThat(events).isNotEmpty();
			assertThat(events).allSatisfy((e) -> {
				assertThat(e.type()).isEqualTo(TimelineEventType.APP_LOG);
				assertThat(e.payload().path("endpoint").asText()).isEqualTo("client-diagnostics");
			});
		}

		@Test
		@DisplayName("emits transport mismatch finding")
		void emitsTransportMismatchFinding() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			final MockEnvironment env = new MockEnvironment();
			env.setProperty("spring.ai.mcp.client.stdio.connections.badClient.command", "https://should-be-sse.com");
			context.setEnvironment(env);
			context.refresh();
			ClientDiagnosticsRecorderTests.this.recorder.setApplicationContext(context);

			// when
			final List<DesyncFinding> findings = ClientDiagnosticsRecorderTests.this.recorder.runDiagnostics();

			// then
			assertThat(findings).anyMatch((f) -> f.type() == DesyncType.TRANSPORT_MISMATCH);
		}

		@Test
		@DisplayName("does not throw when context is null")
		void doesNotThrowWhenContextNull() {
			// given
			ClientDiagnosticsRecorderTests.this.recorder.setApplicationContext(null);

			// when
			ClientDiagnosticsRecorderTests.this.recorder.afterSingletonsInstantiated();

			// then - no exception thrown
		}

	}

	/** Test fixture: a bean with a handler that references a non-existent client name. */
	@SuppressWarnings("unused")
	static class HandlerWithTypo {

		@org.springframework.ai.mcp.annotation.McpSampling(clients = "typo-server")
		String onSample(final Object request) {
			return "result";
		}

	}

}
