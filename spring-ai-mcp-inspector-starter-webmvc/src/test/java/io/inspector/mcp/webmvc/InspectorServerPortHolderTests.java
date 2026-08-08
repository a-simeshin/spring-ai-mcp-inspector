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

package io.inspector.mcp.webmvc;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InspectorServerPortHolder} — captures the embedded server port at
 * startup and falls back to 8080 before the event fires.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorServerPortHolder")
class InspectorServerPortHolderTests {

	@Nested
	@DisplayName("port()")
	class Port {

		@Test
		@Story("Port resolution")
		@Severity(SeverityLevel.NORMAL)
		@Description("port() falls back to 8080 before the web-server-initialized event fires")
		void port_beforeEvent_returnsDefault8080() {
			// given
			final InspectorServerPortHolder holder = new InspectorServerPortHolder();

			// when
			final int port = holder.port();

			// then
			assertThat(port).isEqualTo(8080);
		}

		@Test
		@Story("Port resolution")
		@Severity(SeverityLevel.CRITICAL)
		@Description("onWebServerInitialized() captures the actual port and port() returns it")
		void port_afterEvent_returnsCapturedPort() {
			// given — the primary context reports no server namespace
			final InspectorServerPortHolder holder = new InspectorServerPortHolder();
			final WebServer webServer = mock(WebServer.class);
			given(webServer.getPort()).willReturn(54321);
			final WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
			given(event.getWebServer()).willReturn(webServer);
			given(event.getApplicationContext()).willReturn(mock(WebServerApplicationContext.class));

			// when
			holder.onWebServerInitialized(event);

			// then
			assertThat(holder.port()).isEqualTo(54321);
		}

		@Test
		@Story("Port resolution")
		@Severity(SeverityLevel.MINOR)
		@Description("port() falls back to 8080 when the captured port is non-positive")
		void port_whenCapturedNonPositive_returnsDefault() {
			// given
			final InspectorServerPortHolder holder = new InspectorServerPortHolder();
			final WebServer webServer = mock(WebServer.class);
			given(webServer.getPort()).willReturn(0);
			final WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
			given(event.getWebServer()).willReturn(webServer);

			// when
			holder.onWebServerInitialized(event);

			// then
			assertThat(holder.port()).isEqualTo(8080);
		}

		@Test
		@Story("Management server guard")
		@Severity(SeverityLevel.CRITICAL)
		@Description("An event from the management context is ignored so the actuator port never becomes the loopback port")
		void onWebServerInitialized_withManagementNamespace_isIgnored() {
			// given — the main server started on 54321, the actuator on 9999
			final InspectorServerPortHolder holder = new InspectorServerPortHolder();
			final WebServer mainServer = mock(WebServer.class);
			given(mainServer.getPort()).willReturn(54321);
			final WebServerInitializedEvent mainEvent = mock(WebServerInitializedEvent.class);
			given(mainEvent.getWebServer()).willReturn(mainServer);

			final WebServer managementServer = mock(WebServer.class);
			given(managementServer.getPort()).willReturn(9999);
			final WebServerApplicationContext managementContext = mock(WebServerApplicationContext.class);
			given(managementContext.getServerNamespace()).willReturn("management");
			final WebServerInitializedEvent managementEvent = mock(WebServerInitializedEvent.class);
			given(managementEvent.getWebServer()).willReturn(managementServer);
			given(managementEvent.getApplicationContext()).willReturn(managementContext);

			// when — the management event arrives last
			holder.onWebServerInitialized(mainEvent);
			holder.onWebServerInitialized(managementEvent);

			// then
			assertThat(holder.port()).isEqualTo(54321);
		}

	}

}
