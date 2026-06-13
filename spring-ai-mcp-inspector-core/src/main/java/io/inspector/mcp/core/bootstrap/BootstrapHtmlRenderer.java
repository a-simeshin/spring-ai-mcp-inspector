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

package io.inspector.mcp.core.bootstrap;

import java.io.IOException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared helper that injects a serialised {@link InspectorBootstrap} into the inspector
 * {@code index.html} template. Used by both the servlet and reactive index handlers so
 * that the two stacks render identical bytes.
 *
 * <p>
 * Substitutes the literal placeholder {@value #PLACEHOLDER} with a
 * {@code <script>window.__MCP_INSPECTOR_BOOTSTRAP = ...;&lt;/script&gt;} block whose JSON
 * body is escaped against {@code &lt;/script&gt;} injection.
 *
 * @author Artem Simeshin
 */
public class BootstrapHtmlRenderer {

	/** Placeholder literal substituted in {@code index.html}. */
	public static final String PLACEHOLDER = "<!--MCP_INSPECTOR_BOOTSTRAP-->";

	private final JsonMapper objectMapper;

	public BootstrapHtmlRenderer(final JsonMapper objectMapper) {
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
	public String renderIndexHtml(final String htmlTemplate, final InspectorBootstrap bootstrap) throws IOException {
		final String json;
		try {
			json = this.objectMapper.writeValueAsString(bootstrap);
		}
		catch (final JacksonException ex) {
			// Jackson 3 surfaces serialization failures as an unchecked JacksonException;
			// preserve this renderer's IOException contract for callers.
			throw new IOException("Failed to serialize inspector bootstrap", ex);
		}
		// Escape any "</" sequence (notably "</script>") so injected JSON cannot
		// terminate the surrounding <script> element. The JS parser treats
		// "<\/" as the same character sequence as "</" inside a string literal.
		final String safeJson = json.replace("</", "<\\/");
		final String scriptBlock = "<script>window.__MCP_INSPECTOR_BOOTSTRAP = " + safeJson + ";</script>";
		return htmlTemplate.replace(PLACEHOLDER, scriptBlock);
	}

}
