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

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link RecordingTransportPostProcessor}: record and List bean wrapping,
 * double-wrap guards, lazy recorder resolution and target sanitization.
 *
 * @author Artem Simeshin
 */
class RecordingTransportPostProcessorTests {

	private final RecordingTimelineService timeline = new RecordingTimelineService();

	private final McpClientTrafficRecorder recorder = new McpClientTrafficRecorder(this.timeline);

	private final ClientConnectionRegistry registry = new ClientConnectionRegistry();

	private final StubProvider<McpClientTrafficRecorder> recorderProvider = new StubProvider<>(this.recorder);

	private final StubProvider<ClientConnectionRegistry> registryProvider = new StubProvider<>(this.registry);

	private final RecordingTransportPostProcessor processor = new RecordingTransportPostProcessor(this.recorderProvider,
			this.registryProvider);

	@Test
	@DisplayName("constructor rejects null providers")
	void constructorRejectsNulls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingTransportPostProcessor(null, this.registryProvider));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new RecordingTransportPostProcessor(this.recorderProvider, null));
	}

	@Test
	@DisplayName("wraps a NamedClientMcpTransport record and keeps name and transport type")
	void wrapsRecordBean() {
		final NamedClientMcpTransport bean = new NamedClientMcpTransport("weather",
				new SseUriTargetTransport("https://user:pass@host/path?token=x"));
		final Object result = this.processor.postProcessAfterInitialization(bean, "sseTransports");
		assertThat(result).isInstanceOf(NamedClientMcpTransport.class);
		final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) result;
		assertThat(wrapped.name()).isEqualTo("weather");
		assertThat(wrapped.transport()).isInstanceOf(RecordingMcpClientTransport.class);
		final RecordingMcpClientTransport decorated = (RecordingMcpClientTransport) wrapped.transport();
		assertThat(decorated.endpoint().transportType()).isEqualTo("sse");
		assertThat(decorated.endpoint().target()).isEqualTo("https://***@host/path");
		assertThat(decorated.delegate()).isSameAs(bean.transport());
	}

	@Test
	@DisplayName("wraps every matching element of a List bean and keeps foreign elements")
	void wrapsListBean() {
		final NamedClientMcpTransport stdio = new NamedClientMcpTransport("local",
				new StdioParamsTransport(ServerParameters.builder("npx").build()));
		final NamedClientMcpTransport sse = new NamedClientMcpTransport("remote",
				new SseUriTargetTransport("https://api.example/sse"));
		final List<Object> bean = List.of(stdio, sse, "foreign");
		final Object result = this.processor.postProcessAfterInitialization(bean, "transports");
		assertThat(result).isInstanceOf(List.class);
		final List<?> wrapped = (List<?>) result;
		assertThat(wrapped).hasSize(3);
		assertThat(wrapped.get(0)).isInstanceOf(NamedClientMcpTransport.class);
		assertThat(((NamedClientMcpTransport) wrapped.get(0)).transport())
			.isInstanceOf(RecordingMcpClientTransport.class);
		assertThat(((NamedClientMcpTransport) wrapped.get(1)).transport())
			.isInstanceOf(RecordingMcpClientTransport.class);
		assertThat(wrapped.get(2)).isEqualTo("foreign");
		final RecordingMcpClientTransport stdioDecorated = (RecordingMcpClientTransport) ((NamedClientMcpTransport) wrapped
			.get(0)).transport();
		assertThat(stdioDecorated.endpoint().transportType()).isEqualTo("stdio");
		assertThat(stdioDecorated.endpoint().target()).isEqualTo("npx");
	}

	@Test
	@DisplayName("an already wrapped record is returned unchanged (no double wrap)")
	void doubleWrapRecordGuard() {
		final NamedClientMcpTransport already = new NamedClientMcpTransport("weather",
				new RecordingMcpClientTransport(new PlainTransport(),
						new McpClientTrafficRecorder.ClientEndpoint("weather", "sse", null), this.recorder,
						this.registry));
		assertThat(this.processor.postProcessAfterInitialization(already, "bean")).isSameAs(already);
	}

	@Test
	@DisplayName("a list with only already wrapped elements is returned unchanged")
	void doubleWrapListGuard() {
		final NamedClientMcpTransport already = new NamedClientMcpTransport("weather",
				new RecordingMcpClientTransport(new PlainTransport(),
						new McpClientTrafficRecorder.ClientEndpoint("weather", "sse", null), this.recorder,
						this.registry));
		final List<Object> bean = List.of(already);
		assertThat(this.processor.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("a list without matching elements is returned unchanged")
	void listWithoutMatchesReturnedUnchanged() {
		final List<Object> bean = List.of("a", "b");
		assertThat(this.processor.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("beans that are not NamedClientMcpTransport are returned unchanged")
	void nonMatchingBeanUntouched() {
		final String bean = "plain";
		assertThat(this.processor.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("a null bean is passed through")
	void nullBeanPassedThrough() {
		assertThat(this.processor.postProcessAfterInitialization(null, "bean")).isNull();
	}

	@Test
	@DisplayName("missing recorder skips the wrap")
	void missingRecorderSkipsWrap() {
		final RecordingTransportPostProcessor noRecorder = new RecordingTransportPostProcessor(new StubProvider<>(null),
				this.registryProvider);
		final NamedClientMcpTransport bean = new NamedClientMcpTransport("weather", new PlainTransport());
		assertThat(noRecorder.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("missing registry skips the wrap")
	void missingRegistrySkipsWrap() {
		final RecordingTransportPostProcessor noRegistry = new RecordingTransportPostProcessor(this.recorderProvider,
				new StubProvider<>(null));
		final NamedClientMcpTransport bean = new NamedClientMcpTransport("weather", new PlainTransport());
		assertThat(noRegistry.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
	}

	@Test
	@DisplayName("streamable-http transport type is detected from the class name")
	void streamableTypeDetected() {
		final NamedClientMcpTransport bean = new NamedClientMcpTransport("weather", new StreamableHttpLikeTransport());
		final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) this.processor
			.postProcessAfterInitialization(bean, "bean");
		assertThat(((RecordingMcpClientTransport) wrapped.transport()).endpoint().transportType())
			.isEqualTo("streamable-http");
	}

	@Test
	@DisplayName("an unknown transport class name is kept as the transport type")
	void unknownClassNameKeptAsType() {
		final NamedClientMcpTransport bean = new NamedClientMcpTransport("weather", new PlainTransport());
		final NamedClientMcpTransport wrapped = (NamedClientMcpTransport) this.processor
			.postProcessAfterInitialization(bean, "bean");
		assertThat(((RecordingMcpClientTransport) wrapped.transport()).endpoint().transportType())
			.isEqualTo("PlainTransport");
	}

	@Test
	@DisplayName("the recorder provider is consulted once and then cached")
	void recorderResolvedLazilyOnce() {
		final NamedClientMcpTransport first = new NamedClientMcpTransport("a", new PlainTransport());
		final NamedClientMcpTransport second = new NamedClientMcpTransport("b", new PlainTransport());
		this.processor.postProcessAfterInitialization(first, "bean-a");
		this.processor.postProcessAfterInitialization(second, "bean-b");
		assertThat(this.recorderProvider.availabilityChecks).isEqualTo(1);
		assertThat(this.registryProvider.availabilityChecks).isEqualTo(1);
	}

	@Test
	@DisplayName("sanitizeTarget strips userinfo and drops the query")
	void sanitizeTargetStripsCredentialsAndQuery() {
		assertThat(RecordingTransportPostProcessor.sanitizeTarget("https://user:pass@host/path?token=x"))
			.isEqualTo("https://***@host/path");
		assertThat(RecordingTransportPostProcessor.sanitizeTarget("https://api.example/sse?session=1"))
			.isEqualTo("https://api.example/sse");
		assertThat(RecordingTransportPostProcessor.sanitizeTarget("npx -y mcp-server")).isEqualTo("npx -y mcp-server");
		assertThat(RecordingTransportPostProcessor.sanitizeTarget("https://user@host/path"))
			.isEqualTo("https://***@host/path");
		assertThat(RecordingTransportPostProcessor.sanitizeTarget(null)).isNull();
		assertThat(RecordingTransportPostProcessor.sanitizeTarget("")).isEmpty();
	}

	/**
	 * Minimal {@link ObjectProvider} stub with an availability-check counter.
	 */
	private static final class StubProvider<T> implements ObjectProvider<T> {

		private final T value;

		private int availabilityChecks;

		StubProvider(final T value) {
			this.value = value;
		}

		@Override
		public T getObject() {
			return this.value;
		}

		@Override
		public T getIfAvailable() {
			this.availabilityChecks++;
			return this.value;
		}

		@Override
		public T getIfUnique() {
			return this.value;
		}

		@Override
		public Iterator<T> iterator() {
			return List.of(this.value).iterator();
		}

		@Override
		public Stream<T> stream() {
			return List.of(this.value).stream();
		}

	}

	/**
	 * Test transport without target fields (unknown class name branch).
	 */
	static class PlainTransport implements McpClientTransport {

		@Override
		public Mono<Void> connect(
				final java.util.function.Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendMessage(final JSONRPCMessage message) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(final Object source, final TypeRef<T> typeRef) {
			return null;
		}

	}

	/**
	 * Test transport named like SSE carrying a private URI baseUri field.
	 */
	static class SseUriTargetTransport extends PlainTransport {

		@SuppressWarnings("unused")
		private final URI baseUri;

		SseUriTargetTransport(final String uri) {
			this.baseUri = URI.create(uri);
		}

	}

	/**
	 * Test transport named like stdio carrying a private ServerParameters params field.
	 */
	static class StdioParamsTransport extends PlainTransport {

		@SuppressWarnings("unused")
		private final ServerParameters params;

		StdioParamsTransport(final ServerParameters params) {
			this.params = params;
		}

	}

	/**
	 * Test transport named like streamable-http without target fields.
	 */
	static class StreamableHttpLikeTransport extends PlainTransport {

	}

	/**
	 * Test double capturing appended events in order.
	 */
	private static final class RecordingTimelineService implements TimelineService {

		private final List<TimelineEvent> events = new java.util.ArrayList<>();

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
