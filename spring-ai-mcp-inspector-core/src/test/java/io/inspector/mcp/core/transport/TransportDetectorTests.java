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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link TransportDetector}. */
@Epic("MCP Inspector Core")
@Feature("Transport detector")
class TransportDetectorTests {

	/**
	 * Creates a {@link ClassLoader} that simulates the presence or absence of Spring Web
	 * framework marker classes.
	 * @param hasDispatcherServlet whether {@code DispatcherServlet} should be visible
	 * @param hasDispatcherHandler whether {@code DispatcherHandler} should be visible
	 * @return a custom class loader for testing
	 */
	private static ClassLoader appClassLoader(final boolean hasDispatcherServlet, final boolean hasDispatcherHandler) {
		return new ClassLoader() {
			@Override
			public java.net.URL getResource(final String name) {
				if ("org/springframework/web/servlet/DispatcherServlet.class".equals(name)) {
					if (!hasDispatcherServlet) {
						return null;
					}
					return createDummyUrl();
				}
				if ("org/springframework/web/reactive/DispatcherHandler.class".equals(name)) {
					if (!hasDispatcherHandler) {
						return null;
					}
					return createDummyUrl();
				}
				return super.getResource(name);
			}

			private static java.net.URL createDummyUrl() {
				try {
					return new java.net.URL("file:///dummy-marker");
				}
				catch (final java.net.MalformedURLException ex) {
					throw new RuntimeException(ex);
				}
			}
		};
	}

	@Nested
	@DisplayName("detect()")
	class Detect {

