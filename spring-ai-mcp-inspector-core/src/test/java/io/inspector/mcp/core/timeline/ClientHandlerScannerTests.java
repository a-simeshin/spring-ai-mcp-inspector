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
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientHandlerScanner}: discovery of all four {@code @Mcp*}
 * handler kinds, multi-client {@code clients()} arrays, wildcard bindings for empty
 * arrays, beans without annotations and the {@code consumedBy} lookup.
 *
 * @author Artem Simeshin
 */
class ClientHandlerScannerTests {

	private static ClientHandlerScanner scannerOver(final Class<?>... configClasses) {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(configClasses);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		return scanner;
	}

	@Test
	@DisplayName("discovers all four handler kinds on one bean")
	void discoversAllFourKinds() {
		final ClientHandlerScanner scanner = scannerOver(AllKindsConfig.class);
		final List<ClientHandlerScanner.HandlerBinding> bindings = scanner.scan();
		assertThat(bindings).hasSize(5);
		assertThat(bindings).extracting(ClientHandlerScanner.HandlerBinding::handlerKind)
			.containsExactlyInAnyOrder("sampling", "elicitation", "logging", "logging", "progress");
		assertThat(bindings).allMatch((binding) -> binding.beanName().equals("allKindsHandler"));
		assertThat(bindings).extracting(ClientHandlerScanner.HandlerBinding::source)
			.contains("allKindsHandler#sample", "allKindsHandler#elicit", "allKindsHandler#log",
					"allKindsHandler#progress");
	}

	@Test
	@DisplayName("a bean without handler annotations contributes no bindings")
	void plainBeanContributesNothing() {
		final ClientHandlerScanner scanner = scannerOver(AllKindsConfig.class);
		assertThat(scanner.scan()).noneMatch((binding) -> binding.beanName().equals("plainHandler"));
		assertThat(scanner.explicitClientNames()).containsExactlyInAnyOrder("weather", "maps");
	}

	@Test
	@DisplayName("a multi-client clients() array binds every listed client and trims blanks")
	void multiClientArrayBindsEveryClient() {
		final ClientHandlerScanner scanner = scannerOver(MultiClientConfig.class);
		final List<ClientHandlerScanner.HandlerBinding> bindings = scanner.scan();
		assertThat(bindings).hasSize(2);
		assertThat(bindings).extracting(ClientHandlerScanner.HandlerBinding::clientName)
			.containsExactlyInAnyOrder("weather", "maps");
		assertThat(scanner.explicitClientNames()).containsExactlyInAnyOrder("weather", "maps");
		assertThat(scanner.consumedBy("maps", "sampling")).containsExactly("multiClientHandler#sample");
		assertThat(scanner.consumedBy("weather", "sampling")).containsExactly("multiClientHandler#sample");
	}

	@Test
	@DisplayName("an empty clients() array is a wildcard binding for all clients")
	void emptyClientsIsWildcard() {
		final ClientHandlerScanner scanner = scannerOver(WildcardConfig.class);
		assertThat(scanner.hasWildcardBinding()).isTrue();
		assertThat(scanner.explicitClientNames()).containsExactly("weather");
		assertThat(scanner.consumedBy("any-client", "sampling")).containsExactly("wildcardHandler#sample");
		assertThat(scanner.consumedBy("weather", "sampling")).containsExactly("wildcardHandler#sample");
		assertThat(scanner.consumedBy("weather", "logging")).containsExactly("wildcardHandler#log");
		assertThat(scanner.consumedBy("weather", "progress")).isEmpty();
	}

	@Test
	@DisplayName("consumedBy merges exact and wildcard bindings, sorted and distinct")
	void consumedByMergesExactAndWildcard() {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(MergeConfig.class);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		assertThat(scanner.consumedBy("weather", "sampling")).containsExactly("exactHandler#sample",
				"wildHandler#sample");
		assertThat(scanner.consumedBy("maps", "sampling")).containsExactly("wildHandler#sample");
		assertThat(scanner.consumedBy("weather", "elicitation")).isEmpty();
	}

	@Test
	@DisplayName("the scan result is cached across calls")
	void scanIsCached() {
		final ClientHandlerScanner scanner = scannerOver(AllKindsConfig.class);
		assertThat(scanner.scan()).isSameAs(scanner.scan());
	}

	@Test
	@DisplayName("a scanner without a context returns no bindings")
	void noContextReturnsNothing() {
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		assertThat(scanner.scan()).isEmpty();
		assertThat(scanner.consumedBy("weather", "sampling")).isEmpty();
		assertThat(scanner.explicitClientNames()).isEmpty();
		assertThat(scanner.hasWildcardBinding()).isFalse();
	}

	@Test
	@DisplayName("non-singleton bean definitions are skipped")
	void prototypeBeansAreSkipped() {
		final ClientHandlerScanner scanner = scannerOver(PrototypeConfig.class);
		assertThat(scanner.scan()).isEmpty();
	}

