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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import io.inspector.mcp.core.timeline.ClientHandlerScanner.HandlerBinding;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientHandlerScanner}.
 *
 * @author Artem Simeshin
 */
class ClientHandlerScannerTests {

	private ClientHandlerScanner scanner;

	@BeforeEach
	void setUp() {
		this.scanner = new ClientHandlerScanner();
	}

	@Nested
	@DisplayName("scanHandlers")
	class ScanHandlers {

		@Test
		@DisplayName("finds @McpSampling handler with explicit client name")
		void findsSamplingHandler() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			context.registerBean("handlerBean", SamplingHandlerBean.class);
			context.refresh();
			ClientHandlerScannerTests.this.scanner.setApplicationContext(context);

			// when
			final List<HandlerBinding> bindings = ClientHandlerScannerTests.this.scanner.scanHandlers();

			// then
			assertThat(bindings).hasSize(1);
			final HandlerBinding binding = bindings.get(0);
			assertThat(binding.handlerKind()).isEqualTo("sampling");
			assertThat(binding.clientName()).isEqualTo("clientA");
			assertThat(binding.beanName()).isEqualTo("handlerBean");
			assertThat(binding.methodName()).isEqualTo("onSample");
		}

		@Test
		@DisplayName("finds handler with wildcard clients (empty array)")
		void findsWildcardHandler() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			context.registerBean("wildcardBean", WildcardHandlerBean.class);
			context.refresh();
			ClientHandlerScannerTests.this.scanner.setApplicationContext(context);

			// when
			final List<HandlerBinding> bindings = ClientHandlerScannerTests.this.scanner.scanHandlers();

			// then
			assertThat(bindings).hasSize(1);
			assertThat(bindings.get(0).clientName()).isEqualTo(ClientHandlerScanner.ALL_CLIENTS);
		}

		@Test
		@DisplayName("finds multiple handlers on same bean")
		void findsMultipleHandlers() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			context.registerBean("multiBean", MultiHandlerBean.class);
			context.refresh();
			ClientHandlerScannerTests.this.scanner.setApplicationContext(context);

			// when
			final List<HandlerBinding> bindings = ClientHandlerScannerTests.this.scanner.scanHandlers();

			// then
			assertThat(bindings).hasSize(2);
			assertThat(bindings).anyMatch((b) -> "sampling".equals(b.handlerKind()));
			assertThat(bindings).anyMatch((b) -> "logging".equals(b.handlerKind()));
		}

		@Test
		@DisplayName("returns empty when context has no handler beans")
		void returnsEmptyWhenNoHandlers() {
			// given
			final StaticApplicationContext context = new StaticApplicationContext();
			context.refresh();
			ClientHandlerScannerTests.this.scanner.setApplicationContext(context);

			// when
			final List<HandlerBinding> bindings = ClientHandlerScannerTests.this.scanner.scanHandlers();

			// then
			assertThat(bindings).isEmpty();
		}

		@Test
		@DisplayName("returns empty when beanFactory is null")
		void returnsEmptyWhenBeanFactoryNull() {
			// given
			ClientHandlerScannerTests.this.scanner.setApplicationContext(null);

			// when
			final List<HandlerBinding> bindings = ClientHandlerScannerTests.this.scanner.scanHandlers();

			// then
			assertThat(bindings).isEmpty();
		}

	}

	@Nested
	@DisplayName("static helpers")
	class StaticHelpers {

		@Test
		@DisplayName("groupByClient groups by client name")
		void groupByClientWorks() {
			// given
			final List<HandlerBinding> bindings = List.of(
					new HandlerBinding("sampling", "c1", "b1", "Foo", "m1", "fqcn1"),
					new HandlerBinding("logging", "c1", "b2", "Bar", "m2", "fqcn2"),
					new HandlerBinding("progress", "c2", "b3", "Baz", "m3", "fqcn3"));

			// when
			final Map<String, List<HandlerBinding>> grouped = ClientHandlerScanner.groupByClient(bindings);

			// then
			assertThat(grouped).hasSize(2);
			assertThat(grouped.get("c1")).hasSize(2);
			assertThat(grouped.get("c2")).hasSize(1);
		}

		@Test
		@DisplayName("explicitClientNames excludes wildcard")
		void explicitClientNamesExcludesWildcard() {
			// given
			final List<HandlerBinding> bindings = List.of(
					new HandlerBinding("sampling", "c1", "b1", "Foo", "m1", "fqcn1"),
					new HandlerBinding("logging", ClientHandlerScanner.ALL_CLIENTS, "b2", "Bar", "m2", "fqcn2"));

			// when
			final Set<String> names = ClientHandlerScanner.explicitClientNames(bindings);

			// then
			assertThat(names).containsExactly("c1");
		}

	}

	/** Test fixture: a bean with a @McpSampling handler targeting clientA. */
	@SuppressWarnings("unused")
	static class SamplingHandlerBean {

		@org.springframework.ai.mcp.annotation.McpSampling(clients = "clientA")
		String onSample(final Object request) {
			return "result";
		}

	}

	/** Test fixture: a bean with a @McpSampling handler targeting all clients. */
	@SuppressWarnings("unused")
	static class WildcardHandlerBean {

		@org.springframework.ai.mcp.annotation.McpSampling(clients = {})
		String onSample(final Object request) {
			return "result";
		}

	}

	/** Test fixture: a bean with both sampling and logging handlers. */
	@SuppressWarnings("unused")
	static class MultiHandlerBean {

		@org.springframework.ai.mcp.annotation.McpSampling(clients = "c1")
		String onSample(final Object request) {
			return "result";
		}

		@org.springframework.ai.mcp.annotation.McpLogging(clients = "c1")
		void onLog(final Object notification) {
		}

	}

}
