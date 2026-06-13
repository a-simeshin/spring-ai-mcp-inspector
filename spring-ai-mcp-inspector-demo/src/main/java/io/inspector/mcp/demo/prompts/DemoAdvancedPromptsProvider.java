/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.prompts;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * Schema-rich demo MCP prompts added alongside {@link DemoPromptsProvider}.
 *
 * <p>
 * Exercises:
 * <ul>
 * <li>multi-turn prompts (system + user + assistant) — UI must render the role
 * labels</li>
 * <li>optional prompt argument widgets (required = false)</li>
 * <li>{@code completion/complete} hint API via {@link McpComplete}</li>
 * </ul>
 */
@Component
public class DemoAdvancedPromptsProvider {

	/**
	 * Multi-turn prompt: returns system + user + assistant messages. Drives the
	 * inspector's role-label rendering in the Prompt preview pane.
	 *
	 * <p>
	 * Note: {@link Role} in MCP 0.18.2 only models {@code USER} and {@code ASSISTANT} —
	 * there is no {@code SYSTEM} role. We surface a "system" style message by prefixing
	 * the user-role TextContent, which matches what existing Spring AI prompt fixtures
	 * do.
	 */
	@McpPrompt(name = "multiTurn", description = "Multi-turn (system + user + assistant) prompt example")
	public GetPromptResult multiTurn(
			@McpArg(name = "topic", description = "Topic of the conversation", required = true) String topic) {
		String t = (topic == null || topic.isBlank()) ? "general" : topic;
		return new GetPromptResult("Multi-turn conversation about " + t, List.of(
				new PromptMessage(Role.USER,
						new TextContent("[system] You are a helpful assistant focused on " + t + ".")),
				new PromptMessage(Role.USER, new TextContent("Tell me something about " + t + ".")),
				new PromptMessage(Role.ASSISTANT, new TextContent("Sure, here are a few thoughts on " + t + "."))));
	}

	/**
	 * Optional-arg prompt: {@code title} is required, {@code detail} is optional. UI must
	 * render {@code detail} without a required marker.
	 */
	@McpPrompt(name = "optionalDescription", description = "Prompt with one required and one optional argument")
	public GetPromptResult optionalDescription(
			@McpArg(name = "title", description = "Title of the description", required = true) String title,
			@McpArg(name = "detail", description = "Optional detail to expand on the title",
					required = false) String detail) {
		String body = (detail == null || detail.isBlank()) ? title : title + " — " + detail;
		return new GetPromptResult("Description for " + title,
				List.of(new PromptMessage(Role.ASSISTANT, new TextContent(body))));
	}

	/**
	 * Completion hint for the {@code topic} argument of the {@code multiTurn} prompt. The
	 * MCP server autoconfig in Spring AI 1.1.7 wires this through
	 * {@code McpServerSpecificationFactoryAutoConfiguration.SyncServerSpecificationConfiguration#completionSpecs},
	 * so the inspector's autocomplete dropdown is populated automatically.
	 *
	 * <p>
	 * Returning a {@code List<String>} is supported by
	 * {@code DefaultMcpCompleteResultConverter} (mcp-annotations 0.9.0); it is wrapped
	 * into a {@code CompleteResult} server-side.
	 */
	@McpComplete(prompt = "multiTurn")
	public List<String> completeMultiTurnTopic(String value) {
		List<String> suggestions = List.of("weather", "news", "sports", "music", "movies");
		if (value == null || value.isBlank()) {
			return suggestions;
		}
		String prefix = value.toLowerCase();
		return suggestions.stream().filter(s -> s.startsWith(prefix)).toList();
	}

	// Workaround for Java SDK 0.18.2 bug filed as
	// https://github.com/modelcontextprotocol/java-sdk/issues/991 —
	// server advertises `completions` capability globally as soon as ANY
	// @McpComplete is registered, then throws -32602 on any prompt or
	// resource template ref without a matching handler. Until the SDK is
	// fixed to return EMPTY_COMPLETION_RESULT on missing specs (matching
	// TS/Python SDKs), we register no-op handlers per ref so the upstream
	// Inspector shows graceful "no suggestions" instead of a red error toast.

	@McpComplete(prompt = "greeting")
	public List<String> completeGreetingName(String value) {
		List<String> samples = List.of("Artem", "Alice", "Bob", "Charlie", "Дмитрий");
		if (value == null || value.isBlank()) {
			return samples;
		}
		String prefix = value.toLowerCase();
		return samples.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
	}

	@McpComplete(prompt = "optionalDescription")
	public List<String> completeOptionalDescription(String value) {
		return List.of();
	}

	@McpComplete(uri = "demo://item/{id}")
	public List<String> completeItemId(String value) {
		return java.util.stream.IntStream.range(0, 30)
			.mapToObj(Integer::toString)
			.filter(s -> value == null || value.isBlank() || s.startsWith(value))
			.toList();
	}

	@McpComplete(uri = "demo://users/{id}")
	public List<String> completeUserId(String value) {
		List<String> samples = List.of("42", "100", "alice", "bob");
		if (value == null || value.isBlank()) {
			return samples;
		}
		return samples.stream().filter(s -> s.startsWith(value)).toList();
	}

}
