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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimelineAutoConfiguration}.
 *
 * @author Artem Simeshin
 */
class TimelineAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TimelineAutoConfiguration.class));

	@Nested
	@DisplayName("timeline.enabled=true")
	class Enabled {

		@Test
		@DisplayName("creates TimelineService and McpTrafficRecorder beans")
		void createsServiceAndRecorder() {
			TimelineAutoConfigurationTests.this.runner
				.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=true",
						"spring.ai.mcp.inspector.timeline.logs-enabled=false",
						"spring.ai.mcp.inspector.timeline.stdio-capture-enabled=false")
				.run((context) -> {
					assertThat(context).hasSingleBean(TimelineService.class);
					assertThat(context).hasSingleBean(McpTrafficRecorder.class);
					assertThat(context.getBean(McpTrafficRecorder.class)).isNotNull();
				});
		}

		@Test
		@DisplayName("no BeanPostProcessorChecker WARN when client-capture-enabled=true")
		void noBeanPostProcessorCheckerWarn() {
			final ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
			rootLogger.addAppender(appender);
			try {
				TimelineAutoConfigurationTests.this.runner
					.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=true",
							"spring.ai.mcp.inspector.timeline.client-capture-enabled=true",
							"spring.ai.mcp.inspector.timeline.logs-enabled=false",
							"spring.ai.mcp.inspector.timeline.stdio-capture-enabled=false")
					.run((context) -> {
						assertThat(context).hasSingleBean(McpClientTrafficRecorder.class);
						assertThat(context).hasSingleBean(RecordingTransportPostProcessor.class);
						// The BPP must not trigger eager instantiation of the recorder
						// (and its dependencies) during registerBeanPostProcessors.
						// Spring Framework's BeanPostProcessorChecker logs a WARN on
						// the root logger when a bean is created early because a
						// BeanPostProcessor needs it; capturing the root logger and
						// asserting no such WARN is the regression guard.
						final List<ILoggingEvent> warnOrHigher = appender.list.stream()
							.filter((event) -> event.getLevel().isGreaterOrEqual(Level.WARN))
							.toList();
						assertThat(warnOrHigher)
							.noneMatch((event) -> event.getFormattedMessage().contains("BeanPostProcessorChecker"));
						assertThat(warnOrHigher).noneMatch((event) -> event.getFormattedMessage()
							.contains("mcpInspectorRecordingTransportPostProcessor"));
					});
			}
			finally {
				rootLogger.detachAppender(appender);
				appender.stop();
			}
		}

	}

	@Nested
	@DisplayName("timeline disabled")
	class Disabled {

		@Test
		@DisplayName("creates no timeline beans when the flag is unset")
		void noBeansWhenUnset() {
			TimelineAutoConfigurationTests.this.runner
				.run((context) -> assertThat(context).doesNotHaveBean(McpTrafficRecorder.class)
					.doesNotHaveBean(TimelineService.class));
		}

		@Test
		@DisplayName("creates no timeline beans when the flag is false")
		void noBeansWhenFalse() {
			TimelineAutoConfigurationTests.this.runner
				.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=false")
				.run((context) -> assertThat(context).doesNotHaveBean(McpTrafficRecorder.class)
					.doesNotHaveBean(TimelineService.class));
		}

	}

}
