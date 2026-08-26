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

package io.inspector.mcp.core.introspect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import io.inspector.mcp.core.introspect.model.IntrospectionReport;
import io.inspector.mcp.core.introspect.model.McpElementInfo;
import io.inspector.mcp.core.introspect.model.McpElementKind;
import io.inspector.mcp.core.introspect.model.SchemaWarning;
import io.inspector.mcp.core.introspect.model.SourceInfo;
import io.inspector.mcp.core.introspect.model.WarningCode;

/**
 * Introspects an {@link ApplicationContext} for MCP tools and resources: what is declared
 * (via the spec registry beans and via METHOD-level {@code @McpTool}/{@code @McpResource}
 * annotations) and where each element comes from (bean + method, or programmatic
 * registration).
 *
 * <p>
 * Spring AI annotations are detected by FQCN string only — this class imports no
 * {@code org.springframework.ai.*} types and needs no compile-time spring-ai dependency.
 * The spec registry types ({@link McpServerFeatures} / {@link McpStatelessServerFeatures}
 * nested specification records) come from {@code mcp-core}, which is already a compile
 * dependency of this module.
 *
 * <p>
 * Stateless POJO; not a Spring component. Safe to instantiate per request.
 *
 * @author Artem Simeshin
 */
public class McpBeanIntrospector {

	private static final String MCP_TOOL_FQCN = "org.springframework.ai.mcp.annotation.McpTool";

	private static final String MCP_RESOURCE_FQCN = "org.springframework.ai.mcp.annotation.McpResource";

	/**
	 * Reads the 12 spec-registry {@code List<...>} bean types and the reflected
	 * {@code @McpTool}/{@code @McpResource} methods of every bean, unions them and runs
	 * the OUTSIDE_COMPONENT_SCAN detection (R-SCAN v12).
	 * @param context the application context to introspect
	 * @return the introspection report, never {@code null}
	 */
	public IntrospectionReport introspect(final ApplicationContext context) {
		Assert.notNull(context, "context cannot be null");
		final Map<String, McpElementInfo> tools = readToolRegistries(context);
		final Map<ResourceKey, McpElementInfo> resources = readResourceRegistries(context);
		final Map<String, ReflectedElement> reflectedTools = new LinkedHashMap<>();
		final Map<ResourceKey, ReflectedElement> reflectedResources = new LinkedHashMap<>();
		final Set<String> beanClasses = new LinkedHashSet<>();
		collectBeanClasses(context, beanClasses, reflectedTools, reflectedResources);
		mergeReflectedTools(tools, reflectedTools);
		mergeReflectedResources(resources, reflectedResources);
		final List<SchemaWarning> warnings = new ArrayList<>(scanOutsideComponentScan(context, beanClasses));
		if (tools.isEmpty() && resources.isEmpty()) {
			warnings.add(new SchemaWarning(WarningCode.NO_MCP_ELEMENTS, WarningCode.NO_MCP_ELEMENTS.defaultSeverity(),
					"", "$", "No MCP tools or resources found in the application context"));
		}
		return new IntrospectionReport(sortedTools(tools), sortedResources(resources), List.copyOf(warnings));
	}

	private Map<String, McpElementInfo> readToolRegistries(final ApplicationContext context) {
		final Map<String, McpElementInfo> tools = new LinkedHashMap<>();
		for (final McpElementInfo element : readListBeans(context, McpServerFeatures.SyncToolSpecification.class,
				(spec) -> toolElement(spec.tool()))) {
			tools.putIfAbsent(element.name(), element);
		}
		for (final McpElementInfo element : readListBeans(context, McpServerFeatures.AsyncToolSpecification.class,
				(spec) -> toolElement(spec.tool()))) {
			tools.putIfAbsent(element.name(), element);
		}
		for (final McpElementInfo element : readListBeans(context,
				McpStatelessServerFeatures.SyncToolSpecification.class, (spec) -> toolElement(spec.tool()))) {
			tools.putIfAbsent(element.name(), element);
		}
		for (final McpElementInfo element : readListBeans(context,
				McpStatelessServerFeatures.AsyncToolSpecification.class, (spec) -> toolElement(spec.tool()))) {
			tools.putIfAbsent(element.name(), element);
		}
		return tools;
	}

