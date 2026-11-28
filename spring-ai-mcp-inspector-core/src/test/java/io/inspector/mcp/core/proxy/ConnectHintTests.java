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

import java.util.Map;

import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ConnectHint} wire contract: the shared
 * {@code MCP_CONNECT_FAILED} {@code error} payload shape both stacks must emit
 * identically (spec section 7, AC 8).
 */
@DisplayName("ConnectHint error payload")
class ConnectHintTests {

	private static ProxyConnectFailure notFound() {
		return ProxyConnectFailure.classify(new McpTransportException("Server Not Found. Status code: 404"));
	}

	@Test
	@DisplayName("payload without hint has exactly the four base keys")
	void basePayloadWithoutHint() {
		final Map<String, Object> error = ConnectHint.errorPayload(notFound(), null, "MCP_CONNECT_FAILED");
		assertThat(error).containsOnlyKeys("code", "reason", "message", "retryable");
		assertThat(error.get("code")).isEqualTo("MCP_CONNECT_FAILED");
		assertThat(error.get("reason")).isEqualTo("not_found");
		assertThat(error.get("message")).isEqualTo("server responded with 404: check the URL");
		assertThat(error.get("retryable")).isEqualTo(Boolean.TRUE);
	}

	@Test
	@DisplayName("sse_only hint renders kind, probePath and both refs")
	void sseOnlyHintPayload() {
		final ConnectHint hint = new ConnectHint(ConnectHint.KIND_SSE_ONLY, "/sse", ConnectHint.DEFAULT_REFS);
		final Map<String, Object> error = ConnectHint.errorPayload(notFound(), hint, "MCP_CONNECT_FAILED");
		assertThat(error).containsKeys("code", "reason", "message", "retryable", "hint");
		@SuppressWarnings("unchecked")
		final Map<String, Object> wireHint = (Map<String, Object>) error.get("hint");
		assertThat(wireHint.get("kind")).isEqualTo("sse_only");
		assertThat(wireHint.get("probePath")).isEqualTo("/sse");
		@SuppressWarnings("unchecked")
		final Map<String, Object> refs = (Map<String, Object>) wireHint.get("refs");
		assertThat(refs.get("upstream")).isEqualTo("spring-projects/spring-ai#7207");
		assertThat(refs.get("local")).isEqualTo("#250");
	}

	@Test
	@DisplayName("maybe_sse hint carries the checked sibling path")
	void maybeSseHintPayload() {
		final ConnectHint hint = new ConnectHint(ConnectHint.KIND_MAYBE_SSE, "/sse", ConnectHint.DEFAULT_REFS);
		final Map<String, Object> error = ConnectHint.errorPayload(notFound(), hint, "MCP_CONNECT_FAILED");
		@SuppressWarnings("unchecked")
		final Map<String, Object> wireHint = (Map<String, Object>) error.get("hint");
		assertThat(wireHint.get("kind")).isEqualTo("maybe_sse");
		assertThat(wireHint.get("probePath")).isEqualTo("/sse");
	}

	@Test
	@DisplayName("error code is parameterised (both stacks pass their own constant)")
	void errorCodeParameterised() {
		final Map<String, Object> error = ConnectHint.errorPayload(notFound(), null, "OTHER_CODE");
		assertThat(error.get("code")).isEqualTo("OTHER_CODE");
	}

}
