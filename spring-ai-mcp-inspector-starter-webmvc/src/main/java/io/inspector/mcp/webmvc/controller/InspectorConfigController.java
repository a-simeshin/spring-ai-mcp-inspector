/*
 * Copyright 2025-present the original author or authors.
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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;

/**
 * Exposes the typed {@link InspectorBootstrap} payload as JSON at {@code GET
 * ${spring.ai.mcp.inspector.path}/config}.
 *
 * <p>
 * The body is identical in content to the inline script block injected by
 * {@link InspectorIndexController} into {@code index.html}. Exposing the same payload as
 * a dedicated JSON endpoint is useful for:
 *
 * <ul>
 * <li>integration tests asserting the bootstrap shape without parsing HTML;</li>
 * <li>SPA refresh paths that want to re-read the configuration after boot without a full
 * page reload;</li>
 * <li>secondary consumers (browser extensions, external automation tooling) that need
 * machine-readable access to the same boot state the inline script delivers.</li>
 * </ul>
 *
 * <p>
 * The endpoint is intentionally NOT placed behind {@code InspectorAuthFilter} (which only
 * matches {@code ${path}/api/*}) — it serves the auth token TO the SPA, so it cannot
 * itself require the token.
 *
 * @author Artem Simeshin
 */
@RestController
public class InspectorConfigController {

	private final InspectorBootstrapAssembler assembler;

	public InspectorConfigController(final InspectorBootstrapAssembler assembler) {
		this.assembler = assembler;
	}

	@GetMapping(path = "${spring.ai.mcp.inspector.path:/mcp-inspector}/config",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<InspectorBootstrap> config() {
		final InspectorBootstrap bootstrap = this.assembler.assemble();
		final HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setCacheControl("no-cache, no-store, must-revalidate");
		headers.setPragma("no-cache");
		return new ResponseEntity<>(bootstrap, headers, HttpStatus.OK);
	}

}
