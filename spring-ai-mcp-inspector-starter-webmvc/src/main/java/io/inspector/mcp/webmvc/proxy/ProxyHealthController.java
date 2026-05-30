/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trivial liveness probe — returns {@code {"status":"ok"}}.
 *
 * <p>
 * Intentionally excluded from {@link ProxyAuthFilter} so external monitors can poll it
 * without holding the proxy token.
 */
@RestController
@RequestMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}-api")
public class ProxyHealthController {

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}

}