		@Test
		@Story("SSE detection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() resolves the SSE transport with its sse and message endpoints from the protocol property")
		void detect_whenSseProtocol_detectsSseWithEndpoints() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.SSE);
			assertThat(detected.endpoint()).isEqualTo("/sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/mcp/message");
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBMVC);
		}

		@Test
		@Story("Streamable detection")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() resolves the streamable transport with /mcp endpoint and no message endpoint")
		void detect_whenStreamableProtocol_detectsStreamable() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL,
					"STREAMABLE");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STREAMABLE);
			assertThat(detected.endpoint()).isEqualTo("/mcp");
			assertThat(detected.messageEndpoint()).isNull();
		}

		@Test
		@Story("Stateless detection")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves the stateless transport with /mcp endpoint")
		void detect_whenStatelessProtocol_detectsStateless() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL,
					"STATELESS");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STATELESS);
			assertThat(detected.endpoint()).isEqualTo("/mcp");
		}

		@Test
		@Story("Stdio detection")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves a pure-stdio server with no HTTP endpoint and the stdio stack")
		void detect_whenStdioNoHttp_detectsStdioStack() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_STDIO, "true")
				.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "NONE");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.STDIO_NO_HTTP);
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_STDIO);
			assertThat(detected.endpoint()).isNull();
		}

		@Test
		@Story("Custom endpoints")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() honours custom SSE and message endpoint overrides")
		void detect_whenCustomSseEndpoints_honoursOverrides() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_SSE_ENDPOINT, "/custom-sse")
				.withProperty(TransportDetector.PROP_SSE_MESSAGE_ENDPOINT, "/custom-msg");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/custom-sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/custom-msg");
		}

		@Test
		@Story("Custom endpoints")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() honours a custom streamable MCP endpoint override")
		void detect_whenCustomMcpEndpoint_honoursOverride() {
			// given
			final MockEnvironment env = new MockEnvironment()
				.withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE")
				.withProperty(TransportDetector.PROP_MCP_ENDPOINT, "/custom-mcp");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/custom-mcp");
		}

		@Test
		@Story("Reactive stack")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() resolves the WebFlux stack when the web application type is reactive")
		void detect_whenReactiveWebAppType_detectsWebfluxStack() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "REACTIVE");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.stack()).isEqualTo(TransportDetector.STACK_WEBFLUX);
		}

		@Test
		@Story("Unknown transport")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() returns UNKNOWN when no protocol property is present")
		void detect_whenNoProtocol_returnsUnknown() {
			// given
			final MockEnvironment env = new MockEnvironment();

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.type()).isEqualTo(TransportType.UNKNOWN);
		}

	}

	@Nested
	@DisplayName("deployment prefix")
	class DeploymentPrefix {

		@Test
		@Story("Servlet context path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() prefixes both SSE endpoints with the servlet context path")
		void detect_withServletContextPath_prefixesSseEndpoints() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_SERVLET_CONTEXT_PATH, "/app");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/app/sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/app/mcp/message");
		}

		@Test
		@Story("Servlet context path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() prefixes the streamable MCP endpoint with the servlet context path")
		void detect_withServletContextPath_prefixesMcpEndpoint() {
			// given
			final MockEnvironment env = new MockEnvironment()
				.withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE")
				.withProperty(TransportDetector.PROP_SERVLET_CONTEXT_PATH, "/app/");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then — the trailing slash of the property must not survive
			assertThat(detected.endpoint()).isEqualTo("/app/mcp");
		}

		@Test
		@Story("WebFlux base path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detect() prefixes reactive endpoints with spring.webflux.base-path, ignoring the servlet property")
		void detect_withReactiveStack_usesWebfluxBasePath() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "STATELESS")
				.withProperty(TransportDetector.PROP_WEB_APP_TYPE, "REACTIVE")
				.withProperty(TransportDetector.PROP_WEBFLUX_BASE_PATH, "app")
				.withProperty(TransportDetector.PROP_SERVLET_CONTEXT_PATH, "/ignored");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then — a missing leading slash is normalised
			assertThat(detected.endpoint()).isEqualTo("/app/mcp");
		}

		@Test
		@Story("WebFlux base path")
		@Severity(SeverityLevel.BLOCKER)
		@Description("detect() honours spring.webflux.base-path on a stock WebFlux app, which leaves web-application-type unset")
		void detect_withBasePathAndNoWebApplicationType_prefixesEndpoint() {
			// given — Boot deduces REACTIVE from the classpath, so real WebFlux
			// applications never set spring.main.web-application-type
			final MockEnvironment env = new MockEnvironment()
				.withProperty(TransportDetector.PROP_PROTOCOL, "STREAMABLE")
				.withProperty(TransportDetector.PROP_WEBFLUX_BASE_PATH, "/app");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/app/mcp");
		}

		@Test
		@Story("No prefix")
		@Severity(SeverityLevel.NORMAL)
		@Description("detect() leaves endpoints untouched for a root-mounted application")
		void detect_withRootContextPath_leavesEndpointsUnprefixed() {
			// given
			final MockEnvironment env = new MockEnvironment().withProperty(TransportDetector.PROP_PROTOCOL, "SSE")
				.withProperty(TransportDetector.PROP_SERVLET_CONTEXT_PATH, "/");

			// when
			final DetectedTransport detected = new TransportDetector(env).detect();

			// then
			assertThat(detected.endpoint()).isEqualTo("/sse");
			assertThat(detected.messageEndpoint()).isEqualTo("/mcp/message");
		}

	}

	@Nested
	@DisplayName("detectAppStack()")
	class AppStackDetection {

		@Test
		@Story("WebMVC classpath")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detectAppStack() returns WEBMVC when only DispatcherServlet is on the classpath")
		void detectAppStack_whenOnlyDispatcherServlet_returnsWebMvc() {
			final ClassLoader cl = appClassLoader(true, false);

			final TransportDetector.AppStack stack = TransportDetector.detectAppStack(cl);

			assertThat(stack).isEqualTo(TransportDetector.AppStack.WEBMVC);
		}

		@Test
		@Story("WebFlux classpath")
		@Severity(SeverityLevel.CRITICAL)
		@Description("detectAppStack() returns WEBFLUX when only DispatcherHandler is on the classpath")
		void detectAppStack_whenOnlyDispatcherHandler_returnsWebFlux() {
			final ClassLoader cl = appClassLoader(false, true);

			final TransportDetector.AppStack stack = TransportDetector.detectAppStack(cl);

			assertThat(stack).isEqualTo(TransportDetector.AppStack.WEBFLUX);
		}

		@Test
		@Story("Both on classpath")
		@Severity(SeverityLevel.NORMAL)
		@Description("detectAppStack() returns UNKNOWN when both DispatcherServlet and DispatcherHandler are present")
		void detectAppStack_whenBothPresent_returnsUnknown() {
			final ClassLoader cl = appClassLoader(true, true);

			final TransportDetector.AppStack stack = TransportDetector.detectAppStack(cl);

			assertThat(stack).isEqualTo(TransportDetector.AppStack.UNKNOWN);
		}

		@Test
		@Story("Neither on classpath")
		@Severity(SeverityLevel.NORMAL)
		@Description("detectAppStack() returns UNKNOWN when neither framework is on the classpath")
		void detectAppStack_whenNeitherPresent_returnsUnknown() {
			final ClassLoader cl = appClassLoader(false, false);

			final TransportDetector.AppStack stack = TransportDetector.detectAppStack(cl);

			assertThat(stack).isEqualTo(TransportDetector.AppStack.UNKNOWN);
		}

		@Test
		@Story("Default class loader")
		@Severity(SeverityLevel.NORMAL)
		@Description("detectAppStack() with no argument returns UNKNOWN because core module has neither DispatcherServlet nor DispatcherHandler")
		void detectAppStack_whenDefaultClassLoader_returnsUnknown() {
			final TransportDetector.AppStack stack = TransportDetector.detectAppStack();

			assertThat(stack).isEqualTo(TransportDetector.AppStack.UNKNOWN);
		}

	}

}
