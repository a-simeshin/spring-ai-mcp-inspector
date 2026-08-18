/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.oauth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in marker configuration for the in-process OAuth 2.1 stub server used by the
 * inspector's <em>AuthDebugger</em> end-to-end tests.
 *
 * <p>
 * The actual beans — {@link OAuthStubStore} and {@link OAuthStubController} — are
 * component-scanned but each carry their own {@link ConditionalOnProperty} guard, so they
 * bootstrap only when the {@code oauth-stub} Spring profile (which sets
 * {@code demo.oauth-stub.enabled=true}) is active. This avoids polluting normal demo runs
 * with the stub OAuth endpoints.
 *
 * <p>
 * This configuration class exists primarily as a doc anchor and a single conditional
 * handle for future bean wiring (e.g., a JWT signer or token persistence). It is
 * intentionally <strong>not</strong> registered in {@code AutoConfiguration.imports} —
 * its only entry point is the {@code oauth-stub} profile + property activation.
 *
 * <p>
 * Endpoints activated by this profile:
 * <ul>
 * <li>{@code GET  /.well-known/oauth-authorization-server} — RFC 8414 discovery</li>
 * <li>{@code POST /oauth/register} — RFC 7591 dynamic client registration</li>
 * <li>{@code GET/POST /oauth/authorize} — minimal approve/deny page</li>
 * <li>{@code POST /oauth/token} — token endpoint (PKCE S256 validated)</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "demo.oauth-stub", name = "enabled", havingValue = "true")
public class OAuthStubAutoConfiguration {

}
