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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Scans the application context for beans carrying the method-level {@code @McpSampling},
 * {@code @McpElicitation}, {@code @McpLogging} and {@code @McpProgress} handler
 * annotations of Spring AI 2.0 and builds the mapping from
 * {@code (clientName, handlerKind)} to the bean methods consuming that exchange.
 *
 * <p>
 * The scan replicates what the Spring AI client annotation registry does at startup: the
 * registry itself ({@code ClientMcpSyncHandlersRegistry}) is a concrete class wired
 * directly into the client specifications and cannot be wrapped, so correlation is
 * derived from the same annotations instead.
 *
 * <p>
 * The annotations live in {@code org.springframework.ai.mcp.annotation} and are absent
 * from core's compile classpath by design; detection is by FQCN through
 * {@link MergedAnnotations}, reading bean types without instantiating beans. An empty
 * {@code clients()} array names no client and binds none: the annotation is kept as a
 * binding with an empty client name so the desync detector can report it (Spring AI
 * rejects an empty {@code clients()} at startup). An annotation without a readable
 * {@code clients} attribute is skipped instead of failing the scan.
 *
 * <p>
 * The scan result is computed once and cached; the first scan may run while the context
 * is still refreshing (client events can arrive as soon as a client connects), which is
 * safe because only bean types are read.
 *
 * @author Artem Simeshin
 */
public class ClientHandlerScanner implements ApplicationContextAware {

	private static final Logger LOG = LoggerFactory.getLogger(ClientHandlerScanner.class);

	/** FQCN of {@code @McpSampling} from spring-ai-mcp-annotations. */
	static final String MCP_SAMPLING_FQCN = "org.springframework.ai.mcp.annotation.McpSampling";

	/** FQCN of {@code @McpElicitation} from spring-ai-mcp-annotations. */
	static final String MCP_ELICITATION_FQCN = "org.springframework.ai.mcp.annotation.McpElicitation";

	/** FQCN of {@code @McpLogging} from spring-ai-mcp-annotations. */
	static final String MCP_LOGGING_FQCN = "org.springframework.ai.mcp.annotation.McpLogging";

	/** FQCN of {@code @McpProgress} from spring-ai-mcp-annotations. */
	static final String MCP_PROGRESS_FQCN = "org.springframework.ai.mcp.annotation.McpProgress";

	private volatile ConfigurableListableBeanFactory beanFactory;

