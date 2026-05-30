/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.dto;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link JsonRpcRelay} Jackson serialization. */
class JsonRpcRelayDtoTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void serializesAndDeserializesViaJackson() throws Exception {
		JsonRpcRelay original = new JsonRpcRelay("2.0", 42, "tools/list", Map.of("foo", "bar"));

		String json = mapper.writeValueAsString(original);
		assertThat(json).contains("\"jsonrpc\":\"2.0\"");
		assertThat(json).contains("\"method\":\"tools/list\"");
		assertThat(json).contains("\"id\":42");

		JsonRpcRelay roundTripped = mapper.readValue(json, JsonRpcRelay.class);
		assertThat(roundTripped.jsonrpc()).isEqualTo("2.0");
		assertThat(roundTripped.method()).isEqualTo("tools/list");
		assertThat(roundTripped.id()).isEqualTo(42);
		assertThat(roundTripped.params()).isInstanceOf(Map.class);
	}

	@Test
	void handlesNullFields() throws Exception {
		JsonRpcRelay original = new JsonRpcRelay("2.0", null, "ping", null);

		String json = mapper.writeValueAsString(original);
		JsonRpcRelay roundTripped = mapper.readValue(json, JsonRpcRelay.class);

		assertThat(roundTripped.method()).isEqualTo("ping");
		assertThat(roundTripped.id()).isNull();
		assertThat(roundTripped.params()).isNull();
	}

}
