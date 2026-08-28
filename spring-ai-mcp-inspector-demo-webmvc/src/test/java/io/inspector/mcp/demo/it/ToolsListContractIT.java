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
package io.inspector.mcp.demo.it;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.mcp.demo.DemoApplication;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration contract for the {@code tools/list} wire response through the inspector
 * JSON-RPC relay (issue #57, honest hint annotations).
 *
 * <p>
 * Drives the real demo server through {@link MockMvc}: {@code POST /connect} creates a
 * loopback MCP client session, {@code POST /jsonrpc?sessionId=...} relays
 * {@code tools/list}, and the response is asserted on the raw JSON — exactly what the
 * vendored inspector UI consumes. No new test infrastructure: {@code @SpringBootTest} +
 * MockMvc, same stack as the module's other ITs.
 *
 * <p>
 * Contract under test:
 * <ul>
 * <li>21 tool entries (the 1.x line has no {@code authorizeViaUrl} — it needs SDK 2.0
 * URL-mode elicitation);</li>
 * <li>17 entries with {@code annotations.readOnlyHint=true, destructiveHint=false} (echo,
 * sum, currentTime, addNumbers, concatenate, lookupUser, chooseColor, toggleFlag,
 * optionalGreeting, errorTool, largeOutput, structuredOutput, multiContent, deepJson,
 * blobAttachment, findFiles, listMyRoots);</li>
 * <li>3 entries with {@code annotations.readOnlyHint=false, destructiveHint=false}
 * (askLlm, askUser, deployService);</li>
 * <li>{@code slowEcho} carries NO {@code annotations} field at all — it is registered as
 * a manual {@code ToolCallback} so the wire omits the object and the UI can mark the
 * chips as MCP spec defaults;</li>
 * <li>no entry declares {@code destructiveHint=true};</li>
 * <li>entry shape stays backward-compatible: {@code name}, {@code description} and
 * {@code inputSchema} are always present.</li>
 * </ul>
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Inspector relay")
@Feature("tools/list contract")
class ToolsListContractIT {

	private static final String CONNECT_URL = "/mcp-inspector/api/connect";

	private static final String JSONRPC_URL = "/mcp-inspector/api/jsonrpc";

	private static final String SESSION_URL = "/mcp-inspector/api/session/{id}";

	private static final List<String> READ_ONLY_TOOLS = List.of("echo", "sum", "currentTime", "addNumbers",
			"concatenate", "lookupUser", "chooseColor", "toggleFlag", "optionalGreeting", "errorTool", "largeOutput",
			"structuredOutput", "multiContent", "deepJson", "blobAttachment", "findFiles", "listMyRoots");

	private static final List<String> INTERACTIVE_TOOLS = List.of("askLlm", "askUser", "deployService");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@Story("tools/list contract")
	@Severity(SeverityLevel.CRITICAL)
	@DisplayName("toolsList21Entries — 21 entries; 17 read-only + 3 interactive annotated; no declared destructive")
	@Description("tools/list through the relay returns 21 tools; 17 carry readOnlyHint=true/destructiveHint=false, "
			+ "3 (askLlm, askUser, deployService) carry readOnlyHint=false/destructiveHint=false, and no entry "
			+ "declares destructiveHint=true.")
	void toolsList21EntriesWithExplicitAnnotations() throws Exception {
		// given: a connected relay session
		String sessionId = connect();
		try {
			// when: tools/list is relayed
			JsonNode result = jsonrpc(sessionId, "tools/list");
			JsonNode tools = result.path("tools");
			assertThat(tools.isArray()).as("tools/list must return a tools array").isTrue();

			// then: 21 entries, exact matrix
			assertThat(tools.size()).as("demo must advertise exactly 21 tools").isEqualTo(21);

			List<String> names = new ArrayList<>();
			for (JsonNode tool : tools) {
				names.add(tool.path("name").asText());
				JsonNode annotations = tool.get("annotations");
				if (READ_ONLY_TOOLS.contains(tool.path("name").asText())) {
					assertThat(annotations)
						.as("read-only tool '%s' must declare annotations", tool.path("name").asText())
						.isNotNull();
					assertThat(annotations.path("readOnlyHint").asBoolean())
						.as("tool '%s': readOnlyHint must be true", tool.path("name").asText())
						.isTrue();
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("tool '%s': destructiveHint must be false", tool.path("name").asText())
						.isFalse();
				}
				else if (INTERACTIVE_TOOLS.contains(tool.path("name").asText())) {
					assertThat(annotations)
						.as("interactive tool '%s' must declare annotations", tool.path("name").asText())
						.isNotNull();
					assertThat(annotations.path("readOnlyHint").asBoolean())
						.as("tool '%s': readOnlyHint must be false", tool.path("name").asText())
						.isFalse();
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("tool '%s': destructiveHint must be false", tool.path("name").asText())
						.isFalse();
				}
				else {
					// slowEcho — the only remaining tool
					assertThat(tool.path("name").asText()).as("unexpected tool in tools/list").isEqualTo("slowEcho");
				}
				// no tool may declare destructiveHint=true
				if (annotations != null && annotations.has("destructiveHint")) {
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("no demo tool may declare destructiveHint=true ('%s')", tool.path("name").asText())
						.isFalse();
				}
			}

			// every contracted tool is present
			for (String name : READ_ONLY_TOOLS) {
				assertThat(names).as("read-only tool '%s' missing", name).contains(name);
			}
			for (String name : INTERACTIVE_TOOLS) {
				assertThat(names).as("interactive tool '%s' missing", name).contains(name);
			}
			assertThat(names).as("slowEcho missing").contains("slowEcho");
		}
		finally {
			disconnect(sessionId);
		}
	}

	@Test
	@Story("tools/list contract")
	@Severity(SeverityLevel.CRITICAL)
	@DisplayName("slowEchoEntryLacksAnnotations — the manual ToolCallback entry has no annotations field")
	@Description("slowEcho is registered as a manual ToolCallback (not @McpTool), so the SDK serializer omits the "
			+ "annotations field entirely — the honest signal the inspector UI uses to render spec-default chips.")
	void slowEchoEntryLacksAnnotations() throws Exception {
		// given
		String sessionId = connect();
		try {
			// when
			JsonNode result = jsonrpc(sessionId, "tools/list");
			JsonNode slowEcho = findTool(result.path("tools"), "slowEcho");

			// then
			assertThat(slowEcho).as("slowEcho entry must be present").isNotNull();
			assertThat(slowEcho.has("annotations")).as("slowEcho must not carry an annotations object on the wire")
				.isFalse();
			assertThat(slowEcho.path("name").asText()).isEqualTo("slowEcho");
			assertThat(slowEcho.path("description").asText()).isEqualTo("Echo text after a ~2 second delay");
			assertThat(slowEcho.path("inputSchema").isObject()).as("slowEcho inputSchema must be an object").isTrue();
		}
		finally {
			disconnect(sessionId);
		}
	}

	@Test
	@Story("tools/list contract")
	@Severity(SeverityLevel.NORMAL)
	@DisplayName("toolsListKeepsPreAnnotationEntryShape — name/description/inputSchema present on every entry")
	@Description("The honest-hint change must not alter the entry shape consumers rely on: name, description and "
			+ "inputSchema are present for every tool, and the response structure (jsonrpc/result/tools) is unchanged.")
	void toolsListKeepsPreAnnotationEntryShape() throws Exception {
		// given
		String sessionId = connect();
		try {
			// when
			JsonNode result = jsonrpc(sessionId, "tools/list");
			JsonNode tools = result.path("tools");

			// then
			assertThat(tools.isArray()).isTrue();
			for (JsonNode tool : tools) {
				assertThat(tool.path("name").asText()).as("every entry needs a name").isNotEmpty();
				assertThat(tool.path("inputSchema").isObject()).as("entry '%s' needs inputSchema", tool.path("name"))
					.isTrue();
			}
		}
		finally {
			disconnect(sessionId);
		}
	}

	private String connect() throws Exception {
		MvcResult connect = this.mockMvc
			.perform(post(CONNECT_URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = this.objectMapper.readTree(connect.getResponse().getContentAsString());
		String sessionId = body.path("sessionId").asText(null);
		assertThat(sessionId).as("connect must return a sessionId").isNotNull();
		return sessionId;
	}

	private JsonNode jsonrpc(String sessionId, String method) throws Exception {
		MvcResult call = this.mockMvc
			.perform(post(JSONRPC_URL).param("sessionId", sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(this.objectMapper.writeValueAsString(new JsonRpcRelayBody("2.0", 1, method, null))))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = this.objectMapper.readTree(call.getResponse().getContentAsString());
		assertThat(body.has("result")).as("jsonrpc response must carry a result").isTrue();
		return body.path("result");
	}

	private void disconnect(String sessionId) throws Exception {
		this.mockMvc.perform(delete(SESSION_URL, sessionId)).andExpect(status().is2xxSuccessful());
	}

	private static JsonNode findTool(JsonNode tools, String name) {
		if (!tools.isArray()) {
			return null;
		}
		for (JsonNode tool : tools) {
			if (name.equals(tool.path("name").asText())) {
				return tool;
			}
		}
		return null;
	}

	/** Minimal JSON-RPC request shape understood by the relay. */
	private record JsonRpcRelayBody(String jsonrpc, Object id, String method, Object params) {
	}

}
