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
package io.inspector.mcp.demo.tools;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;

/**
 * Unit tests validating the honesty contract of hint annotations across all 22 demo
 * tools.
 *
 * <p>
 * Contract (mirrored from issue #57, issuecomment-5382846304):
 * <ul>
 * <li>17 tools declared with {@code readOnlyHint=true, destructiveHint=false}: echo, sum,
 * currentTime, addNumbers, concatenate, lookupUser, chooseColor, toggleFlag,
 * optionalGreeting, errorTool, largeOutput, structuredOutput, multiContent, deepJson,
 * blobAttachment, findFiles, listMyRoots</li>
 * <li>4 tools declared with {@code readOnlyHint=false, destructiveHint=false}: askLlm,
 * askUser, deployService, authorizeViaUrl</li>
 * <li>1 tool ({@code slowEcho}) deliberately NOT an {@code @McpTool} method: it is
 * registered as a manual {@link org.springframework.ai.tool.ToolCallback} bean (see
 * {@link SlowEchoToolConfiguration}) so that {@code tools/list} omits the
 * {@code annotations} object entirely — the honest spec-default signal
 * (readOnlyHint=false, destructiveHint=true per MCP spec).</li>
 * </ul>
 */
class DemoToolHintsTests {

	/** Expected (readOnlyHint, destructiveHint) pairs for each annotated tool. */
	private static final Map<String, boolean[]> EXPECTED_ANNOTATIONS = new LinkedHashMap<>() {
		{
			// R=T, D=F (17 tools)
			put("echo", new boolean[] { true, false });
			put("sum", new boolean[] { true, false });
			put("currentTime", new boolean[] { true, false });
			put("addNumbers", new boolean[] { true, false });
			put("concatenate", new boolean[] { true, false });
			put("lookupUser", new boolean[] { true, false });
			put("chooseColor", new boolean[] { true, false });
			put("toggleFlag", new boolean[] { true, false });
			put("optionalGreeting", new boolean[] { true, false });
			put("errorTool", new boolean[] { true, false });
			put("largeOutput", new boolean[] { true, false });
			put("structuredOutput", new boolean[] { true, false });
			put("multiContent", new boolean[] { true, false });
			put("deepJson", new boolean[] { true, false });
			put("blobAttachment", new boolean[] { true, false });
			put("findFiles", new boolean[] { true, false });
			put("listMyRoots", new boolean[] { true, false });
			// R=F, D=F (4 tools)
			put("askLlm", new boolean[] { false, false });
			put("askUser", new boolean[] { false, false });
			put("deployService", new boolean[] { false, false });
			put("authorizeViaUrl", new boolean[] { false, false });
		}
	};

	/**
	 * Names of the three provider classes that hold @McpTool methods.
	 */
	private static final List<Class<?>> PROVIDER_CLASSES = List.of(DemoToolsProvider.class,
			DemoAdvancedToolsProvider.class, DemoInteractiveToolsProvider.class);

	/**
	 * Scans all @McpTool-annotated methods across the three provider classes and asserts
	 * that the readOnlyHint/destructiveHint values match the contracted matrix.
	 *
	 * <p>
	 * This test will FAIL if any tool diverges from the matrix OR if a new tool is added
	 * without a corresponding entry in {@link #EXPECTED_ANNOTATIONS}.
	 */
	@Test
	void hintAnnotationsMatrix() {
		// given: collect all @McpTool methods
		List<Method> annotatedMethods = PROVIDER_CLASSES.stream()
			.flatMap(clazz -> Arrays.stream(clazz.getDeclaredMethods()))
			.filter(method -> method.isAnnotationPresent(McpTool.class))
			.collect(Collectors.toList());

		// when + then: verify every method
		Assertions.assertThat(annotatedMethods).as("At least one @McpTool method should be found").isNotEmpty();

		// slowEcho must NOT be among them — it is a manual ToolCallback
		List<String> annotatedNames = annotatedMethods.stream()
			.map(m -> m.getAnnotation(McpTool.class).name())
			.collect(Collectors.toList());
		Assertions.assertThat(annotatedNames)
			.as("slowEcho must not be an @McpTool method (manual ToolCallback)")
			.doesNotContain("slowEcho");

		for (Method method : annotatedMethods) {
			McpTool mcptool = method.getAnnotation(McpTool.class);
			String toolName = mcptool.name();
			McpAnnotations annotations = mcptool.annotations();

			// All 21 annotated tools MUST have explicit annotations present
			Assertions.assertThat(annotations)
				.as("Tool '%s' must declare explicit @McpTool annotations", toolName)
				.isNotNull();

			boolean[] expected = EXPECTED_ANNOTATIONS.get(toolName);
			Assertions.assertThat(expected)
				.as("No expected annotation entry for tool '%s' — add it to EXPECTED_ANNOTATIONS map", toolName)
				.isNotNull();

			boolean expectedReadOnly = expected[0];
			boolean expectedDestructive = expected[1];

			Assertions.assertThat(annotations.readOnlyHint())
				.as("Tool '%s': readOnlyHint mismatch (expected=%s)", toolName, expectedReadOnly)
				.isEqualTo(expectedReadOnly);
			Assertions.assertThat(annotations.destructiveHint())
				.as("Tool '%s': destructiveHint mismatch (expected=%s)", toolName, expectedDestructive)
				.isEqualTo(expectedDestructive);
		}

		// Ensure no expected tool is missing from the scanned set
		for (String expectedName : EXPECTED_ANNOTATIONS.keySet()) {
			Assertions.assertThat(annotatedNames)
				.as("Expected tool '%s' not found among @McpTool methods", expectedName)
				.contains(expectedName);
		}
	}

	/**
	 * Verifies that {@code slowEcho} is registered as a manual {@code ToolCallback} whose
	 * {@code ToolDefinition} carries no annotations — the wire entry therefore omits the
	 * {@code annotations} object, which is the honest signal that its hints are MCP spec
	 * defaults, not server declarations.
	 *
	 * <p>
	 * The {@code @McpTool} route is deliberately avoided: the Spring AI annotation
	 * scanner synthesizes a fully populated spec-default annotations object for
	 * annotation-less methods, which would be indistinguishable from a server declaration
	 * on the wire.
	 */
	@Test
	void slowEchoRegisteredAsManualToolCallbackWithoutAnnotations() {
		// given
		SlowEchoToolConfiguration configuration = new SlowEchoToolConfiguration();

		// when
		org.springframework.ai.tool.ToolCallback callback = configuration.slowEcho();
		org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();

		// then
		Assertions.assertThat(definition.name()).isEqualTo("slowEcho");
		Assertions.assertThat(definition.description()).isEqualTo("Echo text after a ~2 second delay");
		Assertions.assertThat(definition.inputSchema())
			.as("slowEcho inputSchema must declare the text property")
			.contains("text");
		// ToolDefinition has no annotations concept — the converter builds the wire Tool
		// without annotations, so the field is omitted on the wire.
	}

}