	private volatile ScanResult scanResult;

	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		if (applicationContext != null && applicationContext
			.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory) {
			this.beanFactory = factory;
			this.scanResult = null;
		}
	}

	/**
	 * Scans all singleton beans for method-level {@code @Mcp*} handler annotations and
	 * collects the discovered bindings. The result is cached; repeated calls return the
	 * bindings of the first scan.
	 * @return an immutable list of handler bindings (never {@code null})
	 */
	public List<HandlerBinding> scan() {
		return result().bindings();
	}

	/**
	 * Returns the {@code beanName#method} sources consuming the given exchange for the
	 * given client: bindings naming that client explicitly. Sorted and free of
	 * duplicates.
	 * @param clientName the configured client name (must not be {@code null})
	 * @param handlerKind one of {@code sampling}, {@code elicitation}, {@code logging},
	 * {@code progress} (must not be {@code null})
	 * @return the consuming sources (never {@code null}), empty when nothing matches
	 */
	public List<String> consumedBy(final String clientName, final String handlerKind) {
		final Map<String, Set<String>> byKey = result().byKey();
		final Set<String> sources = new TreeSet<>();
		final Set<String> exact = byKey.get(bindingKey(handlerKind, clientName));
		if (exact != null) {
			sources.addAll(exact);
		}
		return List.copyOf(sources);
	}

	/**
	 * Returns the distinct client names explicitly targeted by scanned bindings; blank
	 * client names (annotations naming no client) are skipped.
	 * @return the explicitly named clients (never {@code null})
	 */
	public Set<String> explicitClientNames() {
		final Set<String> names = new LinkedHashSet<>();
		for (final HandlerBinding binding : result().bindings()) {
			if (StringUtils.hasText(binding.clientName())) {
				names.add(binding.clientName());
			}
		}
		return names;
	}

	private ScanResult result() {
		final ScanResult existing = this.scanResult;
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			if (this.scanResult == null) {
				this.scanResult = buildResult();
			}
			return this.scanResult;
		}
	}

	private ScanResult buildResult() {
		final ConfigurableListableBeanFactory factory = this.beanFactory;
		if (factory == null) {
			return ScanResult.empty();
		}
		final Set<HandlerBinding> bindings = new LinkedHashSet<>();
		final Map<String, Set<String>> byKey = new LinkedHashMap<>();
		for (final String beanName : factory.getBeanDefinitionNames()) {
			if (!isSingletonBean(factory, beanName)) {
				continue;
			}
			final Class<?> beanType;
			try {
				beanType = factory.getType(beanName, false);
			}
			catch (final RuntimeException ex) {
				continue;
			}
			if (beanType == null) {
				continue;
			}
			// Unwrap JDK proxies so annotations on the target class methods are not
			// lost: getType returns the proxy interface for JDK proxies. At
			// afterSingletonsInstantiated time getBean returns the existing instance
			// without re-initialization; on any failure fall back to the raw type.
			final Class<?> targetType = resolveTargetClass(factory, beanName, beanType);
			scanBeanMethods(beanName, targetType, bindings, byKey);
		}
		LOG.debug("Client handler scan found {} binding(s) across {} bean(s)", bindings.size(),
				factory.getBeanDefinitionNames().length);
		return new ScanResult(List.copyOf(bindings), Map.copyOf(byKey));
	}

	private Class<?> resolveTargetClass(final ConfigurableListableBeanFactory factory, final String beanName,
			final Class<?> beanType) {
		if (!Proxy.isProxyClass(beanType)) {
			return beanType;
		}
		try {
			final Object bean = factory.getBean(beanName);
			return AopProxyUtils.ultimateTargetClass(bean);
		}
		catch (final RuntimeException ex) {
			return beanType;
		}
	}

	private static boolean isSingletonBean(final ConfigurableListableBeanFactory beanFactory, final String beanName) {
		try {
			return beanFactory.getBeanDefinition(beanName).isSingleton();
		}
		catch (final NoSuchBeanDefinitionException ex) {
			return false;
		}
	}

	private void scanBeanMethods(final String beanName, final Class<?> beanClass, final Set<HandlerBinding> bindings,
			final Map<String, Set<String>> byKey) {
		for (final Method method : ReflectionUtils.getAllDeclaredMethods(beanClass)) {
			final MergedAnnotations annotations = MergedAnnotations.from(method,
					MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
			collectBinding(beanName, beanClass, method, annotations, MCP_SAMPLING_FQCN, "sampling", bindings, byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_ELICITATION_FQCN, "elicitation", bindings,
					byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_LOGGING_FQCN, "logging", bindings, byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_PROGRESS_FQCN, "progress", bindings, byKey);
		}
	}

	private void collectBinding(final String beanName, final Class<?> beanClass, final Method method,
			final MergedAnnotations annotations, final String annotationFqcn, final String handlerKind,
			final Set<HandlerBinding> bindings, final Map<String, Set<String>> byKey) {
		final MergedAnnotation<?> annotation = annotations.get(annotationFqcn);
		if (!annotation.isPresent()) {
			return;
		}
		// Guarded lookup: an annotation without a readable clients attribute is
		// skipped instead of failing the whole scan (review finding on the draft
		// diagnostics PR).
		final String[] clients = annotation.getValue("clients", String[].class).orElse(new String[0]);
		final Set<String> clientNames = new LinkedHashSet<>();
		for (final String client : clients) {
			if (StringUtils.hasText(client)) {
				clientNames.add(client.trim());
			}
		}
		// An annotation naming no client (empty clients()) is kept with an empty
		// client name: Spring AI rejects such an annotation at startup, and the
		// desync detector reports it as its own finding.
		if (clientNames.isEmpty()) {
			clientNames.add("");
		}
		final String beanClassName = ClassUtils.getUserClass(beanClass).getName();
		for (final String clientName : clientNames) {
			final HandlerBinding binding = new HandlerBinding(handlerKind, clientName, beanName, beanClassName,
					method.getName());
			if (bindings.add(binding)) {
				byKey.computeIfAbsent(bindingKey(handlerKind, clientName), (key) -> new TreeSet<>())
					.add(binding.source());
			}
		}
	}

	private static String bindingKey(final String handlerKind, final String clientName) {
		return handlerKind + ":" + clientName;
	}

	/**
	 * Immutable record describing a single handler-to-client binding discovered by
	 * scanning an {@code @Mcp*} annotation.
	 *
	 * @param handlerKind one of {@code sampling}, {@code elicitation}, {@code logging},
	 * {@code progress}
	 * @param clientName the target client name; empty when the annotation names no client
	 * (its {@code clients()} attribute was empty)
	 * @param beanName the Spring bean name carrying the annotated method
	 * @param beanClassName the user class of the bean
	 * @param methodName the annotated method name
	 */
	public record HandlerBinding(String handlerKind, String clientName, String beanName, String beanClassName,
			String methodName) {

		/**
		 * Returns the {@code beanName#method} identity used in {@code consumedBy}
		 * payloads and diagnostics.
		 * @return the source identity (never {@code null})
		 */
		public String source() {
			return this.beanName + "#" + this.methodName;
		}
	}

	/**
	 * Immutable result of one scan: the ordered binding list and the lookup index from
	 * {@code kind:clientName} to consuming sources.
	 */
	private record ScanResult(List<HandlerBinding> bindings, Map<String, Set<String>> byKey) {

		static ScanResult empty() {
			return new ScanResult(List.of(), Map.of());
		}
	}

}