	@Test
	@DisplayName("beans whose type resolution fails or stays unresolved are skipped without failing the scan")
	void unresolvableBeanTypeIsSkipped() {
		final ConfigurableListableBeanFactory beanFactory = Mockito.mock(ConfigurableListableBeanFactory.class);
		Mockito.when(beanFactory.getBeanDefinitionNames())
			.thenReturn(new String[] { "broken", "mystery", "allKindsHandler" });
		final BeanDefinition brokenDefinition = Mockito.mock(BeanDefinition.class);
		Mockito.when(brokenDefinition.isSingleton()).thenReturn(true);
		Mockito.when(beanFactory.getBeanDefinition("broken")).thenReturn(brokenDefinition);
		Mockito.when(beanFactory.getType("broken", false)).thenThrow(new IllegalStateException("unresolvable"));
		final BeanDefinition mysteryDefinition = Mockito.mock(BeanDefinition.class);
		Mockito.when(mysteryDefinition.isSingleton()).thenReturn(true);
		Mockito.when(beanFactory.getBeanDefinition("mystery")).thenReturn(mysteryDefinition);
		Mockito.when(beanFactory.getType("mystery", false)).thenReturn(null);
		final BeanDefinition handlerDefinition = Mockito.mock(BeanDefinition.class);
		Mockito.when(handlerDefinition.isSingleton()).thenReturn(true);
		Mockito.when(beanFactory.getBeanDefinition("allKindsHandler")).thenReturn(handlerDefinition);
		Mockito.when(beanFactory.getType("allKindsHandler", false)).thenReturn((Class) AllKindsHandler.class);
		final ApplicationContext context = Mockito.mock(ApplicationContext.class);
		Mockito.when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(context);
		final List<ClientHandlerScanner.HandlerBinding> bindings = scanner.scan();
		assertThat(bindings).hasSize(5);
		assertThat(bindings).allMatch((binding) -> binding.beanClassName().equals(AllKindsHandler.class.getName()));
	}

	@Test
	@DisplayName("a non-autowire-capable context leaves the scanner inert")
	void plainContextLeavesScannerInert() {
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(null);
		assertThat(scanner.scan()).isEmpty();
	}

	@Test
	@DisplayName("setApplicationContext resets a previous scan result")
	void resetContextRebuildsIndex() {
		final AnnotationConfigApplicationContext first = new AnnotationConfigApplicationContext(AllKindsConfig.class);
		final ClientHandlerScanner scanner = new ClientHandlerScanner();
		scanner.setApplicationContext(first);
		assertThat(scanner.scan()).hasSize(5);
		final AnnotationConfigApplicationContext second = new AnnotationConfigApplicationContext(
				MultiClientConfig.class);
		scanner.setApplicationContext(second);
		assertThat(scanner.scan()).hasSize(2);
	}

	@Test
	@DisplayName("bindings carry the user class name and method name")
	void bindingsCarryClassAndMethod() {
		final ClientHandlerScanner scanner = scannerOver(AllKindsConfig.class);
		final ClientHandlerScanner.HandlerBinding binding = scanner.scan().get(0);
		assertThat(binding.beanClassName()).isEqualTo(AllKindsHandler.class.getName());
		assertThat(binding.methodName()).isNotBlank();
		assertThat(Set.copyOf(scanner.scan())).hasSameSizeAs(scanner.scan());
	}

	@Configuration
	static class AllKindsConfig {

		@Bean
		AllKindsHandler allKindsHandler() {
			return new AllKindsHandler();
		}

		@Bean
		PlainHandler plainHandler() {
			return new PlainHandler();
		}

	}

	static class AllKindsHandler {

		@McpSampling(clients = "weather")
		String sample(final String request) {
			return request;
		}

		@McpElicitation(clients = "weather")
		String elicit(final String request) {
			return request;
		}

		@McpLogging(clients = { "weather", "maps" })
		void log(final String notification) {
		}

		@McpProgress(clients = "weather")
		void progress(final String notification) {
		}

	}

	static class PlainHandler {

		void nothingAnnotated() {
		}

	}

	@Configuration
	static class MultiClientConfig {

		@Bean
		MultiClientHandler multiClientHandler() {
			return new MultiClientHandler();
		}

	}

	static class MultiClientHandler {

		@McpSampling(clients = { "weather", "maps", " " })
		String sample(final String request) {
			return request;
		}

	}

	@Configuration
	static class WildcardConfig {

		@Bean
		WildcardHandler wildcardHandler() {
			return new WildcardHandler();
		}

	}

	static class WildcardHandler {

		@McpSampling(clients = {})
		String sample(final String request) {
			return request;
		}

		@McpLogging(clients = "weather")
		void log(final String notification) {
		}

	}

	@Configuration
	static class PrototypeConfig {

		@Bean
		@Scope("prototype")
		AllKindsHandler prototypeHandler() {
			return new AllKindsHandler();
		}

	}

	@Configuration
	static class MergeConfig {

		@Bean
		MergeExactHandler exactHandler() {
			return new MergeExactHandler();
		}

		@Bean
		MergeWildcardHandler wildHandler() {
			return new MergeWildcardHandler();
		}

	}

	static class MergeExactHandler {

		@McpSampling(clients = "weather")
		String sample(final String request) {
			return request;
		}

	}

	static class MergeWildcardHandler {

		@McpSampling(clients = {})
		String sample(final String request) {
			return request;
		}

	}

}
