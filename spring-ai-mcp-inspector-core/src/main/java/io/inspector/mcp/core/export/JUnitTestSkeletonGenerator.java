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
 * tool call. Implementations produce a self-contained {@code .java} source file that
 * replays the captured JSON-RPC request against the MCP server endpoint and compares the
 * {@code result} member of the response with the captured etalon using JSONAssert in
 * LENIENT mode (JSON-RPC ids are not compared).
 *
 * <p>
 * All implementations must:
 * </p>
 * <ul>
 * <li>compute a valid Java package from the base package plus a {@code .tools}
 * suffix;</li>
 * <li>name the class {@code <ToolName>RegressionTest}, sanitising the tool name into a
 * valid Java identifier;</li>
 * <li>embed the captured request payload and the etalon response as string
 * constants;</li>
 * <li>include a header comment {@code Generated from inspector call <timestamp>};</li>
 * <li>perform the MCP streamable-HTTP handshake before replaying the captured call: an
 * {@code initialize} exchange, then {@code notifications/initialized} when the server is
 * stateful and returned a {@code Mcp-Session-Id}, carrying that session id on every
 * post-initialize request;</li>
 * <li>declare {@code Accept: application/json, text/event-stream} on every POST, the pair
 * the MCP Java SDK server transports answer with 400 otherwise;</li>
 * <li>extract the JSON-RPC response from the {@code data:} lines when the server answers
 * as an SSE stream instead of a plain JSON body;</li>
 * <li>produce output that compiles in a project that has the stack's web starter,
 * {@code spring-boot-starter-test} and the stack's test module ({@code
 * spring-boot-webmvc-test} on WebMVC, {@code spring-boot-webtestclient} on WebFlux).</li>
 * </ul>
 *
 * @author Artem Simeshin
 */
public interface JUnitTestSkeletonGenerator {

	/**
	 * Generates the full {@code .java} source file for a regression test.
	 * @param call the captured tool call record
	 * @param basePackage the base package of the host project (for example
	 * {@code com.example})
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
	 * Computes the test class name from the tool name. Characters outside
	 * {@link Character#isJavaIdentifierPart(char)} are replaced with underscores and a
	 * leading digit is prefixed with an underscore, so dashed or numeric tool names still
	 * yield a valid class name.
	 * @param toolName the tool name as advertised by the MCP server
	 * @return {@code <ToolName>RegressionTest}
	 */
	default String computeClassName(final String toolName) {
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
		final String normalized = toolName.strip();
		final StringBuilder identifier = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			final char candidate = normalized.charAt(i);
			final boolean identifierPart = Character.isJavaIdentifierPart(candidate);
			identifier.append(identifierPart ? candidate : '_');
		}
		String className = identifier.toString();
		if (Character.isDigit(className.charAt(0))) {
			className = "_" + className;
		}
		return Character.toUpperCase(className.charAt(0)) + className.substring(1) + "RegressionTest";
	}

}
