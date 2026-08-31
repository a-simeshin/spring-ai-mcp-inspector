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

import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@code NamedClientMcpTransport} bean with a
 * {@link RecordingMcpClientTransport} decorator so that client-side MCP traffic is
 * captured into the timeline.
 *
 * <p>
 * The class {@code NamedClientMcpTransport} (from
 * {@code spring-ai-autoconfigure-mcp-client-common}) is a record holding a name and a
 * {@link McpClientTransport}. Core does not depend on that jar at compile time, so this
 * post-processor detects the bean by class name at runtime and extracts the name and
 * transport via record component accessors. When the class is absent from the classpath,
 * no bean matches and the post-processor is a no-op.
 *
 * @author Artem Simeshin
 */
public final class RecordingTransportPostProcessor implements BeanPostProcessor {

	private static final Logger LOG = LoggerFactory.getLogger(RecordingTransportPostProcessor.class);

	/**
	 * FQCN of {@code NamedClientMcpTransport} from
	 * spring-ai-autoconfigure-mcp-client-common.
	 */
	static final String NAMED_CLIENT_TRANSPORT_FQCN = "org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport";

	private final McpClientTrafficRecorder trafficRecorder;

	/**
	 * Creates a new post-processor.
	 * @param trafficRecorder the recorder to pass to wrapping transports (must not be
	 * {@code null})
	 */
	public RecordingTransportPostProcessor(final McpClientTrafficRecorder trafficRecorder) {
		if (trafficRecorder == null) {
			throw new IllegalArgumentException("trafficRecorder must not be null");
		}
		this.trafficRecorder = trafficRecorder;
	}

	@Override
	public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
		if (!isNamedClientMcpTransport(bean)) {
			return bean;
		}
		try {
			return wrapBean(bean);
		}
		catch (final ReflectiveOperationException ex) {
			LOG.warn("Failed to wrap NamedClientMcpTransport bean '{}': {}", beanName, ex.getMessage());
			return bean;
		}
	}

	private boolean isNamedClientMcpTransport(final Object bean) {
		return bean != null && NAMED_CLIENT_TRANSPORT_FQCN.equals(bean.getClass().getName());
	}

	private Object wrapBean(final Object bean) throws ReflectiveOperationException {
		final String clientName = (String) invokeAccessor(bean, "name");
		final McpClientTransport delegate = (McpClientTransport) invokeAccessor(bean, "transport");
		final String transportType = detectTransportType(delegate);
		final RecordingMcpClientTransport recordingTransport = new RecordingMcpClientTransport(delegate, clientName,
				transportType, this.trafficRecorder);
		return newNamedClientMcpTransport(clientName, recordingTransport);
	}

	private Object invokeAccessor(final Object bean, final String accessorName) throws ReflectiveOperationException {
		return bean.getClass().getMethod(accessorName).invoke(bean);
	}

	private Object newNamedClientMcpTransport(final String clientName, final McpClientTransport transport)
			throws ReflectiveOperationException {
		final Class<?> clazz = Class.forName(NAMED_CLIENT_TRANSPORT_FQCN);
		return clazz.getDeclaredConstructor(String.class, McpClientTransport.class).newInstance(clientName, transport);
	}

	private String detectTransportType(final McpClientTransport transport) {
		if (transport == null) {
			return "unknown";
		}
		final String className = transport.getClass().getSimpleName();
		if (className.contains("Stdio")) {
			return "stdio";
		}
		if (className.contains("Sse") || className.contains("SSE")) {
			return "sse";
		}
		if (className.contains("Streamable") || className.contains("Http")) {
			return "streamable-http";
		}
		return className;
	}

}
