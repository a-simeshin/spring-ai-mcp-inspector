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

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link ConnectHint} wire shape (spec section 7): kinds, refs and the
 * exact JSON object the UI parses.
 */
@Epic("MCP Inspector Core")
@Feature("Connect hint wire shape")
@DisplayName("ConnectHint")
class ConnectHintTests {

	@Test
	@Severity(SeverityLevel.CRITICAL)
	@Description("toWire() emits exactly {kind, probePath, refs{upstream, local}} per spec section 7")
	void toWire_exactShape() {
		final Map<String, Object> wire = ConnectHint.sseOnly("/sse").toWire();
		assertThat(wire).containsOnlyKeys("kind", "probePath", "refs");
		assertThat(wire.get("kind")).isEqualTo("sse_only");
		assertThat(wire.get("probePath")).isEqualTo("/sse");
		@SuppressWarnings("unchecked")
		final Map<String, String> refs = (Map<String, String>) wire.get("refs");
		assertThat(refs).containsEntry("upstream", "spring-projects/spring-ai#7207").containsEntry("local", "#250");
	}

	@Test
	@Severity(SeverityLevel.NORMAL)
	@Description("maybe_sse keeps the same refs and its own kind wire value")
	void maybeSse_wire() {
		final Map<String, Object> wire = ConnectHint.maybeSse("/sse").toWire();
		assertThat(wire.get("kind")).isEqualTo("maybe_sse");
		@SuppressWarnings("unchecked")
		final Map<String, String> refs2 = (Map<String, String>) wire.get("refs");
		assertThat(refs2).containsKey("upstream");
	}

	@Test
	@Severity(SeverityLevel.MINOR)
	@Description("Null kind or probePath is rejected")
	void nullArgumentsRejected() {
		assertThatThrownBy(() -> new ConnectHint(null, "/sse", null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ConnectHint(ConnectHint.Kind.SSE_ONLY, null, null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@Severity(SeverityLevel.MINOR)
	@Description("The refs map is immutable")
	void refsImmutable() {
		assertThatThrownBy(() -> ConnectHint.sseOnly("/sse").refs().put("x", "y"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
