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

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@code NamedClientMcpTransport} bean with a
 * {@link RecordingMcpClientTransport} decorator so that client-side MCP traffic is
 * captured into the timeline and connection state is tracked in the
 * {@link ClientConnectionRegistry}.
 *
 * <p>
 * The class {@code NamedClientMcpTransport} (from
 * {@code spring-ai-autoconfigure-mcp-client-common}) is a record holding a name and a
 * {@link McpClientTransport}. Core does not depend on that jar at compile time, so this
 * post-processor detects the bean by class name at runtime and extracts the name and
 * transport via record component accessors. When the class is absent from the classpath,
 * no bean matches and the post-processor is a no-op.
 *
 * <p>
 * In Spring AI 2.0, every transport is exposed as an element of a
 * {@code List<NamedClientMcpTransport>} bean rather than individual records. This
 * post-processor handles list beans by iterating over their elements and wrapping each
 * matching entry.
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

	private final ObjectProvider<McpClientTrafficRecorder> trafficRecorderProvider;

	private final ObjectProvider<ClientConnectionRegistry> connectionRegistryProvider;

	private volatile McpClientTrafficRecorder trafficRecorder;

	private volatile ClientConnectionRegistry connectionRegistry;

	/**
	 * Creates a new post-processor.
	 * @param trafficRecorderProvider the provider for the recorder to pass to wrapping
	 * transports (must not be {@code null})
	 * @param connectionRegistryProvider the provider for the connection registry to pass
	 * to wrapping transports (must not be {@code null})
	 */
	public RecordingTransportPostProcessor(final ObjectProvider<McpClientTrafficRecorder> trafficRecorderProvider,
			final ObjectProvider<ClientConnectionRegistry> connectionRegistryProvider) {
		if (trafficRecorderProvider == null) {
			throw new IllegalArgumentException("trafficRecorderProvider must not be null");
		}
		if (connectionRegistryProvider == null) {
			throw new IllegalArgumentException("connectionRegistryProvider must not be null");
		}
		this.trafficRecorderProvider = trafficRecorderProvider;
		this.connectionRegistryProvider = connectionRegistryProvider;
	}

	@Override
	public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
		if (bean == null) {
			return null;
		}
		// Handle List<NamedClientMcpTransport> (Spring AI 2.0 bean shape)
		if (bean instanceof List<?> list) {
			return wrapListBean(list);
		}
		// Handle individual NamedClientMcpTransport record
		if (isNamedClientMcpTransport(bean)) {
			try {
				return wrapBean(bean);
			}
			catch (final ReflectiveOperationException ex) {
				LOG.warn("Failed to wrap NamedClientMcpTransport bean '{}': {}", beanName, ex.getMessage());
				return bean;
			}
		}
		return bean;
	}

	private Object wrapListBean(final List<?> list) {
		final List<Object> wrapped = new ArrayList<>(list.size());
		boolean changed = false;
		for (final Object element : list) {
			if (isNamedClientMcpTransport(element)) {
				try {
					// Guard against double wrapping: skip if the transport is already a
					// RecordingMcpClientTransport
					final Object existingTransport = invokeAccessor(element, "transport");
					if (existingTransport instanceof RecordingMcpClientTransport) {
						wrapped.add(element);
						continue;
					}
					wrapped.add(wrapBean(element));
					changed = true;
				}
				catch (final ReflectiveOperationException ex) {
					LOG.warn("Failed to wrap NamedClientMcpTransport in list: {}", ex.getMessage());
					wrapped.add(element);
				}
			}
			else {
				wrapped.add(element);
			}
		}
		return changed ? wrapped : list;
	}

	private boolean isNamedClientMcpTransport(final Object bean) {
		return bean != null && NAMED_CLIENT_TRANSPORT_FQCN.equals(bean.getClass().getName());
	}

	private Object wrapBean(final Object bean) throws ReflectiveOperationException {
		final String clientName = (String) invokeAccessor(bean, "name");
		final McpClientTransport delegate = (McpClientTransport) invokeAccessor(bean, "transport");
		// Guard against double wrapping: skip if the transport is already a
		// RecordingMcpClientTransport (mirrors the list path).
		if (delegate instanceof RecordingMcpClientTransport) {
			return bean;
		}
		final McpClientTrafficRecorder recorder = resolveRecorder();
		final ClientConnectionRegistry registry = resolveRegistry();
		if (recorder == null || registry == null) {
			LOG.warn("McpClientTrafficRecorder or ClientConnectionRegistry not available; skipping wrap of {}",
					NAMED_CLIENT_TRANSPORT_FQCN);
			return bean;
		}
		final String transportType = detectTransportType(delegate);
		final String target = resolveTarget(delegate);
		final RecordingMcpClientTransport recordingTransport = new RecordingMcpClientTransport(delegate,
				new McpClientTrafficRecorder.ClientEndpoint(clientName, transportType, target), recorder, registry);
		return newNamedClientMcpTransport(clientName, recordingTransport);
	}

	private McpClientTrafficRecorder resolveRecorder() {
		McpClientTrafficRecorder result = this.trafficRecorder;
		if (result == null) {
			result = this.trafficRecorderProvider.getIfAvailable();
			if (result != null) {
				this.trafficRecorder = result;
			}
		}
		return result;
	}

	private ClientConnectionRegistry resolveRegistry() {
		ClientConnectionRegistry result = this.connectionRegistry;
		if (result == null) {
			result = this.connectionRegistryProvider.getIfAvailable();
			if (result != null) {
				this.connectionRegistry = result;
			}
		}
		return result;
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

	/**
	 * Best-effort extraction of the connection target from the delegate transport. The
	 * SDK transports carry the target in a private field without a public accessor, so
	 * the field is read reflectively; when neither shape matches, {@code null} is
	 * returned and the payload omits the target.
	 * @param transport the delegate transport, may be {@code null}
	 * @return the sanitized target, or {@code null} when not resolvable
	 */
	private String resolveTarget(final McpClientTransport transport) {
		if (transport == null) {
			return null;
		}
		// HttpClientSseClientTransport and HttpClientStreamableHttpTransport carry a
		// private final URI baseUri field.
		final Object baseUri = readField(transport, "baseUri");
		if (baseUri instanceof URI uri) {
			return sanitizeTarget(uri.toString());
		}
		// StdioClientTransport carries a private final ServerParameters params field.
		final Object params = readField(transport, "params");
		if (params instanceof ServerParameters serverParameters) {
			return sanitizeTarget(serverParameters.getCommand());
		}
		return null;
	}

	private Object readField(final Object target, final String fieldName) {
		try {
			final Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(target);
		}
		catch (final ReflectiveOperationException | IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Sanitizes a target for the timeline payload: userinfo credentials are replaced with
	 * {@code ***} and the query string is dropped entirely; host and path are kept. Stdio
	 * command strings pass through unchanged unless they embed a query.
	 * @param rawTarget the raw target, may be {@code null}
	 * @return the sanitized target, or {@code null} when the input was {@code null}
	 */
	static String sanitizeTarget(final String rawTarget) {
		if (rawTarget == null || rawTarget.isEmpty()) {
			return rawTarget;
		}
		String sanitized = rawTarget;
		final int queryIndex = sanitized.indexOf('?');
		if (queryIndex >= 0) {
			sanitized = sanitized.substring(0, queryIndex);
		}
		final int schemeIndex = sanitized.indexOf("://");
		if (schemeIndex >= 0) {
			final int pathIndex = sanitized.indexOf('/', schemeIndex + 3);
			final int authorityEnd = (pathIndex >= 0) ? pathIndex : sanitized.length();
			final int atIndex = sanitized.indexOf('@', schemeIndex + 3);
			if (atIndex >= 0 && atIndex < authorityEnd) {
				sanitized = sanitized.substring(0, schemeIndex + 3) + "***" + sanitized.substring(atIndex);
			}
		}
		return sanitized;
	}

}
