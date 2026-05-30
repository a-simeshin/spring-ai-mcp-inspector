/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared helper that injects a serialised {@link InspectorBootstrap} into the inspector
 * {@code index.html} template. Used by both the servlet and reactive index handlers so
 * that the two stacks render identical bytes.
 *
 * <p>
 * Substitutes the literal placeholder {@value #PLACEHOLDER} with a
 * {@code <script>window.__MCP_INSPECTOR_BOOTSTRAP = ...;</script>} block whose JSON body
 * is escaped against {@code </script>} injection.
 */
public class BootstrapHtmlRenderer {

	/** Placeholder literal substituted in {@code index.html}. */
	public static final String PLACEHOLDER = "<!--MCP_INSPECTOR_BOOTSTRAP-->";

	private final ObjectMapper objectMapper;

	public BootstrapHtmlRenderer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Renders the inspector index HTML with the bootstrap script injected.
	 * @param htmlTemplate the raw {@code index.html} contents; must contain the
	 * {@link #PLACEHOLDER} literal (if missing the template is returned untouched)
	 * @param bootstrap the bootstrap payload to embed
	 * @return the rendered HTML
	 * @throws IOException if Jackson serialisation fails
	 */
	public String renderIndexHtml(String htmlTemplate, InspectorBootstrap bootstrap) throws IOException {
		String json = objectMapper.writeValueAsString(bootstrap);
		// Escape any "</" sequence (notably "</script>") so injected JSON cannot
		// terminate the surrounding <script> element. The JS parser treats
		// "<\/" as the same character sequence as "</" inside a string literal.
		String safeJson = json.replace("</", "<\\/");
		String scriptBlock = "<script>window.__MCP_INSPECTOR_BOOTSTRAP = " + safeJson + ";</script>";
		return htmlTemplate.replace(PLACEHOLDER, scriptBlock);
	}

}
