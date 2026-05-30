/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring Boot application bootstrapping a Spring AI MCP server with a few demo
 * tools so the inspector's REST relay (and loopback client) can be exercised end-to-end
 * from the WebMvc IT classes in this module.
 */
@SpringBootApplication
@ComponentScan(basePackages = "io.inspector.mcp.webmvc.it")
public class TestMcpServerApp {

}
