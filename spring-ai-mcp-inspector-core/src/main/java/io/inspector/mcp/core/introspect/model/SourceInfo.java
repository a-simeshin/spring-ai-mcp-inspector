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

package io.inspector.mcp.core.introspect.model;

/**
 * Provenance of an introspection element: which Spring bean / method declared it.
 *
 * <p>
 * {@code source} is always a non-null object on every reported element (R-SOURCE v8). A
 * programmatically registered element (present only in the spec registry) carries
 * {@code registered=true} with all four fields {@code null}; the null fields live inside
 * the object, never the object itself.
 *
 * @param beanName name of the declaring bean, or {@code null} for spec-only elements
 * @param beanClass FQCN of the declaring bean class, or {@code null} for spec-only
 * elements
 * @param method name of the annotated method, or {@code null} for spec-only elements
 * @param annotation FQCN of the detected annotation ({@code @McpTool} /
 * {@code @McpResource}), or {@code null} for spec-only elements
 * @param registered whether the element is registered in the application context
 * @author Artem Simeshin
 */
public record SourceInfo(String beanName, String beanClass, String method, String annotation, boolean registered) {

}
