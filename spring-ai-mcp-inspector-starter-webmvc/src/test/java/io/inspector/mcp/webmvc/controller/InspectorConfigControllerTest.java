/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.webmvc.controller;

import io.inspector.mcp.core.bootstrap.InspectorBootstrap;
import io.inspector.mcp.core.bootstrap.InspectorBootstrapAssembler;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InspectorConfigController}. Asserts the assembled bootstrap is
 * returned with status 200, JSON content type and the no-cache headers.
 */
@Epic("WebMvc Inspector")
@Feature("InspectorConfigController")
class InspectorConfigControllerTest {

	private InspectorBootstrapAssembler assembler;

	private InspectorConfigController controller;

	@BeforeEach
	void setUp() {
		assembler = mock(InspectorBootstrapAssembler.class);
		controller = new InspectorConfigController(assembler);
	}

	@Nested
	@DisplayName("config()")
	class Config {

		@Test
		@Story("Bootstrap JSON endpoint")
		@Severity(SeverityLevel.CRITICAL)
		@Description("config() returns the assembled bootstrap with 200, JSON content type and no-cache headers")
		void config_returnsBootstrapWithNoCacheHeaders() {
			// given
			InspectorBootstrap bootstrap = new InspectorBootstrap();
			bootstrap.setAuthToken("tok");
			bootstrap.setProxyAddress("/mcp-inspector-api");
			when(assembler.assemble()).thenReturn(bootstrap);

			// when
			ResponseEntity<InspectorBootstrap> response = controller.config();

			// then
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isSameAs(bootstrap);
			assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-store");
			assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
		}

	}

}
