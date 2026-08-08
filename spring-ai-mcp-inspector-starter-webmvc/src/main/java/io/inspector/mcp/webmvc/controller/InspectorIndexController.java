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

package io.inspector.mcp.webmvc.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.bootstrap.BootstrapHtmlRenderer;
import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;

/**
 * Serves the inspector landing page and injects the typed bootstrap payload into the SPA
 * bundle as a single
 * {@code <script>window.__MCP_INSPECTOR_BOOTSTRAP = ...&lt;/script&gt;} block.
 *
 * <p>
 * Routes:
 *
 * <ul>
 * <li>{@code GET ${spring.ai.mcp.inspector.path}} → 302 to
 * {@code ${path}/index.html}.</li>
 * <li>{@code GET ${spring.ai.mcp.inspector.path}/} → same redirect.</li>
 * <li>{@code GET ${spring.ai.mcp.inspector.path}/index.html} → templated HTML with the
 * {@code <!--MCP_INSPECTOR_BOOTSTRAP-->} placeholder substituted by a single script block
 * carrying the JSON-serialised {@link InspectorBootstrap}.</li>
 * </ul>
 *
 * <p>
 * Other static assets (JS/CSS/images) under {@code ${path}/**} are served by the Spring
 * MVC resource handler registered in {@code McpInspectorWebMvcAutoConfiguration}.
 *
 * <h2>OAuth callback paths are intentionally hardcoded</h2>
 *
 * <p>
 * The upstream React bundle (App.tsx, OAuthDebugCallback.tsx) checks the literal strings
 * {@code "/oauth/callback"} and {@code "/oauth/callback/debug"} against
 * {@code window.location.pathname} — both callback routes therefore stay mounted at the
 * top level even when {@code spring.ai.mcp.inspector.path} is customised. Relocating them
 * would require patching the upstream JS, which is out of scope for the
 * path-parametrization phase.
 *
 * @author Artem Simeshin
 */
@RestController
public class InspectorIndexController {

	static final String INDEX_RESOURCE = "mcp-inspector-bundle/index.html";

	private final InspectorBootstrapAssembler assembler;

	private final BootstrapHtmlRenderer renderer;

	private final String inspectorPath;

	private final String indexPath;

	public InspectorIndexController(final InspectorBootstrapAssembler assembler, final BootstrapHtmlRenderer renderer,
			@Value("${spring.ai.mcp.inspector.path:/mcp-inspector}") final String inspectorPath) {
		this.assembler = assembler;
		this.renderer = renderer;
		this.inspectorPath = inspectorPath;
		this.indexPath = inspectorPath + "/index.html";
	}

	/**
	 * Redirects the inspector root to {@code index.html}.
	 * @param request the current request, read for its context path
	 * @return a 302 to the context-path-aware {@code index.html}
	 */
	@GetMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}")
	public ResponseEntity<Void> redirectRoot(final HttpServletRequest request) {
		return redirectToIndex(request);
	}

	/**
	 * Trailing-slash variant of {@link #redirectRoot(HttpServletRequest)}.
	 * @param request the current request, read for its context path
	 * @return a 302 to the context-path-aware {@code index.html}
	 */
	@GetMapping("${spring.ai.mcp.inspector.path:/mcp-inspector}/")
	public ResponseEntity<Void> redirectTrailingSlash(final HttpServletRequest request) {
		return redirectToIndex(request);
	}

	/**
	 * Serves the templated SPA at the top-level OAuth callback path so the upstream React
	 * client (which checks {@code window.location.pathname === "/oauth/callback"} in
	 * {@code App.tsx}) can claim the URL after the IdP redirect. Without this route the
	 * browser receives a 404 before the SPA boots.
	 *
	 * <p>
	 * This path stays hardcoded by design — see the class javadoc.
	 * @param request the current request, read for its context path
	 * @return the rendered index HTML response
	 * @throws IOException if the bundle resource cannot be read
	 */
	@GetMapping(path = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> oauthCallback(final HttpServletRequest request) throws IOException {
		return index(request);
	}

	/**
	 * Variant of {@link #oauthCallback()} for the debug flow
	 * ({@code OAuthDebugCallback.tsx}), which the SPA renders when
	 * {@code window.location.pathname === "/oauth/callback/debug"}.
	 *
	 * <p>
	 * This path stays hardcoded by design — see the class javadoc.
	 * @param request the current request, read for its context path
	 * @return the rendered index HTML response
	 * @throws IOException if the bundle resource cannot be read
	 */
	@GetMapping(path = "/oauth/callback/debug", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> oauthCallbackDebug(final HttpServletRequest request) throws IOException {
		return index(request);
	}

	/**
	 * Serves the templated SPA. Asset URLs and the bootstrap proxy address are rewritten
	 * to carry the request's context path (or reverse-proxy prefix).
	 * @param request the current request, read for its context path
	 * @return the rendered index HTML response
	 * @throws IOException if the bundle resource cannot be read
	 */
	@GetMapping(path = "${spring.ai.mcp.inspector.path:/mcp-inspector}/index.html",
			produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> index(final HttpServletRequest request) throws IOException {
		// Pin to this class's classloader rather than the thread-context loader,
		// which in multi-context test scenarios (servlet + reactive back-to-back)
		// may be a stopped Tomcat WebappClassLoader and would throw "Illegal access:
		// this web application instance has been stopped already".
		final ClassPathResource resource = new ClassPathResource(INDEX_RESOURCE,
				InspectorIndexController.class.getClassLoader());
		if (!resource.exists()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.contentType(MediaType.TEXT_HTML)
				.body("<!doctype html><title>MCP Inspector</title>" + "<p>UI bundle missing: " + INDEX_RESOURCE
						+ " not on classpath.</p>");
		}
		final String body;
		try (InputStream in = resource.getInputStream()) {
			body = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
		final String prefix = contextPath(request);
		final InspectorBootstrap bootstrap = this.assembler.assemble(prefix);
		final String rendered = this.renderer.renderIndexHtml(body, bootstrap, prefix + this.inspectorPath);

		final HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_HTML);
		headers.setCacheControl("no-cache, no-store, must-revalidate");
		headers.setPragma("no-cache");
		return new ResponseEntity<>(rendered, headers, HttpStatus.OK);
	}

	private ResponseEntity<Void> redirectToIndex(final HttpServletRequest request) {
		final HttpHeaders headers = new HttpHeaders();
		// A manually set Location is NOT context-path-prepended by the container —
		// unlike a "redirect:" view or sendRedirect() — so the prefix goes in here.
		headers.setLocation(URI.create(contextPath(request) + this.indexPath));
		return new ResponseEntity<>(headers, HttpStatus.FOUND);
	}

	private static String contextPath(final HttpServletRequest request) {
		// A root-mounted application reports "" per the servlet spec; a container
		// reporting "/" instead would yield protocol-relative "//..." URLs.
		final String contextPath = request.getContextPath();
		return "/".equals(contextPath) ? "" : contextPath;
	}

}
