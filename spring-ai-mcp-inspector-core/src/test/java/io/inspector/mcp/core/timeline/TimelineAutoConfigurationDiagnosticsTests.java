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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link TimelineAutoConfiguration}, verifying the
 * conditional bean creation of {@link ClientDiagnosticsRecorder} based on the
 * {@code client-capture-enabled} property.
 *
 * <p>
 * The conditional chain is: <pre>
 * {@code client-capture-enabled=true} -> {@code McpClientTrafficRecorder} created ->
 * {@code @ConditionalOnBean(McpClientTrafficRecorder.class)} triggers ->
 * {@code ClientDiagnosticsRecorder} created.
 * </pre>
 *
 * @author Artem Simeshin
 */
class TimelineAutoConfigurationDiagnosticsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TimelineAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=true");

	@Test
	@DisplayName("ClientDiagnosticsRecorder is absent when client-capture-enabled is not set")
	void diagnosticsRecorderAbsentByDefault() {
		this.runner.run((ctx) -> assertThat(ctx.getBeansOfType(ClientDiagnosticsRecorder.class)).isEmpty());
	}

	@Test
	@DisplayName("ClientDiagnosticsRecorder is absent when client-capture-enabled is false")
	void diagnosticsRecorderAbsentWhenDisabled() {
		this.runner.withPropertyValues("spring.ai.mcp.inspector.timeline.client-capture-enabled=false")
			.run((ctx) -> assertThat(ctx.getBeansOfType(ClientDiagnosticsRecorder.class)).isEmpty());
	}

	@Test
	@DisplayName("ClientDiagnosticsRecorder is present when client-capture-enabled is true")
	void diagnosticsRecorderPresentWhenEnabled() {
		this.runner.withPropertyValues("spring.ai.mcp.inspector.timeline.client-capture-enabled=true")
			.run((ctx) -> assertThat(ctx.getBeansOfType(ClientDiagnosticsRecorder.class)).hasSize(1));
	}

}
