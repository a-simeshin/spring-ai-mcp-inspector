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

package io.inspector.mcp.core.export;

/**
 * Generates a compilable JUnit 5 {@code @SpringBootTest} skeleton from a captured MCP
 * tool call. Implementations produce a self-contained {@code .java} source file with the
 * tool arguments embedded as a JSON constant and at least one assertion comparing the key
 * response fields with the captured etalon.
 *
 * <p>
 * All implementations must:
 * </p>
 * <ul>
 * <li>compute a valid Java package from the base package plus a {@code .tools}
 * suffix;</li>
 * <li>name the class {@code <ToolName>RegressionTest};</li>
 * <li>embed arguments and etalon result as JSON string constants;</li>
 * <li>include a header comment {@code Generated from inspector call
 * <timestamp>};</li>
 * <li>produce output that compiles in a project that has only the stack's web starter
 * plus {@code spring-boot-starter-test} and the matching test module.</li>
 * </ul>
 *
 * @author Artem Simeshin
 */
public interface JUnitTestSkeletonGenerator {

	/**
	 * Generates the full {@code .java} source file for a regression test.
	 * @param call the captured tool call record
	 * @param basePackage the base package of the host project (for example
	 * {@code io.inspector.mcp.webmvc})
	 * @return compilable Java source
	 */
	String generate(ToolCallRecord call, String basePackage);

	/**
	 * Computes the test package from the base package.
	 * @param basePackage the base package of the host project
	 * @return {@code basePackage + ".tools"}, or {@code "tools"} when the base package is
	 * blank
	 */
	default String computeTestPackage(final String basePackage) {
		if (basePackage == null || basePackage.isBlank()) {
			return "tools";
		}
		return basePackage.trim() + ".tools";
	}

	/**
	 * Computes the test class name from the tool name.
	 * @param toolName the tool name as advertised by the MCP server
	 * @return {@code <ToolName>RegressionTest}
	 */
	default String computeClassName(final String toolName) {
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
		return toolName.substring(0, 1).toUpperCase() + toolName.substring(1) + "RegressionTest";
	}

}
