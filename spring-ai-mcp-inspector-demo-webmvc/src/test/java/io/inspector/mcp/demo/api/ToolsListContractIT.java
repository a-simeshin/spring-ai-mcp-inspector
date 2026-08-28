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
package io.inspector.mcp.demo.api;

import java.util.ArrayList;
import java.util.List;

import io.inspector.mcp.demo.DemoApplication;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration contract for the {@code tools/list} wire response through the inspector
 * streamable-HTTP proxy (issue #57, honest hint annotations).
 *
 * <p>
 * Drives the real demo server through {@link MockMvc}:
 * {@code POST /mcp-inspector-api/mcp} opens a proxy session to the loopback MCP endpoint
 * ({@code /mcp}), {@code tools/list} is relayed as a JSON-RPC request, and the response
 * is asserted on the raw JSON — exactly what the vendored inspector UI consumes. No new
 * test infrastructure: {@code @SpringBootTest} + MockMvc, same stack as the module's
 * other ITs.
 *
 * <p>
 * Contract under test:
 * <ul>
 * <li>22 tool entries (the 2.x line includes {@code authorizeViaUrl}, which needs SDK 2.0
 * URL-mode elicitation);</li>
 * <li>17 entries with {@code annotations.readOnlyHint=true, destructiveHint=false} (echo,
 * sum, currentTime, addNumbers, concatenate, lookupUser, chooseColor, toggleFlag,
 * optionalGreeting, errorTool, largeOutput, structuredOutput, multiContent, deepJson,
 * blobAttachment, findFiles, listMyRoots);</li>
 * <li>4 entries with {@code annotations.readOnlyHint=false, destructiveHint=false}
 * (askLlm, askUser, deployService, authorizeViaUrl);</li>
 * <li>{@code slowEcho} carries NO {@code annotations} field at all — it is registered as
 * a manual {@code ToolCallback} so the wire omits the object and the UI can mark the
 * chips as MCP spec defaults;</li>
 * <li>no entry declares {@code destructiveHint=true};</li>
 * <li>entry shape stays backward-compatible: {@code name}, {@code description} and
 * {@code inputSchema} are always present.</li>
 * </ul>
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.inspector.auth-enabled=false" })
@Epic("MCP Inspector relay")
@Feature("tools/list contract")
class ToolsListContractIT {

	private static final String MCP_URL = "/mcp-inspector-api/mcp";

	private static final String SESSION_HEADER = "mcp-session-id";

	private static final List<String> READ_ONLY_TOOLS = List.of("echo", "sum", "currentTime", "addNumbers",
			"concatenate", "lookupUser", "chooseColor", "toggleFlag", "optionalGreeting", "errorTool", "largeOutput",
			"structuredOutput", "multiContent", "deepJson", "blobAttachment", "findFiles", "listMyRoots");

	private static final List<String> INTERACTIVE_TOOLS = List.of("askLlm", "askUser", "deployService",
			"authorizeViaUrl");

	private static final JsonMapper MAPPER = new JsonMapper();

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	@Story("tools/list contract")
	@Severity(SeverityLevel.CRITICAL)
	@DisplayName("toolsList22Entries — 22 entries; 17 read-only + 4 interactive annotated; no declared destructive")
	@Description("tools/list through the relay returns 22 tools; 17 carry readOnlyHint=true/destructiveHint=false, "
			+ "4 (askLlm, askUser, deployService, authorizeViaUrl) carry readOnlyHint=false/destructiveHint=false, "
			+ "and no entry declares destructiveHint=true.")
	void toolsList22EntriesWithExplicitAnnotations() throws Exception {
		// given: a connected proxy session
		final String sessionId = connect();
		try {
			// when: tools/list is relayed
			final JsonNode result = toolsList(sessionId);
			final JsonNode tools = result.path("tools");
			assertThat(tools.isArray()).as("tools/list must return a tools array").isTrue();

			// then: 22 entries, exact matrix
			assertThat(tools.size()).as("demo must advertise exactly 22 tools").isEqualTo(22);

			final List<String> names = new ArrayList<>();
			for (final JsonNode tool : tools) {
				names.add(tool.path("name").asString());
				final JsonNode annotations = tool.get("annotations");
				if (READ_ONLY_TOOLS.contains(tool.path("name").asString())) {
					assertThat(annotations)
						.as("read-only tool '%s' must declare annotations", tool.path("name").asString())
						.isNotNull();
					assertThat(annotations.path("readOnlyHint").asBoolean())
						.as("tool '%s': readOnlyHint must be true", tool.path("name").asString())
						.isTrue();
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("tool '%s': destructiveHint must be false", tool.path("name").asString())
						.isFalse();
				}
				else if (INTERACTIVE_TOOLS.contains(tool.path("name").asString())) {
					assertThat(annotations)
						.as("interactive tool '%s' must declare annotations", tool.path("name").asString())
						.isNotNull();
					assertThat(annotations.path("readOnlyHint").asBoolean())
						.as("tool '%s': readOnlyHint must be false", tool.path("name").asString())
						.isFalse();
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("tool '%s': destructiveHint must be false", tool.path("name").asString())
						.isFalse();
				}
				else {
					// slowEcho — the only remaining tool
					assertThat(tool.path("name").asString()).as("unexpected tool in tools/list").isEqualTo("slowEcho");
				}
				// no tool may declare destructiveHint=true
				if (annotations != null && annotations.has("destructiveHint")) {
					assertThat(annotations.path("destructiveHint").asBoolean())
						.as("no demo tool may declare destructiveHint=true ('%s')", tool.path("name").asString())
						.isFalse();
				}
			}

			// every contracted tool is present
			for (final String name : READ_ONLY_TOOLS) {
				assertThat(names).as("read-only tool '%s' missing", name).contains(name);
			}
			for (final String name : INTERACTIVE_TOOLS) {
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
		final String sessionId = connect();
		try {
			// when
			final JsonNode slowEcho = findTool(toolsList(sessionId).path("tools"), "slowEcho");

			// then
			assertThat(slowEcho).as("slowEcho entry must be present").isNotNull();
			assertThat(slowEcho.has("annotations")).as("slowEcho must not carry an annotations object on the wire")
				.isFalse();
			assertThat(slowEcho.path("name").asString()).isEqualTo("slowEcho");
			assertThat(slowEcho.path("description").asString()).isEqualTo("Echo text after a ~2 second delay");
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
		final String sessionId = connect();
		try {
			// when
			final JsonNode result = toolsList(sessionId);
			final JsonNode tools = result.path("tools");

			// then
			assertThat(tools.isArray()).isTrue();
			for (final JsonNode tool : tools) {
				assertThat(tool.path("name").asString()).as("every entry needs a name").isNotEmpty();
				assertThat(tool.path("inputSchema").isObject()).as("entry '%s' needs inputSchema", tool.path("name"))
					.isTrue();
			}
		}
		finally {
			disconnect(sessionId);
		}
	}

	/**
	 * Opens a proxy session by relaying {@code initialize} and returns the
	 * {@code mcp-session-id} response header.
	 * @return the new proxy session id
	 * @throws Exception on MockMvc failure
	 */
	private String connect() throws Exception {
		final ObjectNode init = MAPPER.createObjectNode();
		init.put("jsonrpc", "2.0");
		init.put("method", "initialize");
		init.put("id", 1);
		final ObjectNode params = init.putObject("params");
		params.put("protocolVersion", "2025-11-25");
		params.putObject("capabilities");
		final ObjectNode info = params.putObject("clientInfo");
		info.put("name", "tools-list-contract-it");
		info.put("version", "0.1.0");

		final MvcResult connect = this.mockMvc
			.perform(post(MCP_URL).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(init)))
			.andExpect(status().isOk())
			.andReturn();
		final String sessionId = connect.getResponse().getHeader(SESSION_HEADER);
		assertThat(sessionId).as("initialize must return an mcp-session-id header").isNotBlank();
		return sessionId;
	}

	/**
	 * Relays {@code tools/list} on an open session and returns the JSON-RPC result node.
	 * @param sessionId the proxy session id
	 * @return the {@code result} object of the tools/list response
	 * @throws Exception on MockMvc failure
	 */
	private JsonNode toolsList(final String sessionId) throws Exception {
		// notifications/initialized — required by the MCP handshake before requests
		final ObjectNode initialized = MAPPER.createObjectNode();
		initialized.put("jsonrpc", "2.0");
		initialized.put("method", "notifications/initialized");
		this.mockMvc
			.perform(post(MCP_URL).header(SESSION_HEADER, sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(initialized)))
			.andExpect(status().isAccepted());

		final ObjectNode call = MAPPER.createObjectNode();
		call.put("jsonrpc", "2.0");
		call.put("method", "tools/list");
		call.put("id", 2);

		final MvcResult result = this.mockMvc
			.perform(post(MCP_URL).header(SESSION_HEADER, sessionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(MAPPER.writeValueAsString(call)))
			.andExpect(status().isOk())
			.andReturn();
		final JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("result").isObject()).as("jsonrpc response must carry a result").isTrue();
		return body.path("result");
	}

	/**
	 * Tears down the proxy session.
	 * @param sessionId the proxy session id
	 * @throws Exception on MockMvc failure
	 */
	private void disconnect(final String sessionId) throws Exception {
		this.mockMvc.perform(delete(MCP_URL).header(SESSION_HEADER, sessionId)).andExpect(status().is2xxSuccessful());
	}

	/**
	 * Finds a tool entry by name in a tools array.
	 * @param tools the tools array node
	 * @param name the tool name to find
	 * @return the tool entry, or {@code null} when absent
	 */
	private static JsonNode findTool(final JsonNode tools, final String name) {
		if (!tools.isArray()) {
			return null;
		}
		for (final JsonNode tool : tools) {
			if (name.equals(tool.path("name").asString())) {
				return tool;
			}
		}
		return null;
	}

}
