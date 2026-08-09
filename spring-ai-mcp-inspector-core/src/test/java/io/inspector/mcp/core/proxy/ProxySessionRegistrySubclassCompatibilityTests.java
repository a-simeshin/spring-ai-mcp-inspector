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

package io.inspector.mcp.core.proxy;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProxySessionRegistry} is public and user-extensible, so it must not consume the
 * one {@link ApplicationListener} parameterisation a subclass is allowed to have.
 *
 * <p>
 * Java permits a generic interface to appear only once per hierarchy. While the registry
 * declared {@code implements ApplicationListener<ContextClosedEvent>}, the subclass below
 * did not compile:
 *
 * <pre>
 * error: ApplicationListener cannot be inherited with different arguments:
 *        &lt;ApplicationReadyEvent&gt; and &lt;ContextClosedEvent&gt;
 * </pre>
 *
 * <p>
 * That would have been a source-incompatible change in a patch release, for no benefit —
 * an {@code @EventListener}-annotated method gives identical timing. This test is a
 * compile-time assertion: if the interface ever comes back, the test sources stop
 * compiling and the build fails before any assertion runs.
 */
@Epic("MCP Inspector Core")
@Feature("Proxy session registry")
class ProxySessionRegistrySubclassCompatibilityTests {

	@Test
	@DisplayName("a subclass may still be an ApplicationListener of some other event")
	@Story("API compatibility")
	@Severity(SeverityLevel.NORMAL)
	@Description("Compiles a subclass that implements ApplicationListener<ApplicationReadyEvent>, which is "
			+ "impossible while the registry itself implements ApplicationListener<ContextClosedEvent>")
	void subclass_mayImplementApplicationListenerForAnotherEvent() {
		final UserRegistry registry = new UserRegistry();

		assertThat(registry.size()).isZero();
		assertThat(registry).isInstanceOf(ApplicationListener.class);
	}

	/** The reviewer's probe, verbatim. Its existence is the assertion. */
	static class UserRegistry extends ProxySessionRegistry implements ApplicationListener<ApplicationReadyEvent> {

		@Override
		public void onApplicationEvent(final ApplicationReadyEvent event) {
			// a host application's own startup hook — the point is only that it compiles
		}

	}

}
