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

package io.inspector.mcp.webflux.plain;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring Boot application whose component scan covers ONLY
 * {@code io.inspector.mcp.webflux.plain} — a package with no MCP annotations — so the
 * introspection endpoint reports an empty MCP context (R8-PLAIN v10).
 */
@SpringBootApplication
@ComponentScan(basePackages = "io.inspector.mcp.webflux.plain")
public class PlainWebFluxApp {

}
