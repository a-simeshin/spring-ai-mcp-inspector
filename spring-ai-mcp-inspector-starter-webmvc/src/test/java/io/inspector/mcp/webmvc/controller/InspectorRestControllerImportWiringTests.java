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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import io.inspector.mcp.core.auth.InspectorAuthTokenProvider;
import io.inspector.mcp.core.client.LoopbackMcpClientFactory;
import io.inspector.mcp.core.config.McpInspectorProperties;
import io.inspector.mcp.core.oauth.InspectorOAuthClient;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.inspector.mcp.core.transport.TransportDetector;
import io.inspector.mcp.webmvc.InspectorServerPortHolder;
import io.inspector.mcp.webmvc.sse.InspectorSseEmitterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the {@code @Import} wiring of {@link InspectorRestController}.
 *
 * <p>
 * The controller is registered via {@code @Import} on
 * {@code McpInspectorWebMvcAutoConfiguration} and carries two constructors. Spring only
 * resolves a non-annotated constructor automatically when it is the single one: with
 * several candidates and no default constructor, instantiation falls back to the no-arg
 * path and application startup fails with {@code NoSuchMethodException}. The full
 * constructor must stay {@code @Autowired}; this test reproduces the registration path so
 * a regression fails here instead of on a live demo boot.
 */
class InspectorRestControllerImportWiringTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withBean(McpInspectorProperties.class, McpInspectorProperties::new)
		.withBean(TransportDetector.class, () -> mock(TransportDetector.class))
		.withBean(LoopbackMcpClientFactory.class, () -> mock(LoopbackMcpClientFactory.class))
		.withBean(InspectorAuthTokenProvider.class, () -> mock(InspectorAuthTokenProvider.class))
		.withBean(InspectorSseEmitterRegistry.class, InspectorSseEmitterRegistry::new)
		.withBean(InspectorServerPortHolder.class, InspectorServerPortHolder::new)
		.withBean(JsonMapper.class, JsonMapper::new)
		.withBean(InspectorOAuthClient.class, InspectorOAuthClient::new)
		.withBean(ProxySessionRegistry.class, ProxySessionRegistry::new)
		.withBean(ImportingConfig.class);

	@Test
	@DisplayName("import registration resolves the @Autowired constructor")
	void importRegistrationResolvesAutowiredConstructor() {
		this.runner.run((context) -> assertThat(context).hasSingleBean(InspectorRestController.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(InspectorRestController.class)
	static class ImportingConfig {

	}

}
