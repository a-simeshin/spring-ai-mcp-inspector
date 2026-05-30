/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Legacy E2E harness retained for plan-contract compatibility.
 *
 * <p>
 * The original {@code InspectorE2ETest} drove the in-house custom UI that has since been
 * removed in favour of the vendored upstream {@code modelcontextprotocol/inspector} React
 * client. All ten happy-path scenarios it asserted have been ported to
 * {@link InspectorUiIT} (picked up by Failsafe via the {@code *IT.java} suffix) with
 * selectors anchored to the upstream DOM contract under {@code docs/UPSTREAM_DOM_MAP.md}.
 *
 * <p>
 * This class is intentionally empty so:
 *
 * <ul>
 * <li>plans / scripts that reference {@code InspectorE2ETest} by name still find it on
 * the classpath;</li>
 * <li>the broken selectors against the deleted custom UI are gone — there is nothing here
 * to silently rot;</li>
 * <li>{@code mvn -Pe2e verify} (which includes {@code *E2ETest.java} via the {@code e2e}
 * Maven profile in {@code spring-ai-mcp-inspector-demo/pom.xml}) won't fail.</li>
 * </ul>
 *
 * <p>
 * To run the real e2e matrix, use:
 *
 * <pre>{@code
 *   mvn -B -pl spring-ai-mcp-inspector-demo -am verify -Dtest=InspectorUiIT
 * }</pre>
 */
class InspectorE2ETest {

	@Test
	@DisplayName("Legacy E2E placeholder — see InspectorUiIT for the active upstream-DOM tests")
	@Disabled("Superseded by InspectorUiIT. Run `-Dtest=InspectorUiIT` for the real matrix.")
	void supersededByInspectorUiIT() {
		// Intentionally left blank. See the class javadoc.
	}

}
