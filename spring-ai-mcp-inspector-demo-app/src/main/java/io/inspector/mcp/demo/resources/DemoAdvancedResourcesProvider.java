/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.resources;

import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;

import java.util.List;

import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * Schema-rich demo MCP resources on top of {@link DemoResourcesProvider}.
 *
 * <p>
 * Exercises every resource-side UI affordance the inspector renders:
 * <ul>
 * <li>30 static resources for pagination / virtualization UI</li>
 * <li>large text resource for streaming / overflow handling</li>
 * <li>markdown + JSON mime types for syntax-highlighted preview</li>
 * <li>blob (image/png) resource for hex/preview toggle</li>
 * <li>URI-template resource {@code demo://users/{id}} for the template binding UI</li>
 * </ul>
 *
 * <p>
 * Note on URI templates: the resource provider in mcp-annotations 0.9.0 routes templated
 * URIs (any URI containing a {@code {var}} segment) through
 * {@code SyncResourceTemplateSpecification} rather than as a concrete {@code Resource}.
 * The method must accept a {@code String} parameter named exactly like the URI variable.
 */
@Component
public class DemoAdvancedResourcesProvider {

	/** Static items 0..29 used by {@link #item(String)}. */
	public static final int STATIC_ITEM_COUNT = 30;

	/**
	 * Same 1×1 transparent PNG used elsewhere in the demo — Base64 of a minimal valid
	 * PNG.
	 */
	private static final String TINY_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

	/** ~256 KiB lorem-ipsum payload generated lazily once and cached. */
	private static final String LARGE_TEXT = buildLargeText(256);

	/**
	 * URI-template resource — exercises the URI-template binding widget. The {@code id}
	 * method parameter is bound from the {@code {id}} variable in the URI.
	 */
	@McpResource(uri = "demo://item/{id}", name = "demo-item",
			description = "Indexed demo item exposed via URI template (id is 0..29)", mimeType = "text/plain")
	public String item(String id) {
		try {
			int idx = Integer.parseInt(id);
			if (idx < 0 || idx >= STATIC_ITEM_COUNT) {
				return "Unknown item " + id;
			}
			return "Item " + idx;
		}
		catch (NumberFormatException e) {
			return "Unknown item " + id;
		}
	}

	/**
	 * Large text resource — exercises overflow / virtualization in the ResourceView.
	 */
	@McpResource(uri = "demo://large-text", name = "demo-large-text",
			description = "~256KB of lorem-ipsum text — exercises large-payload UI", mimeType = "text/plain")
	public String largeText() {
		return LARGE_TEXT;
	}

	/**
	 * Markdown resource — exercises the markdown preview pane.
	 */
	@McpResource(uri = "demo://readme.md", name = "demo-readme",
			description = "Markdown demo content — exercises the markdown preview pane", mimeType = "text/markdown")
	public String readme() {
		return String.join("\n", "# MCP Inspector Demo", "",
				"This resource exercises the **markdown** preview pane of the inspector UI.", "", "## Sections", "",
				"- _Italic_ rendering", "- **Bold** rendering", "- `inline code`", "", "```java",
				"System.out.println(\"hello, mcp\");", "```", "");
	}

	/**
	 * JSON resource — exercises the JSON syntax-highlighted preview.
	 */
	@McpResource(uri = "demo://config.json", name = "demo-config",
			description = "Small JSON payload — exercises the JSON preview pane", mimeType = "application/json")
	public String config() {
		return "{\n" + "  \"name\": \"mcp-inspector-demo\",\n" + "  \"version\": \"0.1.0\",\n"
				+ "  \"features\": [\"tools\", \"resources\", \"prompts\"],\n"
				+ "  \"limits\": { \"maxConnections\": 32, \"timeoutSeconds\": 30 }\n" + "}";
	}

	/**
	 * Blob resource — exercises BlobResourceContents rendering (image preview / hex-dump
	 * toggle). Returns a {@link ReadResourceResult} directly so we can supply
	 * {@link BlobResourceContents} verbatim (the default converter would choose BLOB
	 * based on mime, but returning the typed result is more explicit).
	 */
	@McpResource(uri = "demo://logo.png", name = "demo-logo",
			description = "Tiny PNG (base64-encoded) — exercises BlobResourceContents UI", mimeType = "image/png")
	public ReadResourceResult logo() {
		ResourceContents blob = new BlobResourceContents("demo://logo.png", "image/png", TINY_PNG_BASE64);
		return new ReadResourceResult(List.of(blob));
	}

	/**
	 * URI-template resource — exercises the URI-template binding widget. Distinct from
	 * {@link #item(String)} so we have one template returning user-style data and one
	 * returning indexed items.
	 */
	@McpResource(uri = "demo://users/{id}", name = "demo-user",
			description = "User-shaped record looked up by URI variable {id}", mimeType = "text/plain")
	public String user(String id) {
		return "User " + id;
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private static String buildLargeText(int kib) {
		int total = kib * 1024;
		StringBuilder sb = new StringBuilder(total);
		String chunk = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
				+ "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ";
		while (sb.length() < total) {
			sb.append(chunk);
		}
		sb.setLength(total);
		return sb.toString();
	}

}
