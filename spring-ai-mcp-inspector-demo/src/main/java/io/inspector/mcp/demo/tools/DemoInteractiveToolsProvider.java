/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.tools;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ListRootsResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Demo MCP tools that exercise <strong>server-initiated</strong> features of the MCP
 * protocol — features that previously left inspector UI tabs as {@code assumeTrue}-skips
 * because no server tool would trigger them.
 *
 * <p>
 * Each tool here drives a different server→client round-trip through
 * {@link McpSyncServerExchange}:
 * <ul>
 * <li>{@code askLlm} → {@link McpSyncServerExchange#createMessage(CreateMessageRequest)}
 * — surfaces the upstream <em>Sampling</em> tab (approve/decline flow)</li>
 * <li>{@code askUser} → {@link McpSyncServerExchange#createElicitation(ElicitRequest)} —
 * surfaces the upstream <em>Elicitation</em> tab (form input flow)</li>
 * <li>{@code listMyRoots} → {@link McpSyncServerExchange#listRoots()} — surfaces the
 * upstream <em>Roots</em> tab (client-advertised roots)</li>
 * </ul>
 *
 * <p>
 * If the connected client does not advertise the required capability (sampling /
 * elicitation / roots), each tool returns a helpful error string rather than throwing —
 * the goal is for the inspector UI to render a clear <em>"client does not support X"</em>
 * banner rather than a stack trace.
 */
@Component
public class DemoInteractiveToolsProvider {

	/**
	 * Server-initiated sampling. Asks the connected client (acting as an LLM
	 * intermediary) to generate a completion for the supplied question.
	 *
	 * <p>
	 * The inspector UI must advertise {@code sampling} in its {@link ClientCapabilities}
	 * and register a {@code sampling/createMessage} handler to satisfy this request. If
	 * sampling is not supported, the tool returns a guidance string.
	 * @param exchange the per-call server exchange (auto-injected by mcp-annotations)
	 * @param question the prompt to forward to the client's LLM
	 * @return text content of the LLM's response, or an error/guidance string
	 */
	@McpTool(name = "askLlm", description = "Ask the connected client's LLM a question via MCP sampling/createMessage")
	public String askLlm(McpSyncServerExchange exchange,
			@McpToolParam(description = "the question to forward to the LLM", required = true) String question) {
		if (exchange == null) {
			return "askLlm: no server exchange available (transport does not support server→client requests)";
		}
		ClientCapabilities caps = exchange.getClientCapabilities();
		if (caps == null || caps.sampling() == null) {
			return "askLlm: connected client does not advertise the 'sampling' capability";
		}
		try {
			CreateMessageRequest request = CreateMessageRequest.builder()
				.messages(List.of(new SamplingMessage(Role.USER, new TextContent(question))))
				.maxTokens(256)
				.systemPrompt("You are a helpful assistant invoked via MCP sampling.")
				.build();
			CreateMessageResult result = exchange.createMessage(request);
			Content content = result.content();
			if (content instanceof TextContent text) {
				return text.text();
			}
			return String.valueOf(content);
		}
		catch (RuntimeException e) {
			return "askLlm: sampling request failed: " + e.getMessage();
		}
	}

	/**
	 * Server-initiated elicitation. Asks the connected client to render a form with a
	 * single string field {@code answer} and return the user's response.
	 *
	 * <p>
	 * Returns the user's answer, or an action/error string if the client declines/cancels
	 * or does not support elicitation.
	 * @param exchange the per-call server exchange (auto-injected by mcp-annotations)
	 * @param question the question text shown to the user in the elicitation form
	 * @return the user's answer, an action label (decline/cancel), or a guidance string
	 */
	@McpTool(name = "askUser",
			description = "Ask the connected client's user a free-form question via MCP elicitation/create")
	public String askUser(McpSyncServerExchange exchange,
			@McpToolParam(description = "the question to render in the elicitation form",
					required = true) String question) {
		if (exchange == null) {
			return "askUser: no server exchange available (transport does not support server→client requests)";
		}
		ClientCapabilities caps = exchange.getClientCapabilities();
		if (caps == null || caps.elicitation() == null) {
			return "askUser: connected client does not advertise the 'elicitation' capability";
		}
		try {
			Map<String, Object> answerProp = new LinkedHashMap<>();
			answerProp.put("type", "string");
			answerProp.put("description", "User's free-form answer to the question");

			Map<String, Object> properties = new LinkedHashMap<>();
			properties.put("answer", answerProp);

			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "object");
			schema.put("properties", properties);
			schema.put("required", List.of("answer"));

			ElicitRequest request = ElicitRequest.builder().message(question).requestedSchema(schema).build();
			ElicitResult result = exchange.createElicitation(request);

			if (result.action() != ElicitResult.Action.ACCEPT) {
				return "askUser: user " + result.action().name().toLowerCase();
			}
			Map<String, Object> content = result.content();
			Object answer = content == null ? null : content.get("answer");
			return answer == null ? "" : String.valueOf(answer);
		}
		catch (RuntimeException e) {
			return "askUser: elicitation request failed: " + e.getMessage();
		}
	}

	/**
	 * Realistic elicitation example: confirm deploy parameters before acting.
	 *
	 * <p>
	 * Demonstrates a structured form with multiple field types so the inspector renders
	 * proper widgets (enum dropdown, boolean toggle, number input with bounds, optional
	 * free-form text). This is the kind of human-in-the-loop checkpoint a real deploy
	 * tool would use before running an irreversible action.
	 * @param exchange the per-call server exchange (auto-injected by mcp-annotations)
	 * @param serviceName the service being deployed (shown to the user)
	 * @return a summary of the choices the user made, or an action/error string
	 */
	@McpTool(name = "deployService",
			description = "Confirm deploy parameters via elicitation form (environment, dryRun, replicas, notes)")
	public String deployService(McpSyncServerExchange exchange,
			@McpToolParam(description = "name of the service to deploy", required = true) String serviceName) {
		if (exchange == null) {
			return "deployService: no server exchange available";
		}
		ClientCapabilities caps = exchange.getClientCapabilities();
		if (caps == null || caps.elicitation() == null) {
			return "deployService: connected client does not advertise the 'elicitation' capability";
		}
		try {
			Map<String, Object> environment = new LinkedHashMap<>();
			environment.put("type", "string");
			environment.put("enum", List.of("prod", "staging", "dev"));
			environment.put("description", "target environment");

			Map<String, Object> dryRun = new LinkedHashMap<>();
			dryRun.put("type", "boolean");
			dryRun.put("default", true);
			dryRun.put("description", "if true, print plan but don't apply");

			Map<String, Object> replicas = new LinkedHashMap<>();
			replicas.put("type", "integer");
			replicas.put("minimum", 1);
			replicas.put("maximum", 10);
			replicas.put("default", 2);
			replicas.put("description", "number of pods");

			Map<String, Object> notes = new LinkedHashMap<>();
			notes.put("type", "string");
			notes.put("description", "optional deploy note for the audit log");

			Map<String, Object> properties = new LinkedHashMap<>();
			properties.put("environment", environment);
			properties.put("dryRun", dryRun);
			properties.put("replicas", replicas);
			properties.put("notes", notes);

			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "object");
			schema.put("properties", properties);
			schema.put("required", List.of("environment", "dryRun", "replicas"));

			ElicitRequest request = ElicitRequest.builder()
				.message("Confirm deploy parameters for service '" + serviceName + "'")
				.requestedSchema(schema)
				.build();
			ElicitResult result = exchange.createElicitation(request);

			if (result.action() != ElicitResult.Action.ACCEPT) {
				return "deployService: user " + result.action().name().toLowerCase() + " — nothing was deployed";
			}
			Map<String, Object> content = result.content();
			String env = String.valueOf(content.get("environment"));
			Object replicasVal = content.get("replicas");
			Object dryRunVal = content.get("dryRun");
			Object notesVal = content.get("notes");
			String notesPart = (notesVal == null || String.valueOf(notesVal).isBlank()) ? ""
					: " (note: " + notesVal + ")";
			return "deployService: would deploy '" + serviceName + "' to " + env + " with " + replicasVal
					+ " replicas, dryRun=" + dryRunVal + notesPart;
		}
		catch (RuntimeException e) {
			return "deployService: elicitation request failed: " + e.getMessage();
		}
	}

	/**
	 * Queries the connected client for its currently advertised roots and returns them as
	 * a comma-separated {@code uri (name)} listing.
	 * @param exchange the per-call server exchange (auto-injected by mcp-annotations)
	 * @return concatenated root descriptors, or a guidance string if unsupported
	 */
	/**
	 * Real use of Roots: search files matching a glob, scoped strictly to the directories
	 * the client has advertised. Demonstrates the security boundary — without an
	 * advertised root the tool refuses to search at all rather than walking the whole
	 * filesystem.
	 *
	 * <p>
	 * Supports {@code file://} roots only; other URI schemes are skipped with a note in
	 * the result. Walks each root recursively, matches against the supplied glob, caps
	 * results at {@code MAX_HITS} to keep the response readable in the inspector UI.
	 * @param exchange the per-call server exchange (auto-injected by mcp-annotations)
	 * @param glob a glob expression like {@code **&#47;*.java} or {@code pom.xml}
	 * @return matching paths (one per line) or a guidance string
	 */
	@McpTool(name = "findFiles", description = "Search files matching a glob, scoped to client-advertised roots")
	public String findFiles(McpSyncServerExchange exchange,
			@McpToolParam(description = "glob pattern, e.g. **/*.java or pom.xml", required = true) String glob) {
		if (exchange == null) {
			return "findFiles: no server exchange available";
		}
		String trimmedGlob = glob == null ? "" : glob.trim();
		if (trimmedGlob.isEmpty()) {
			return "findFiles: glob is empty";
		}
		ClientCapabilities caps = exchange.getClientCapabilities();
		if (caps == null || caps.roots() == null) {
			return "findFiles: connected client does not advertise the 'roots' capability — "
					+ "refusing to scan the host filesystem without an explicit allow-list";
		}
		List<Root> roots;
		try {
			roots = exchange.listRoots().roots();
		}
		catch (RuntimeException e) {
			return "findFiles: roots/list failed: " + e.getMessage();
		}
		if (roots == null || roots.isEmpty()) {
			return "findFiles: client advertises roots capability but advertised zero roots — "
					+ "add at least one root in the Roots tab and retry";
		}

		final int MAX_HITS = 50;
		List<String> hits = new ArrayList<>();
		List<String> skipped = new ArrayList<>();

		for (Root root : roots) {
			String rootUri = root.uri() == null ? "" : root.uri().trim();
			if (rootUri.isEmpty() || rootUri.equals("file://")) {
				continue;
			}
			URI uri;
			try {
				uri = URI.create(rootUri);
			}
			catch (IllegalArgumentException e) {
				skipped.add(rootUri + " (invalid URI)");
				continue;
			}
			if (!"file".equalsIgnoreCase(uri.getScheme())) {
				skipped.add(rootUri + " (non-file scheme)");
				continue;
			}
			Path base;
			try {
				base = Paths.get(uri);
			}
			catch (IllegalArgumentException e) {
				skipped.add(rootUri + " (invalid path: " + e.getMessage() + ")");
				continue;
			}
			if (!Files.isDirectory(base)) {
				skipped.add(rootUri + " (not a directory)");
				continue;
			}
			PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + trimmedGlob);
			try (Stream<Path> stream = Files.walk(base)) {
				stream.filter(Files::isRegularFile)
					.filter(p -> matcher.matches(base.relativize(p)))
					.map(Path::toString)
					.limit(MAX_HITS - hits.size())
					.forEach(hits::add);
			}
			catch (IOException e) {
				skipped.add(root.uri() + " (walk failed: " + e.getMessage() + ")");
			}
			if (hits.size() >= MAX_HITS) {
				break;
			}
		}

		StringBuilder out = new StringBuilder();
		if (hits.isEmpty()) {
			out.append("findFiles: no matches for '")
				.append(trimmedGlob)
				.append("' across ")
				.append(roots.size())
				.append(" advertised root(s)");
		}
		else {
			out.append("findFiles: ")
				.append(hits.size())
				.append(hits.size() == MAX_HITS ? "+ (capped)" : "")
				.append(" match(es) for '")
				.append(trimmedGlob)
				.append("':\n")
				.append(String.join("\n", hits));
		}
		if (!skipped.isEmpty()) {
			out.append("\n\nskipped roots:\n").append(String.join("\n", skipped));
		}
		return out.toString();
	}

	@McpTool(name = "listMyRoots", description = "List the roots advertised by the connected client (roots/list)")
	public String listMyRoots(McpSyncServerExchange exchange) {
		if (exchange == null) {
			return "listMyRoots: no server exchange available";
		}
		ClientCapabilities caps = exchange.getClientCapabilities();
		if (caps == null || caps.roots() == null) {
			return "listMyRoots: connected client does not advertise the 'roots' capability";
		}
		try {
			ListRootsResult result = exchange.listRoots();
			List<Root> roots = result.roots();
			if (roots == null || roots.isEmpty()) {
				return "listMyRoots: client advertised roots capability but returned no roots";
			}
			return roots.stream().map(r -> {
				String name = r.name() == null || r.name().isBlank() ? "" : " (" + r.name() + ")";
				return r.uri() + name;
			}).collect(Collectors.joining(", "));
		}
		catch (RuntimeException e) {
			return "listMyRoots: roots request failed: " + e.getMessage();
		}
	}

}
