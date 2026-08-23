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
 * <li>1 tool ({@code slowEcho}) deliberately WITHOUT explicit annotations — receives the
 * spec defaults (readOnlyHint=false, destructiveHint=true per MCP spec).</li>
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
	 * MCP spec defaults for readOnlyHint/destructiveHint when no annotations are
	 * declared.
	 */
	private static final boolean SPEC_DEFAULT_READ_ONLY = false;

	private static final boolean SPEC_DEFAULT_DESTRUCTIVE = true;

	/** Names of the three provider classes that hold @McpTool methods. */
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

		for (Method method : annotatedMethods) {
			McpTool mcptool = method.getAnnotation(McpTool.class);
			String toolName = mcptool.name();
			McpAnnotations annotations = mcptool.annotations();

			// slowEcho is the special case: it has NO explicit annotations element,
			// so it gets the spec defaults via the annotation's default value
			if ("slowEcho".equals(toolName)) {
				Assertions.assertThat(annotations)
					.as("slowEcho annotations element is present (default value) but must carry spec defaults")
					.isNotNull();
				Assertions.assertThat(annotations.readOnlyHint())
					.as("slowEcho: readOnlyHint must be spec default (%s)", SPEC_DEFAULT_READ_ONLY)
					.isEqualTo(SPEC_DEFAULT_READ_ONLY);
				Assertions.assertThat(annotations.destructiveHint())
					.as("slowEcho: destructiveHint must be spec default (%s)", SPEC_DEFAULT_DESTRUCTIVE)
					.isEqualTo(SPEC_DEFAULT_DESTRUCTIVE);
				continue;
			}

			// All other 21 tools MUST have explicit annotations present
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
		List<String> scannedNames = annotatedMethods.stream()
			.map(m -> m.getAnnotation(McpTool.class).name())
			.collect(Collectors.toList());

		for (String expectedName : EXPECTED_ANNOTATIONS.keySet()) {
			Assertions.assertThat(scannedNames)
				.as("Expected tool '%s' not found among @McpTool methods", expectedName)
				.contains(expectedName);
		}
	}

	/**
	 * Verifies that {@code slowEcho} tool is emitted with SPEC DEFAULT annotations (no
	 * explicit annotations element in @McpTool declaration).
	 *
	 * <p>
	 * This is the control tool demonstrating the MCP spec-default behaviour: when
	 * {@code annotations} is absent, the annotation processor falls back to
	 * {@code readOnlyHint=false, destructiveHint=true} per the MCP specification. The
	 * Java annotation default value provides exactly these defaults.
	 */
	@Test
	void slowEchoEmittedWithSpecDefaults() {
		// given
		Method slowEchoMethod = Arrays.stream(DemoAdvancedToolsProvider.class.getDeclaredMethods())
			.filter(m -> m.isAnnotationPresent(McpTool.class))
			.filter(m -> "slowEcho".equals(m.getAnnotation(McpTool.class).name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("slowEcho method not found on DemoAdvancedToolsProvider"));

		// when
		McpTool mcptool = slowEchoMethod.getAnnotation(McpTool.class);
		McpAnnotations annotations = mcptool.annotations();

		// then
		Assertions.assertThat(mcptool.name()).isEqualTo("slowEcho");
		Assertions.assertThat(annotations)
			.as("slowEcho annotations element is present (default value) — this is expected")
			.isNotNull();
		Assertions.assertThat(annotations.readOnlyHint())
			.as("slowEcho readOnlyHint must be spec default (false)")
			.isEqualTo(SPEC_DEFAULT_READ_ONLY);
		Assertions.assertThat(annotations.destructiveHint())
			.as("slowEcho destructiveHint must be spec default (true)")
			.isEqualTo(SPEC_DEFAULT_DESTRUCTIVE);
		Assertions.assertThat(annotations.idempotentHint())
			.as("slowEcho idempotentHint must be spec default (false)")
			.isEqualTo(false);
		Assertions.assertThat(annotations.openWorldHint())
			.as("slowEcho openWorldHint must be spec default (true)")
			.isEqualTo(true);
	}

}