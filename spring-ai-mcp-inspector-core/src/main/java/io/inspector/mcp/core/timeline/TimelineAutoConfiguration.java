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

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Auto-configuration for the timeline event capturing subsystem.
 *
 * <p>
 * Registers the {@link BoundedTimelineService}, the {@link McpTrafficRecorder} consumed
 * by the proxy, the {@link TimelineAppender} (wrapped in a Logback
 * {@link AsyncAppender}), and the {@link SystemErrOutSink} when the corresponding feature
 * flags are enabled.
 *
 * <p>
 * This configuration is imported by both the WebMVC and WebFlux starter
 * auto-configuration classes.
 *
 * @author Artem Simeshin
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector.timeline", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpInspectorProperties.class)
public class TimelineAutoConfiguration {

	private final McpInspectorProperties properties;

	public TimelineAutoConfiguration(final McpInspectorProperties properties) {
		this.properties = properties;
	}

	/**
	 * The shared timeline service backed by a bounded ring buffer.
	 * @return a new {@link BoundedTimelineService}
	 */
	@Bean
	@ConditionalOnMissingBean(TimelineService.class)
	public BoundedTimelineService mcpInspectorTimelineService() {
		return new BoundedTimelineService(this.properties.getTimeline().getCapacity());
	}

	/**
	 * The shared traffic recorder wired into the proxy when the timeline is enabled and
	 * traffic recording is not switched off. Absent (proxy runs without recording) when
	 * {@code spring.ai.mcp.inspector.timeline.enabled} is unset or {@code false}, or when
	 * {@code spring.ai.mcp.inspector.timeline.traffic-enabled=false}; the proxy falls
	 * back to the no-recorder constructor in that case.
	 * @param timelineService the timeline service to append events to
	 * @return a new {@link McpTrafficRecorder}
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector.timeline", name = "traffic-enabled", havingValue = "true",
			matchIfMissing = true)
	public McpTrafficRecorder mcpInspectorMcpTrafficRecorder(final TimelineService timelineService) {
		return new McpTrafficRecorder(timelineService);
	}

	/**
	 * Registers the {@link TimelineAppender} with Logback's root logger, wrapped in an
	 * {@link AsyncAppender} for non-blocking behaviour.
	 * @param timelineService the timeline service to forward events to
	 * @return the async appender (for introspection; the appender is already registered)
	 */
	@Bean
	@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector.timeline", name = "logs-enabled", havingValue = "true",
			matchIfMissing = true)
	public AsyncAppender mcpInspectorTimelineAsyncAppender(final TimelineService timelineService) {
		final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		final McpInspectorProperties.Timeline timelineConfig = this.properties.getTimeline();

		// Create the delegate appender
		final TimelineAppender delegate = new TimelineAppender(timelineService);
		delegate.setContext(context);
		delegate.setName("timeline");
		delegate.start();

		// Wrap in the async appender
		final AsyncAppender asyncAppender = new AsyncAppender();
		asyncAppender.setContext(context);
		asyncAppender.setName("timeline-async");
		asyncAppender.addAppender(delegate);
		asyncAppender.setQueueSize(timelineConfig.getAppenderQueueSize());
		asyncAppender.setDiscardingThreshold(timelineConfig.isAppenderDiscardingPolicy() ? 0 : -1);
		asyncAppender.setNeverBlock(timelineConfig.isAppenderDiscardingPolicy());
		asyncAppender.start();

		// Attach to the root logger so all loggers are captured
		final Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
		rootLogger.addAppender(asyncAppender);

		return asyncAppender;
	}

	/**
	 * Captures {@code System.out} and {@code System.err} output that bypasses the Logback
	 * pipeline.
	 * @param timelineService the timeline service to forward events to
	 * @return the system stream sink
	 */
	@Bean
	@ConditionalOnProperty(prefix = "spring.ai.mcp.inspector.timeline", name = "stdio-capture-enabled",
			havingValue = "true", matchIfMissing = true)
	public SystemErrOutSink mcpInspectorSystemErrOutSink(final TimelineService timelineService) {
		return new SystemErrOutSink(timelineService);
	}

}