	private Map<ResourceKey, McpElementInfo> readResourceRegistries(final ApplicationContext context) {
		final Map<ResourceKey, McpElementInfo> resources = new LinkedHashMap<>();
		readResourceRegistry(context, McpServerFeatures.SyncResourceSpecification.class,
				(spec) -> resourceElement(spec.resource()), resources);
		readResourceRegistry(context, McpServerFeatures.AsyncResourceSpecification.class,
				(spec) -> resourceElement(spec.resource()), resources);
		readResourceRegistry(context, McpStatelessServerFeatures.SyncResourceSpecification.class,
				(spec) -> resourceElement(spec.resource()), resources);
		readResourceRegistry(context, McpStatelessServerFeatures.AsyncResourceSpecification.class,
				(spec) -> resourceElement(spec.resource()), resources);
		readResourceRegistry(context, McpServerFeatures.SyncResourceTemplateSpecification.class,
				(spec) -> templateElement(spec.resourceTemplate()), resources);
		readResourceRegistry(context, McpServerFeatures.AsyncResourceTemplateSpecification.class,
				(spec) -> templateElement(spec.resourceTemplate()), resources);
		readResourceRegistry(context, McpStatelessServerFeatures.SyncResourceTemplateSpecification.class,
				(spec) -> templateElement(spec.resourceTemplate()), resources);
		readResourceRegistry(context, McpStatelessServerFeatures.AsyncResourceTemplateSpecification.class,
				(spec) -> templateElement(spec.resourceTemplate()), resources);
		return resources;
	}

	private <T> void readResourceRegistry(final ApplicationContext context, final Class<T> specType,
			final Function<T, McpElementInfo> elementMapper, final Map<ResourceKey, McpElementInfo> sink) {
		for (final McpElementInfo element : readListBeans(context, specType, elementMapper)) {
			sink.putIfAbsent(resourceKey(element), element);
		}
	}

	/**
	 * R-READ v13: {@code getBeanNamesForType(ResolvableType)} (non-instantiating) then
	 * per-name {@code getBean} inside {@code try/catch (BeansException)}. A failing bean
	 * is skipped, valid beans are retained; {@code getBeansOfType} is never used because
	 * it instantiates all matches at once and aborts the whole read on the first failure.
	 * @param <T> the specification record type
	 * @param context the application context
	 * @param specType one of the 12 {@code List<...>} registry element types
	 * @param elementMapper spec record to element info
	 * @return elements read from every matching {@code List} bean
	 */
	private static <T> List<McpElementInfo> readListBeans(final ApplicationContext context, final Class<T> specType,
			final Function<T, McpElementInfo> elementMapper) {
		final List<McpElementInfo> elements = new ArrayList<>();
		for (final Object spec : readListBeans(context, specType)) {
			elements.add(elementMapper.apply(specType.cast(spec)));
		}
		return elements;
	}

	/**
	 * Reads every bean of the given {@code List<specType>} shape without instantiating
	 * more than one bean at a time.
	 * @param context the application context
	 * @param specType one of the 12 {@code List<...>} registry element types
	 * @return the spec records found in every matching {@code List} bean
	 */
	private static List<Object> readListBeans(final ApplicationContext context, final Class<?> specType) {
		final List<Object> specs = new ArrayList<>();
		final ResolvableType listType = ResolvableType.forClassWithGenerics(List.class, specType);
		final String[] names = context.getBeanNamesForType(listType);
		for (final String name : names) {
			final Object bean = readBean(context, name);
			if (bean instanceof List<?> list) {
				for (final Object spec : list) {
					if (spec != null && specType.isInstance(spec)) {
						specs.add(spec);
					}
				}
			}
		}
		return specs;
	}

	private static Object readBean(final ApplicationContext context, final String name) {
		try {
			return context.getBean(name);
		}
		catch (final BeansException ex) {
			// R-DEGRADE v13: per-bean failures never fail the report.
			return null;
		}
	}

	private void collectBeanClasses(final ApplicationContext context, final Set<String> beanClasses,
			final Map<String, ReflectedElement> reflectedTools,
			final Map<ResourceKey, ReflectedElement> reflectedResources) {
		for (final String beanName : context.getBeanDefinitionNames()) {
			final Class<?> beanClass = context.getType(beanName);
			if (beanClass == null) {
				continue;
			}
			beanClasses.add(ClassUtils.getUserClass(beanClass).getName());
			reflectAnnotatedMethods(beanName, beanClass, reflectedTools, reflectedResources);
		}
	}

