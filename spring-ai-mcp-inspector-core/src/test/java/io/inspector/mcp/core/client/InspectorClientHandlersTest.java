/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.client;

import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InspectorClientHandlers}. */
@Epic("MCP Inspector Core")
@Feature("Loopback client handlers")
class InspectorClientHandlersTest {

	@Nested
	@DisplayName("none()")
	class None {

		@Test
		@Story("Empty handlers")
		@Severity(SeverityLevel.NORMAL)
		@Description("none() returns a tuple with both sampling and elicitation callbacks unset")
		void none_returnsTupleWithNoCallbacks() {
			// when
			final InspectorClientHandlers handlers = InspectorClientHandlers.none();

			// then
			assertThat(handlers.sampling()).isNull();
			assertThat(handlers.elicitation()).isNull();
		}

	}

	@Nested
	@DisplayName("sampling()")
	class Sampling {

		@Test
		@Story("Dispatch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the sampling handler is invoked with the request and returns the produced result")
		@SuppressWarnings("unchecked")
		void sampling_whenInvoked_dispatchesRequestAndShapesResult() {
			// given
			final Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> sampling = mock(
					Function.class);
			final McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
				.messages(java.util.List.of())
				.maxTokens(64)
				.build();
			final McpSchema.CreateMessageResult result = McpSchema.CreateMessageResult.builder()
				.role(McpSchema.Role.ASSISTANT)
				.content(new McpSchema.TextContent("hi"))
				.model("test-model")
				.build();
			when(sampling.apply(request)).thenReturn(result);
			final InspectorClientHandlers handlers = new InspectorClientHandlers(sampling, null);

			// when
			final McpSchema.CreateMessageResult produced = handlers.sampling().apply(request);

			// then
			assertThat(produced).isSameAs(result);
			assertThat(produced.model()).isEqualTo("test-model");
			verify(sampling).apply(request);
		}

	}

	@Nested
	@DisplayName("elicitation()")
	class Elicitation {

		@Test
		@Story("Dispatch")
		@Severity(SeverityLevel.CRITICAL)
		@Description("the elicitation handler is invoked with the request and returns the produced result")
		@SuppressWarnings("unchecked")
		void elicitation_whenInvoked_dispatchesRequestAndShapesResult() {
			// given
			final Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitation = mock(Function.class);
			final McpSchema.ElicitRequest request = McpSchema.ElicitRequest.builder().message("name?").build();
			final McpSchema.ElicitResult result = McpSchema.ElicitResult.builder()
				.message(McpSchema.ElicitResult.Action.ACCEPT)
				.content(java.util.Map.of("name", "value"))
				.build();
			when(elicitation.apply(any())).thenReturn(result);
			final InspectorClientHandlers handlers = new InspectorClientHandlers(null, elicitation);

			// when
			final McpSchema.ElicitResult produced = handlers.elicitation().apply(request);

			// then
			assertThat(produced).isSameAs(result);
			assertThat(produced.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
			verify(elicitation).apply(request);
		}

	}

}
