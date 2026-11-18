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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApplicationContextRunner coverage with the real Spring AI 2.0 client transport
 * auto-configurations: all three {@code List<NamedClientMcpTransport>} beans must come
 * out wrapped when client capture is on, and untouched when it is off.
 *
 * @author Artem Simeshin
 */
class ClientCaptureTransportAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(StdioTransportAutoConfiguration.class, SseHttpClientTransportAutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class, TimelineAutoConfiguration.class));

	@Test
	@DisplayName("all three Spring AI transport list beans are wrapped when capture is enabled")
	void allThreeTransportListBeansWrapped() {
		this.runner
			.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=true",
					"spring.ai.mcp.inspector.timeline.client-capture-enabled=true",
					"spring.ai.mcp.inspector.timeline.logs-enabled=false",
					"spring.ai.mcp.inspector.timeline.stdio-capture-enabled=false", "spring.ai.mcp.client.enabled=true",
					"spring.ai.mcp.client.stdio.enabled=true",
					"spring.ai.mcp.client.stdio.connections.demo.command=/bin/echo",
					"spring.ai.mcp.client.sse.enabled=true",
					"spring.ai.mcp.client.sse.connections.demo.url=http://localhost:9911",
					"spring.ai.mcp.client.streamable-http.enabled=true",
					"spring.ai.mcp.client.streamable-http.connections.demo.url=http://localhost:9912")
			.run((context) -> {
				assertThat(context).hasSingleBean(RecordingTransportPostProcessor.class);
				assertAllWrapped(context.getBean("stdioTransports", List.class), "stdio");
				assertAllWrapped(context.getBean("sseHttpClientTransports", List.class), "sse");
				assertAllWrapped(context.getBean("streamableHttpHttpClientTransports", List.class), "streamable-http");
			});
	}

	@Test
	@DisplayName("transport list beans stay untouched when client capture is disabled")
	void transportListBeansUntouchedWhenDisabled() {
		this.runner
			.withPropertyValues("spring.ai.mcp.inspector.timeline.enabled=true",
					"spring.ai.mcp.inspector.timeline.logs-enabled=false", "spring.ai.mcp.client.enabled=true",
					"spring.ai.mcp.client.stdio.enabled=true",
					"spring.ai.mcp.client.stdio.connections.demo.command=/bin/echo",
					"spring.ai.mcp.client.sse.enabled=true",
					"spring.ai.mcp.client.sse.connections.demo.url=http://localhost:9911",
					"spring.ai.mcp.client.streamable-http.enabled=true",
					"spring.ai.mcp.client.streamable-http.connections.demo.url=http://localhost:9912")
			.run((context) -> {
				assertThat(context).doesNotHaveBean(RecordingTransportPostProcessor.class);
				assertThat(context.getBean("stdioTransports", List.class)).isNotEmpty();
				assertThat(context.getBean("sseHttpClientTransports", List.class)).isNotEmpty();
				assertThat(context.getBean("streamableHttpHttpClientTransports", List.class)).isNotEmpty();
				assertNoneWrapped(context.getBean("stdioTransports", List.class));
				assertNoneWrapped(context.getBean("sseHttpClientTransports", List.class));
				assertNoneWrapped(context.getBean("streamableHttpHttpClientTransports", List.class));
			});
	}

	private static void assertAllWrapped(final List<?> transports, final String expectedType) {
		assertThat(transports).isNotEmpty();
		for (final Object element : transports) {
			final NamedClientMcpTransport named = (NamedClientMcpTransport) element;
			assertThat(named.transport()).isInstanceOf(RecordingMcpClientTransport.class);
			assertThat(((RecordingMcpClientTransport) named.transport()).endpoint().transportType())
				.isEqualTo(expectedType);
		}
	}

	private static void assertNoneWrapped(final List<?> transports) {
		for (final Object element : transports) {
			final NamedClientMcpTransport named = (NamedClientMcpTransport) element;
			assertThat(named.transport()).isNotInstanceOf(RecordingMcpClientTransport.class);
		}
	}

}
