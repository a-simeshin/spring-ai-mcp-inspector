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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Scans the {@link ApplicationContext} for beans carrying method-level
 * {@code @McpSampling}, {@code @McpElicitation}, {@code @McpLogging} or
 * {@code @McpProgress} annotations (Spring AI 2.0), extracts the target client names from
 * each annotation's {@code clients()} attribute, and reports the handler-to-client
 * bindings.
 *
 * <p>
 * The annotations live in {@code org.springframework.ai.mcp.annotation} and are absent
 * from core's compile classpath by design (R1: core depends on no spring-ai classes).
 * Detection is by FQCN string via {@link MergedAnnotations}, mirroring the approach used
 * by {@code McpBeanIntrospector} for {@code @McpTool}/{@code @McpResource}.
 *
 * <p>
 * An empty or absent {@code clients()} array means the handler applies to ALL configured
 * clients. The scanner represents that with the sentinel {@link #ALL_CLIENTS}.
 *
 * <p>
 * Method identity is overload-safe: the deduplication key includes the full method
 * descriptor (parameter types), so two valid overloads of the same name (e.g.
 * {@code handle(String)} and {@code handle(Integer)}) are both preserved.
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

	/** Sentinel for an empty {@code clients()} array: handler applies to all clients. */
	static final String ALL_CLIENTS = "*";

	private ConfigurableListableBeanFactory beanFactory;

	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		if (applicationContext != null && applicationContext
			.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory factory) {
			this.beanFactory = factory;
		}
	}

	/**
	 * Scans all singleton beans for method-level {@code @Mcp*} handler annotations and
	 * collects the discovered bindings.
	 * @return an immutable list of handler bindings (never {@code null})
	 */
	public List<HandlerBinding> scanHandlers() {
		if (this.beanFactory == null) {
			return List.of();
		}
		final Map<String, HandlerBinding> byKey = new LinkedHashMap<>();
		for (final String beanName : this.beanFactory.getBeanDefinitionNames()) {
			if (!this.beanFactory.isSingleton(beanName)) {
				continue;
			}
			final Class<?> beanType;
			try {
				beanType = this.beanFactory.getType(beanName);
			}
			catch (final RuntimeException ex) {
				continue;
			}
			if (beanType == null) {
				continue;
			}
			scanBeanMethods(beanName, beanType, byKey);
		}
		return List.copyOf(byKey.values());
	}

	private void scanBeanMethods(final String beanName, final Class<?> beanClass,
			final Map<String, HandlerBinding> byKey) {
		for (final Method method : ReflectionUtils.getAllDeclaredMethods(beanClass)) {
			final MergedAnnotations annotations = MergedAnnotations.from(method,
					MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
			collectBinding(beanName, beanClass, method, annotations, MCP_SAMPLING_FQCN, "sampling", byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_ELICITATION_FQCN, "elicitation", byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_LOGGING_FQCN, "logging", byKey);
			collectBinding(beanName, beanClass, method, annotations, MCP_PROGRESS_FQCN, "progress", byKey);
		}
	}

	private void collectBinding(final String beanName, final Class<?> beanClass, final Method method,
			final MergedAnnotations annotations, final String annotationFqcn, final String handlerKind,
			final Map<String, HandlerBinding> byKey) {
		final MergedAnnotation<?> annotation = annotations.get(annotationFqcn);
		if (!annotation.isPresent()) {
			return;
		}
		final String[] clients = annotation.getStringArray("clients");
		final Set<String> clientNames = new LinkedHashSet<>();
		if (clients == null || clients.length == 0) {
			clientNames.add(ALL_CLIENTS);
		}
		else {
			for (final String client : clients) {
				if (StringUtils.hasText(client)) {
					clientNames.add(client.trim());
				}
			}
		}
		if (clientNames.isEmpty()) {
			clientNames.add(ALL_CLIENTS);
		}
		final String descriptor = methodDescriptor(method);
		for (final String clientName : clientNames) {
			final String key = handlerKind + ":" + clientName + ":" + beanName + ":" + method.getName() + descriptor;
			byKey.putIfAbsent(key, new HandlerBinding(handlerKind, clientName, beanName,
					ClassUtils.getUserClass(beanClass).getName(), method.getName(), descriptor, annotationFqcn));
		}
	}

	/**
	 * Builds a JVM-style method descriptor that uniquely identifies overloads by
	 * parameter types: {@code (Ljava/lang/String;)Ljava/lang/String;}. This is
	 * overload-safe: two methods with the same name but different parameter types get
	 * different descriptors.
	 * @param method the method (must not be {@code null})
	 * @return the descriptor string (never {@code null})
	 */
	static String methodDescriptor(final Method method) {
		final StringBuilder sb = new StringBuilder("(");
		for (final Class<?> paramType : method.getParameterTypes()) {
			sb.append(typeDescriptor(paramType));
		}
		sb.append(")");
		sb.append(typeDescriptor(method.getReturnType()));
		return sb.toString();
	}

	private static final Map<Class<?>, String> PRIMITIVE_DESCRIPTORS = Map.of(void.class, "V", int.class, "I",
			boolean.class, "Z", byte.class, "B", char.class, "C", short.class, "S", long.class, "J", float.class, "F",
			double.class, "D");

	/**
	 * Returns the JVM descriptor string for a single type: {@code I} for int,
	 * {@code Ljava/lang/String;} for objects, {@code [I} for int[], etc.
	 * @param type the type (must not be {@code null})
	 * @return the descriptor string (never {@code null})
	 */
	private static String typeDescriptor(final Class<?> type) {
		if (type.isPrimitive()) {
			return PRIMITIVE_DESCRIPTORS.getOrDefault(type, "V");
		}
		if (type.isArray()) {
			return "[" + typeDescriptor(type.getComponentType());
		}
		return "L" + type.getName().replace('.', '/') + ";";
	}

	/**
	 * Groups handler bindings by client name, returning the set of client names that have
	 * at least one handler.
	 * @param bindings the raw scan result
	 * @return a map from client name to the handlers registered for that client
	 */
	public static Map<String, List<HandlerBinding>> groupByClient(final List<HandlerBinding> bindings) {
		final Map<String, List<HandlerBinding>> grouped = new TreeMap<>();
		for (final HandlerBinding binding : bindings) {
			grouped.computeIfAbsent(binding.clientName(), (k) -> new ArrayList<>()).add(binding);
		}
		return grouped;
	}

	/**
	 * Returns the set of distinct client names targeted by the given bindings, excluding
	 * the {@link #ALL_CLIENTS} sentinel.
	 * @param bindings the raw scan result
	 * @return the set of explicitly named clients
	 */
	public static Set<String> explicitClientNames(final List<HandlerBinding> bindings) {
		final Set<String> names = new LinkedHashSet<>();
		for (final HandlerBinding binding : bindings) {
			if (!ALL_CLIENTS.equals(binding.clientName())) {
				names.add(binding.clientName());
			}
		}
		return names;
	}

	/**
	 * Immutable record describing a single handler-to-client binding discovered by
	 * scanning an {@code @Mcp*} annotation.
	 *
	 * @param handlerKind one of {@code sampling}, {@code elicitation}, {@code logging},
	 * {@code progress}
	 * @param clientName the target client name, or {@link #ALL_CLIENTS} if the
	 * annotation's {@code clients()} was empty
	 * @param beanName the Spring bean name carrying the annotated method
	 * @param beanClassName the user class of the bean
	 * @param methodName the annotated method name
	 * @param methodDescriptor the JVM-style method descriptor, overload-safe
	 * @param annotationFqcn the FQCN of the annotation
	 */
	public record HandlerBinding(String handlerKind, String clientName, String beanName, String beanClassName,
			String methodName, String methodDescriptor, String annotationFqcn) {
	}

}
