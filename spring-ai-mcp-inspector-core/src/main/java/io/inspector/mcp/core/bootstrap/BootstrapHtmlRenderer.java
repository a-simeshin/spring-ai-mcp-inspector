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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/**
	 * Asset base every URL in the bundle is built with. Duplicated from the UI build —
	 * keep in lockstep with {@code base} in
	 * {@code spring-ai-mcp-inspector-ui/upstream-client/vite.config.ts}. The trailing
	 * slash is part of the literal: it is what makes the rewrite match only asset URLs
	 * ({@code /mcp-inspector/assets/...}) and not the sibling proxy prefix
	 * ({@code /mcp-inspector-api}).
	 */
	public static final String BUNDLE_ASSET_BASE = "/mcp-inspector/";

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
		return renderIndexHtml(htmlTemplate, bootstrap, BUNDLE_ASSET_BASE);
	}

	/**
	 * Renders the inspector index HTML with the bootstrap script injected and every
	 * bundle asset URL repointed at {@code assetBasePath}.
	 *
	 * <p>
	 * Needed whenever the bundle is not served from {@value #BUNDLE_ASSET_BASE} — under a
	 * servlet context path, a WebFlux base path, a reverse-proxy prefix, or a customised
	 * {@code spring.ai.mcp.inspector.path}. Every link the template emits is
	 * path-absolute, so a {@code <base href>} would be inert; the URLs themselves have to
	 * be rewritten.
	 *
	 * <p>
	 * Scope: the served HTML only. The bundle's JS keeps its build-time base for
	 * dynamically imported chunks (Vite's {@code assetsURL} helper), so the code-split
	 * OAuth callback chunks are still fetched from {@value #BUNDLE_ASSET_BASE} — matching
	 * the documented limitation that OAuth does not work under a deployment prefix.
	 * @param htmlTemplate the raw {@code index.html} contents
	 * @param bootstrap the bootstrap payload to embed
	 * @param assetBasePath the path the bundle is actually served from, e.g.
	 * {@code /app/mcp-inspector}; a trailing slash is optional
	 * @return the rendered HTML
	 * @throws IOException if Jackson serialisation fails
	 */
	public String renderIndexHtml(final String htmlTemplate, final InspectorBootstrap bootstrap,
			final String assetBasePath) throws IOException {
		// Rewrite the RAW template, before the bootstrap script is injected. Doing it
		// afterwards would also hit any injected value containing the asset base — a
		// proxy path such as "/mcp-inspector/api" under context path "/app" would be
		// advertised as "/app/app/mcp-inspector/api" and every proxy call would 404.
		final String template = rewriteAssetUrls(htmlTemplate, assetBasePath);
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
		// Additionally neutralise HTML comment delimiters ("<!--" / "-->") so a
		// bootstrap value cannot break out of a comment or script context.
		final String safeJson = json.replace("</", "<\\/").replace("<!--", "<\\!--").replace("-->", "--\\>");
		final String scriptBlock = "<script>window.__MCP_INSPECTOR_BOOTSTRAP = " + safeJson + ";</script>";
		// Replace only the FIRST placeholder occurrence: the served index.html may
		// contain the literal placeholder a second time inside a documentation
		// comment, and String.replace would inject the script (and auth token) twice.
		return template.replaceFirst(Pattern.quote(PLACEHOLDER), Matcher.quoteReplacement(scriptBlock));
	}

	private static String rewriteAssetUrls(final String htmlTemplate, final String assetBasePath) {
		if (assetBasePath == null || assetBasePath.isBlank()) {
			return htmlTemplate;
		}
		final String withSlash = assetBasePath.endsWith("/") ? assetBasePath : assetBasePath + "/";
		if (BUNDLE_ASSET_BASE.equals(withSlash)) {
			return htmlTemplate;
		}
		return htmlTemplate.replace(BUNDLE_ASSET_BASE, withSlash);
	}

}
