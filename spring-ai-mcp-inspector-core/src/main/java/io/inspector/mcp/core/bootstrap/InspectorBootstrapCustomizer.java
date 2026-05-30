/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

/**
 * Public extension point allowing library users to mutate the {@link InspectorBootstrap}
 * payload after the framework defaults have been applied but before it is serialised into
 * {@code index.html} (as {@code window.__MCP_INSPECTOR_BOOTSTRAP}) and before it is
 * served by the {@code ${spring.ai.mcp.inspector.path}/config} JSON endpoint.
 *
 * <h2>Registration</h2>
 *
 * <p>
 * Declare one or more implementations as Spring {@code @Bean}s; the
 * {@link InspectorBootstrapAssembler} autowires the full list and invokes each one
 * against every freshly-assembled {@link InspectorBootstrap} instance:
 *
 * <pre>{@code
 * &#64;Configuration
 * class InspectorCustomizers {
 *

 *     &#64;Bean
 *
&#64;Order(10)
 *     InspectorBootstrapCustomizer defaultUrlCustomizer() {
 *         return bootstrap -> bootstrap.setDefaultUrl("https://internal-mcp.acme.com/mcp");
 *     }
 * }
 * }</pre>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 * <li><b>Idempotent.</b> The same customizer is invoked once per assembly — which in
 * practice means once per inbound {@code /index.html} or {@code /config} request, because
 * the {@link InspectorBootstrapAssembler} itself is a singleton but constructs a fresh
 * {@link InspectorBootstrap} on every call. Customizers must therefore be safe to run
 * repeatedly and must not retain state between invocations.</li>
 * <li><b>Fast.</b> The customize call is on the request path. Avoid blocking I/O, network
 * calls, or other latency-incurring work; pre-compute expensive values at customizer-bean
 * construction time and reference them from the lambda.</li>
 * <li><b>Invocation timing.</b> Runs after the assembler has applied the framework
 * defaults ({@code authToken}, {@code proxyAddress}, {@code detectedTransport},
 * {@code detectedUrl}) — never before — so customizers can safely read those values and
 * decide whether to override them.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 *
 * <p>
 * When multiple {@link InspectorBootstrapCustomizer} beans are registered the assembler
 * invokes them in Spring's standard
 * {@link org.springframework.core.annotation.Order @Order} sequence — lower order values
 * run <em>first</em>, higher order values run <em>last</em>, and on a conflict the
 * <em>last</em> customizer to set a given field wins:
 *
 * <pre>{@code
 * &#64;Bean @Order(10) InspectorBootstrapCustomizer a() {
 *     return b -> b.setDefaultUrl("https://first.example/mcp");   // overwritten
 * }
 *
 *
&#64;Bean @Order(20) InspectorBootstrapCustomizer b() {
 *     return b -> b.setDefaultUrl("https://second.example/mcp");  // wins
 * }
 * }</pre>
 *
 * <p>
 * Customizers without an explicit {@code @Order} are treated as having
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} per the standard Spring
 * contract.
 *
 * <h2>The {@code extra} map — escape hatch</h2>
 *
 * <p>
 * {@link InspectorBootstrap#getExtra()} is a free-form {@link java.util.LinkedHashMap}
 * serialized verbatim into the JSON payload. Use it to surface fields that are not
 * modeled as first-class properties on {@link InspectorBootstrap} — for example feature
 * flags, tenant identifiers, or arbitrary metadata your fork of the upstream SPA reads at
 * boot. Note that the assembler escapes {@code </script>} sequences in the serialized
 * JSON before inline injection, so values in {@code extra} can safely contain HTML
 * fragments.
 *
 * <h2>Examples</h2>
 *
 * <p>
 * <b>1. Pre-fill {@code defaultUrl}</b> so the inspector form opens with a preferred MCP
 * server already populated:
 *
 * <pre>{@code
 * &#64;Bean
 * InspectorBootstrapCustomizer prefillDefaultUrl() {
 *     return bootstrap -> bootstrap.setDefaultUrl("https://internal-mcp.acme.com/mcp");
 * }
 * }</pre>
 *
 * <p>
 * <b>2. Add a {@link ServerEntry}</b> for an auto-discovered MCP server so it appears in
 * the inspector's server picker without operator action:
 *
 * <pre>{@code
 * &#64;Bean
 * InspectorBootstrapCustomizer autoDiscoveredServers() {
 *     return bootstrap -> bootstrap.getServerEntries().add(
 *         new ServerEntry(
 *             "internal",
 *             "https://internal-mcp/mcp",
 *             "streamable-http",
 *             Map.of("X-Tenant-Id", "acme")));
 * }
 * }</pre>
 *
 * <p>
 * <b>3. Add tenant-scoped {@link CustomHeader} defaults</b> so every outgoing MCP request
 * from the inspector UI carries the same routing/identification header:
 *
 * <pre>{@code
 * &#64;Bean
 * InspectorBootstrapCustomizer tenantHeader() {
 *     return bootstrap -> bootstrap.getDefaultHeaders().add(
 *         CustomHeader.of("X-Tenant-Id", "acme"));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface InspectorBootstrapCustomizer {

	/**
	 * Mutates the supplied bootstrap in place. Implementations should be fast,
	 * idempotent, and stateless — see the type-level javadoc for the full contract.
	 * @param bootstrap the bootstrap instance to customise (never {@code null})
	 */
	void customize(InspectorBootstrap bootstrap);

}
