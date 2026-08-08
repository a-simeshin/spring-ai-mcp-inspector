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

package io.inspector.mcp.core.transport;

import java.util.Locale;

import org.springframework.core.env.Environment;

/**
 * Reads the Spring {@link Environment} to figure out which MCP transport / stack the host
 * application is using. Web-stack-agnostic — does not depend on any servlet or reactor
 * types.
 *
 * <p>
 * Heuristics:
 *
 * <ul>
 * <li>If {@code spring.ai.mcp.server.stdio=true} <em>and</em>
 * {@code spring.main.web-application-type=NONE} →
 * {@link TransportType#STDIO_NO_HTTP}.</li>
 * <li>Otherwise {@code spring.ai.mcp.server.protocol} (SSE / STREAMABLE / STATELESS) is
 * used.</li>
 * <li>If neither stdio nor protocol is set, falls back to
 * {@link TransportType#UNKNOWN}.</li>
 * </ul>
 *
 * <p>
 * Detected endpoints are returned <em>already prefixed</em> with the application's
 * deployment prefix ({@value #PROP_SERVLET_CONTEXT_PATH} on servlet stacks,
 * {@value #PROP_WEBFLUX_BASE_PATH} on reactive ones). This class is the only producer of
 * {@link DetectedTransport#endpoint()} / {@link DetectedTransport#messageEndpoint()}, so
 * consumers must never prefix them a second time.
 *
 * @author Artem Simeshin
 */
public class TransportDetector {

	/**
	 * Property key for the MCP server protocol ({@code SSE}, {@code STREAMABLE}, etc.).
	 */
	public static final String PROP_PROTOCOL = "spring.ai.mcp.server.protocol";

	/** Property key for the stdio flag ({@code true} when running without HTTP). */
	public static final String PROP_STDIO = "spring.ai.mcp.server.stdio";

	/**
	 * Property key for the Spring web application type ({@code SERVLET},
	 * {@code REACTIVE}, {@code NONE}).
	 */
	public static final String PROP_WEB_APP_TYPE = "spring.main.web-application-type";

	/** Property key for the SSE endpoint path override. */
	public static final String PROP_SSE_ENDPOINT = "spring.ai.mcp.server.sse-endpoint";

	/** Property key for the SSE message endpoint path override. */
	public static final String PROP_SSE_MESSAGE_ENDPOINT = "spring.ai.mcp.server.sse-message-endpoint";

	/** Property key for the MCP endpoint path override (streamable / stateless). */
	public static final String PROP_MCP_ENDPOINT = "spring.ai.mcp.server.mcp-endpoint";

	/** Property key for the servlet context path prefixed onto detected endpoints. */
	public static final String PROP_SERVLET_CONTEXT_PATH = "server.servlet.context-path";

	/** Property key for the WebFlux base path prefixed onto detected endpoints. */
	public static final String PROP_WEBFLUX_BASE_PATH = "spring.webflux.base-path";

	/** Default SSE endpoint path used when no override is configured. */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** Default SSE message endpoint path used when no override is configured. */
	public static final String DEFAULT_SSE_MESSAGE_ENDPOINT = "/mcp/message";

	/** Default MCP endpoint path used when no override is configured. */
	public static final String DEFAULT_MCP_ENDPOINT = "/mcp";

	/** Stack label for Spring Web MVC (servlet) applications. */
	public static final String STACK_WEBMVC = "WEBMVC";

	/** Stack label for Spring WebFlux (reactive) applications. */
	public static final String STACK_WEBFLUX = "WEBFLUX";

	/** Stack label for pure stdio applications without an HTTP stack. */
	public static final String STACK_STDIO = "STDIO";

	private final Environment environment;

	public TransportDetector(final Environment environment) {
		this.environment = environment;
	}

	/**
	 * Performs detection.
	 * @return populated {@link DetectedTransport}; never {@code null}
	 */
	public DetectedTransport detect() {
		final boolean stdio = this.environment.getProperty(PROP_STDIO, Boolean.class, Boolean.FALSE);
		final String webAppType = normalize(this.environment.getProperty(PROP_WEB_APP_TYPE));

		if (stdio && "NONE".equals(webAppType)) {
			return new DetectedTransport(TransportType.STDIO_NO_HTTP, null, null, STACK_STDIO);
		}

		final String stack = ("REACTIVE".equals(webAppType)) ? STACK_WEBFLUX : STACK_WEBMVC;

		final String protocolRaw = this.environment.getProperty(PROP_PROTOCOL);
		final String protocol = normalize(protocolRaw);

		if (protocol == null || protocol.isEmpty()) {
			return new DetectedTransport(TransportType.UNKNOWN, null, null, stack);
		}

		// The endpoints leave this class already carrying the application's context
		// path: TransportDetector is the single producer of endpoint() /
		// messageEndpoint(), so prefixing here covers every consumer (loopback
		// clients, proxy target advertisement, bootstrap payload) at once. No
		// consumer may prefix again.
		final String prefix = contextPrefix(stack);

		return switch (protocol) {
			case "SSE" -> new DetectedTransport(TransportType.SSE,
					prefixed(prefix, this.environment.getProperty(PROP_SSE_ENDPOINT, DEFAULT_SSE_ENDPOINT)),
					prefixed(prefix,
							this.environment.getProperty(PROP_SSE_MESSAGE_ENDPOINT, DEFAULT_SSE_MESSAGE_ENDPOINT)),
					stack);
			case "STREAMABLE" -> new DetectedTransport(TransportType.STREAMABLE,
					prefixed(prefix, this.environment.getProperty(PROP_MCP_ENDPOINT, DEFAULT_MCP_ENDPOINT)), null,
					stack);
			case "STATELESS" -> new DetectedTransport(TransportType.STATELESS,
					prefixed(prefix, this.environment.getProperty(PROP_MCP_ENDPOINT, DEFAULT_MCP_ENDPOINT)), null,
					stack);
			default -> new DetectedTransport(TransportType.UNKNOWN, null, null, stack);
		};
	}

	/**
	 * Reads the stack-appropriate deployment prefix — {@value #PROP_SERVLET_CONTEXT_PATH}
	 * for servlet applications, {@value #PROP_WEBFLUX_BASE_PATH} for reactive ones.
	 * @param stack the detected stack label
	 * @return the normalised prefix without a trailing slash, or {@code ""} when none
	 */
	private String contextPrefix(final String stack) {
		final String raw = STACK_WEBFLUX.equals(stack) ? this.environment.getProperty(PROP_WEBFLUX_BASE_PATH)
				: this.environment.getProperty(PROP_SERVLET_CONTEXT_PATH);
		if (raw == null || raw.isBlank()) {
			return "";
		}
		final String trimmed = raw.trim();
		final String noTrailingSlash = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
		if (noTrailingSlash.isEmpty()) {
			return "";
		}
		return noTrailingSlash.startsWith("/") ? noTrailingSlash : "/" + noTrailingSlash;
	}

	private static String prefixed(final String prefix, final String path) {
		if (prefix.isEmpty() || path == null || path.isBlank()) {
			return path;
		}
		return path.startsWith("/") ? prefix + path : prefix + "/" + path;
	}

	private static String normalize(final String value) {
		return (value != null) ? value.trim().toUpperCase(Locale.ROOT) : null;
	}

}
