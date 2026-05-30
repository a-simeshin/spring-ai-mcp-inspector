/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.proxy;

import java.util.Map;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProxyHealthController}. */
@Epic("WebMvc Inspector")
@Feature("ProxyHealthController")
class ProxyHealthControllerTest {

	@Nested
	@DisplayName("health()")
	class Health {

		@Test
		@Story("Liveness probe")
		@Severity(SeverityLevel.NORMAL)
		@Description("health() returns the status:ok body")
		void health_returnsStatusOk() {
			// given
			ProxyHealthController controller = new ProxyHealthController();

			// when
			Map<String, String> body = controller.health();

			// then
			assertThat(body).containsEntry("status", "ok");
		}

	}

}