	private void reflectAnnotatedMethods(final String beanName, final Class<?> beanClass,
			final Map<String, ReflectedElement> reflectedTools,
			final Map<ResourceKey, ReflectedElement> reflectedResources) {
		for (final Method method : ReflectionUtils.getAllDeclaredMethods(beanClass)) {
			final MergedAnnotations annotations = MergedAnnotations.from(method,
					MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
			final MergedAnnotation<?> toolAnnotation = annotations.get(MCP_TOOL_FQCN);
			if (toolAnnotation.isPresent()) {
				final String name = elementName(toolAnnotation, method);
				reflectedTools
					.putIfAbsent(name,
							new ReflectedElement(
									new SourceInfo(beanName, ClassUtils.getUserClass(beanClass).getName(),
											method.getName(), MCP_TOOL_FQCN, true),
									toolAnnotation.getString("description"), null));
			}
			final MergedAnnotation<?> resourceAnnotation = annotations.get(MCP_RESOURCE_FQCN);
			if (resourceAnnotation.isPresent()) {
				final String name = elementName(resourceAnnotation, method);
				final String uri = resourceAnnotation.getString("uri");
				reflectedResources.putIfAbsent(new ResourceKey(uri, name),
						new ReflectedElement(new SourceInfo(beanName, ClassUtils.getUserClass(beanClass).getName(),
								method.getName(), MCP_RESOURCE_FQCN, true), resourceAnnotation.getString("description"),
								uri));
			}
		}
	}

	/**
	 * R-NAME-FALLBACK v13: element name = {@code hasText(annotation.name()) ?
	 * annotation.name() : method.getName()}, mirroring Spring AI 2.0's own providers.
	 * @param annotation the merged {@code @McpTool}/{@code @McpResource} annotation
	 * @param method the annotated method
	 * @return the element name
	 */
	private static String elementName(final MergedAnnotation<?> annotation, final Method method) {
		final String annotatedName = annotation.getString("name");
		return StringUtils.hasText(annotatedName) ? annotatedName : method.getName();
	}

	private static void mergeReflectedTools(final Map<String, McpElementInfo> tools,
			final Map<String, ReflectedElement> reflectedTools) {
		for (final Map.Entry<String, ReflectedElement> entry : reflectedTools.entrySet()) {
			final String name = entry.getKey();
			final ReflectedElement reflected = entry.getValue();
			final McpElementInfo registered = tools.get(name);
			if (registered == null) {
				tools.put(name, new McpElementInfo(McpElementKind.TOOL, name, reflected.description(), null, null, null,
						null, reflected.source()));
			}
			else {
				tools.put(name, withSource(registered, reflected.source()));
			}
		}
	}

	private static void mergeReflectedResources(final Map<ResourceKey, McpElementInfo> resources,
			final Map<ResourceKey, ReflectedElement> reflectedResources) {
		for (final Map.Entry<ResourceKey, ReflectedElement> entry : reflectedResources.entrySet()) {
			final ResourceKey key = entry.getKey();
			final ReflectedElement reflected = entry.getValue();
			final McpElementInfo registered = resources.get(key);
			if (registered == null) {
				resources.put(key,
						new McpElementInfo(
								isUriTemplate(reflected.uri()) ? McpElementKind.RESOURCE_TEMPLATE
										: McpElementKind.RESOURCE,
								key.name(), reflected.description(), null, null, reflected.uri(), null,
								reflected.source()));
			}
			else {
				resources.put(key, withSource(registered, reflected.source()));
			}
		}
	}

	private static McpElementInfo withSource(final McpElementInfo element, final SourceInfo source) {
		return new McpElementInfo(element.kind(), element.name(), element.description(), element.inputSchema(),
				element.outputSchema(), element.uri(), element.mimeType(), source);
	}

	private static McpElementInfo toolElement(final McpSchema.Tool tool) {
		return new McpElementInfo(McpElementKind.TOOL, tool.name(), tool.description(), tool.inputSchema(),
				tool.outputSchema(), null, null, programmaticSource());
	}

	private static McpElementInfo resourceElement(final McpSchema.Resource resource) {
		return new McpElementInfo(
				isUriTemplate(resource.uri()) ? McpElementKind.RESOURCE_TEMPLATE : McpElementKind.RESOURCE,
				resource.name(), resource.description(), null, null, resource.uri(), resource.mimeType(),
				programmaticSource());
	}

	private static McpElementInfo templateElement(final McpSchema.ResourceTemplate template) {
		return new McpElementInfo(McpElementKind.RESOURCE_TEMPLATE, template.name(), template.description(), null, null,
				template.uriTemplate(), template.mimeType(), programmaticSource());
	}

	/**
	 * R-TEMPLATE v11: a URI containing a {@code {...}} placeholder classifies the element
	 * as {@link McpElementKind#RESOURCE_TEMPLATE}.
	 * @param uri the resource URI / URI template
	 * @return {@code true} when the URI contains a placeholder
	 */
	private static boolean isUriTemplate(final String uri) {
		return uri != null && uri.contains("{") && uri.contains("}");
	}

	/**
	 * R-SOURCE v8: programmatic (spec-only) elements carry
	 * {@code registered=true, beanName/beanClass/method/annotation = null}.
	 * @return the programmatic source marker
	 */
	private static SourceInfo programmaticSource() {
		return new SourceInfo(null, null, null, null, true);
	}

	private static ResourceKey resourceKey(final McpElementInfo element) {
		return new ResourceKey(element.uri(), element.name());
	}

	private static List<McpElementInfo> sortedTools(final Map<String, McpElementInfo> tools) {
		return tools.values().stream().sorted(Comparator.comparing(McpElementInfo::name)).toList();
	}

	private static List<McpElementInfo> sortedResources(final Map<ResourceKey, McpElementInfo> resources) {
		return resources.values()
			.stream()
			.sorted(Comparator.comparing(McpElementInfo::uri, Comparator.nullsFirst(Comparator.naturalOrder())))
			.toList();
	}

	/**
	 * R-SCAN v12: classes inside the component-scan roots that carry method-level
	 * {@code @McpTool}/{@code @McpResource} but are not registered beans produce an
	 * OUTSIDE_COMPONENT_SCAN warning. No roots resolve to no scan at all (no warning).
	 * @param context the application context
	 * @param beanClasses the FQCNs of all registered bean classes
	 * @return the outside-component-scan warnings
	 */
	private List<SchemaWarning> scanOutsideComponentScan(final ApplicationContext context,
			final Set<String> beanClasses) {
		final Set<String> roots = resolveScanRoots(context);
		if (roots.isEmpty()) {
			return List.of();
		}
		return findAnnotatedClassesOutsideBeans(roots, beanClasses).stream()
			.map(McpBeanIntrospector::outsideComponentScanWarning)
			.toList();
	}

	private Set<String> resolveScanRoots(final ApplicationContext context) {
		final Set<String> roots = new LinkedHashSet<>();
		for (final String beanName : context.getBeanDefinitionNames()) {
			final Class<?> beanClass = context.getType(beanName);
			if (beanClass != null) {
				collectScanRoots(beanClass, roots, new HashSet<>());
			}
		}
		return roots;
	}

	private void collectScanRoots(final Class<?> beanClass, final Set<String> roots, final Set<Class<?>> visited) {
		if (!visited.add(beanClass)) {
			return;
		}
		final MergedAnnotations annotations = MergedAnnotations.from(beanClass,
				MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
		final MergedAnnotation<ComponentScan> scan = annotations.get(ComponentScan.class);
		if (scan.isPresent()) {
			addScanRoot(scan, beanClass, roots);
		}
		final MergedAnnotation<ComponentScans> scans = annotations.get(ComponentScans.class);
		if (scans.isPresent()) {
			for (final MergedAnnotation<ComponentScan> nested : scans.getAnnotationArray("value",
					ComponentScan.class)) {
				addScanRoot(nested, beanClass, roots);
			}
		}
		final MergedAnnotation<Import> imports = annotations.get(Import.class);
		if (imports.isPresent()) {
			for (final Class<?> imported : imports.getClassArray("value")) {
				collectScanRoots(imported, roots, visited);
			}
		}
	}

	private static void addScanRoot(final MergedAnnotation<ComponentScan> scan, final Class<?> declaringClass,
			final Set<String> roots) {
		final String[] basePackages = scan.getStringArray("basePackages");
		final Class<?>[] basePackageClasses = scan.getClassArray("basePackageClasses");
		if (basePackages.length == 0 && basePackageClasses.length == 0) {
			// Spring's own rule: the declaring class package is the root.
			roots.add(declaringClass.getPackageName());
			return;
		}
		for (final String basePackage : basePackages) {
			if (StringUtils.hasText(basePackage)) {
				roots.add(basePackage);
			}
		}
		for (final Class<?> basePackageClass : basePackageClasses) {
			roots.add(basePackageClass.getPackageName());
		}
	}

	private Set<String> findAnnotatedClassesOutsideBeans(final Set<String> roots, final Set<String> beanClasses) {
		final ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(
				false);
		provider.addIncludeFilter((metadataReader,
				metadataReaderFactory) -> metadataReader.getAnnotationMetadata().hasAnnotatedMethods(MCP_TOOL_FQCN)
						|| metadataReader.getAnnotationMetadata().hasAnnotatedMethods(MCP_RESOURCE_FQCN));
		final Set<String> outside = new LinkedHashSet<>();
		for (final String root : roots) {
			for (final BeanDefinition candidate : provider.findCandidateComponents(root)) {
				final String candidateClass = candidate.getBeanClassName();
				if (candidateClass != null && !beanClasses.contains(candidateClass)) {
					outside.add(candidateClass);
				}
			}
		}
		return outside;
	}

	private static SchemaWarning outsideComponentScanWarning(final String fqcn) {
		return new SchemaWarning(WarningCode.OUTSIDE_COMPONENT_SCAN,
				WarningCode.OUTSIDE_COMPONENT_SCAN.defaultSeverity(), fqcn, "$",
				"Class " + fqcn + " declares @McpTool/@McpResource methods but is not registered as a bean");
	}

	private record ReflectedElement(SourceInfo source, String description, String uri) {

	}

	private record ResourceKey(String uri, String name) {

	}

}
