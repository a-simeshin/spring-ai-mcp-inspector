/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.Annotations;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Schema-rich demo MCP tools added on top of the basic {@link DemoToolsProvider}.
 *
 * <p>
 * Every method here exercises a specific UI affordance of the upstream Inspector:
 * <ul>
 * <li>numeric / array / boolean / optional / nested-object widget rendering in
 * DynamicJsonForm</li>
 * <li>error-path rendering in the ToolResults panel</li>
 * <li>large-payload streaming and stability checks</li>
 * <li>structured / multi-content (text + image + resource link) rendering</li>
 * <li>long-running tool state in the UI</li>
 * <li>recursive JsonView for deep nested JSON</li>
 * <li>{@code notifications/message} emission via
 * {@link McpSyncServerExchange#loggingNotification}</li>
 * </ul>
 *
 * <p>
 * None of these methods overlap with the {@code echo}, {@code sum}, or
 * {@code currentTime} tools — backwards-compatibility for existing IT suites is
 * preserved.
 */
@Component
public class DemoAdvancedToolsProvider {

	/** 10×10 PNG (a 1×1 transparent stub blown up; minimal valid base64-encoded PNG). */
	private static final String TINY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

	/**
	 * Hard cap so {@link #deepJson(int)} cannot blow the stack or balloon the payload.
	 */
	private static final int DEEP_JSON_MAX_DEPTH = 50;

	/**
	 * Hard cap so {@link #largeOutput(int)} stays predictable in CI (~8 MiB worst case).
	 */
	private static final int LARGE_OUTPUT_MAX_KB = 8 * 1024;

	/**
	 * Plain numeric widget — exercises the {@code number} input in DynamicJsonForm.
	 */
	@McpTool(name = "addNumbers", description = "Add two floating-point numbers")
	public double addNumbers(@McpToolParam(description = "first addend", required = true) double x,
			@McpToolParam(description = "second addend", required = true) double y) {
		return x + y;
	}

	/**
	 * Array widget + string widget — exercises DynamicJsonForm's array editor.
	 */
	@McpTool(name = "concatenate", description = "Join string parts with a separator")
	public String concatenate(@McpToolParam(description = "string parts to join", required = true) List<String> parts,
			@McpToolParam(description = "separator between parts", required = true) String separator) {
		if (parts == null || parts.isEmpty()) {
			return "";
		}
		return String.join(separator == null ? "" : separator, parts);
	}

	/**
	 * Single object parameter — exercises nested JSON object widget rendering.
	 */
	@McpTool(name = "lookupUser", description = "Lookup a user by structured profile object")
	public String lookupUser(
			@McpToolParam(description = "User profile (name, email, age)", required = true) UserProfile user) {
		return "User: " + user.name() + " <" + user.email() + "> (age " + user.age() + ")";
	}

	/**
	 * Profile payload for {@link #lookupUser(UserProfile)} — generates a nested JSON
	 * schema.
	 */
	public record UserProfile(String name, String email, int age) {
	}

	/**
	 * Enum widget — exercises the dropdown/select kind in DynamicJsonForm. Java enum is
	 * auto-converted to a JSON-schema {@code "enum"} array by the schema generator
	 * shipped with mcp-annotations 0.9.0 (see {@code SpringAiSchemaModule}).
	 */
	@McpTool(name = "chooseColor", description = "Pick a color from a fixed palette")
	public String chooseColor(
			@McpToolParam(description = "Color choice (red, green, blue)", required = true) Color color) {
		return "You chose " + color.name().toLowerCase();
	}

	/** Color palette enum — renders as a JSON-schema {@code enum} property. */
	public enum Color {

		RED, GREEN, BLUE

	}

	/**
	 * Boolean widget — exercises the checkbox kind in DynamicJsonForm.
	 */
	@McpTool(name = "toggleFlag", description = "Echo a boolean flag")
	public String toggleFlag(@McpToolParam(description = "flag value", required = true) boolean enabled) {
		return enabled ? "flag is ON" : "flag is OFF";
	}

	/**
	 * Optional-parameter widget — UI must render the {@code suffix} field as not-required
	 * (no asterisk).
	 */
	@McpTool(name = "optionalGreeting", description = "Greet a user with an optional suffix")
	public String optionalGreeting(@McpToolParam(description = "name to greet", required = true) String name,
			@McpToolParam(description = "optional suffix (e.g. '!!!')", required = false) String suffix) {
		return "Hello, " + name + (suffix == null || suffix.isEmpty() ? "" : suffix);
	}

	/**
	 * Always throws — exercises the error-path rendering in ToolResults panel.
	 */
	@McpTool(name = "errorTool", description = "Always throws — exercises the ToolResults error rendering")
	public String errorTool() {
		throw new RuntimeException("intentional demo failure");
	}

	/**
	 * Large payload — exercises UI streaming/scroll/virtualization for big TextContent.
	 *
	 * <p>
	 * Also emits a {@code notifications/message} via the server exchange so the UI's
	 * notification panel lights up when this tool runs. The exchange parameter is
	 * injected automatically by mcp-annotations when present in the signature.
	 * @param sizeKb requested size in KiB (default 1024 = 1 MiB, capped at
	 * {@value #LARGE_OUTPUT_MAX_KB})
	 */
	@McpTool(name = "largeOutput", description = "Return a large text payload of ~sizeKb KiB")
	public String largeOutput(McpSyncServerExchange exchange,
			@McpToolParam(description = "payload size in KiB (default 1024)", required = false) Integer sizeKb) {
		int kb = sizeKb == null ? 1024 : Math.max(1, Math.min(sizeKb, LARGE_OUTPUT_MAX_KB));

		// Emit a server-side logging notification — the inspector UI surfaces these.
		// Best-effort: log notifications require the client to have called
		// `logging/setLevel`; if not, the SDK silently drops them, which is fine for a
		// demo.
		try {
			exchange.loggingNotification(LoggingMessageNotification.builder()
				.level(LoggingLevel.INFO)
				.logger("demo.largeOutput")
				.data("Emitting " + kb + " KiB of payload")
				.build());
		}
		catch (Exception ignored) {
			/* exchange may be null in some transports/tests — non-fatal */
		}

		int totalChars = kb * 1024;
		StringBuilder sb = new StringBuilder(totalChars);
		// Use a repeating pattern that is still mildly compressible — avoids accidentally
		// exposing pathological worst-case behaviour in any encoder along the wire.
		String chunk = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";
		while (sb.length() < totalChars) {
			sb.append(chunk);
		}
		sb.setLength(totalChars);
		return sb.toString();
	}

	/**
	 * Returns both a structuredContent map and a TextContent — exercises the Structured /
	 * Text toggle in the ToolResults panel.
	 */
	@McpTool(name = "structuredOutput", description = "Return both structuredContent and a text representation")
	public CallToolResult structuredOutput() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("status", "ok");
		data.put("count", 3);
		data.put("items", List.of("alpha", "beta", "gamma"));

		List<Content> contents = List.<Content>of(new TextContent("status=ok count=3 items=[alpha, beta, gamma]"));

		return CallToolResult.builder().content(contents).isError(Boolean.FALSE).structuredContent(data).build();
	}

	/**
	 * Mixed content (text + image + embedded resource) — exercises ResourceLinkView,
	 * IconDisplay, and multi-content rendering.
	 */
	@McpTool(name = "multiContent", description = "Return text + a small PNG image + an embedded resource")
	public CallToolResult multiContent() {
		// ImageContent(Annotations annotations, String data, String mimeType)
		// Use the 3-arg constructor with `null` annotations to keep it simple.
		ImageContent image = new ImageContent((Annotations) null, TINY_PNG_BASE64, "image/png");

		// ResourceLink is a Content type pointing to a resource by URI.
		// Builder:
		// ResourceLink.builder().name(...).uri(...).description(...).mimeType(...).build()
		ResourceLink link = ResourceLink.builder()
			.name("demo-greeting")
			.uri("demo://greeting")
			.description("Static greeting message exposed via MCP resources API")
			.mimeType("text/plain")
			.build();

		// EmbeddedResource wraps a ResourceContents inline so the inspector can preview
		// it.
		EmbeddedResource embedded = new EmbeddedResource(new Annotations(List.of(Role.USER), 0.4),
				new TextResourceContents("demo://greeting", "text/plain", "Hello from MCP demo"));

		List<Content> contents = List.<Content>of(
				new TextContent("Multi-content payload: text + image + resource link + embedded resource"), image, link,
				embedded);
		return CallToolResult.builder().content(contents).isError(Boolean.FALSE).build();
	}

	/**
	 * Slow tool — exercises the "long-running" / spinner UI state.
	 */
	@McpTool(name = "slowEcho", description = "Echo text after a ~2 second delay")
	public String slowEcho(@McpToolParam(description = "text to echo (slowly)", required = true) String text) {
		try {
			Thread.sleep(2000L);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("slowEcho interrupted", e);
		}
		return text;
	}

	/**
	 * Build a structurally nested map of {@code depth} levels — exercises the recursive
	 * JsonView renderer. Depth is capped at {@value #DEEP_JSON_MAX_DEPTH}.
	 */
	@McpTool(name = "deepJson", description = "Return a JSON object nested N levels deep (cap 50)")
	public Map<String, Object> deepJson(
			@McpToolParam(description = "nesting depth (1..50)", required = true) int depth) {
		int d = Math.max(1, Math.min(depth, DEEP_JSON_MAX_DEPTH));
		Map<String, Object> root = new LinkedHashMap<>();
		Map<String, Object> cursor = root;
		for (int i = 0; i < d - 1; i++) {
			Map<String, Object> next = new LinkedHashMap<>();
			cursor.put("a", next);
			cursor = next;
		}
		cursor.put("a", "leaf");
		return root;
	}

	/**
	 * Blob resource — exercises BlobResourceContents in an EmbeddedResource. Kept as a
	 * tool (not a resource) so we can vary the payload at call time without adding
	 * more @McpResource methods.
	 */
	@McpTool(name = "blobAttachment", description = "Return an embedded binary (PNG) resource alongside text")
	public CallToolResult blobAttachment() {
		EmbeddedResource embedded = new EmbeddedResource((Annotations) null,
				new BlobResourceContents("demo://logo.png", "image/png", TINY_PNG_BASE64));
		List<Content> contents = List.<Content>of(new TextContent("Attached PNG blob"), embedded);
		return CallToolResult.builder().content(contents).isError(Boolean.FALSE).build();
	}

}
