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

package io.inspector.mcp.webflux.router;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.client.ExternalStdioClientFactory;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.transport.TransportDetector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link InspectorHandler} is public and part of the 1.0.0 surface, so it must leave the
 * single permitted {@link ApplicationListener} parameterisation to its subclasses — see
 * the sibling test in the core module for the full argument. The class below simply has
 * to compile.
 */
@Epic("WebFlux Inspector")
@Feature("Inspector handler")
class InspectorHandlerSubclassCompatibilityTests {

	@Test
	@DisplayName("a subclass may still be an ApplicationListener of some other event")
	@Story("API compatibility")
	@Severity(SeverityLevel.NORMAL)
	@Description("Compiles a subclass that implements ApplicationListener<ApplicationReadyEvent>, which is "
			+ "impossible while InspectorHandler itself implements ApplicationListener<ContextClosedEvent>")
	void subclass_mayImplementApplicationListenerForAnotherEvent() {
		final UserHandler handler = new UserHandler();

		assertThat(handler).isInstanceOf(ApplicationListener.class);
		assertThat(handler.hasSession("nope")).isFalse();
	}

	/** The reviewer's probe, applied to the reactive handler. */
	static class UserHandler extends InspectorHandler implements ApplicationListener<ApplicationReadyEvent> {

		UserHandler() {
			super(mock(TransportDetector.class), mock(LoopbackMcpClientFactory.class),
					mock(ExternalStdioClientFactory.class), mock(InspectorAuthTokenProvider.class), new JsonMapper());
		}

		@Override
		public void onApplicationEvent(final ApplicationReadyEvent event) {
			// a host application's own startup hook — the point is only that it compiles
		}

	}

}
