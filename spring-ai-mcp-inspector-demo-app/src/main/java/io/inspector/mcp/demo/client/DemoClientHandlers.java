/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.client;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.stereotype.Component;

/**
 * Client-side {@code @Mcp*} handlers for the demo app's own outbound MCP client
 * ({@code demo-loopback}, issue #120).
 *
 * <p>
 * The demo tool {@code askLlm} sends a {@code sampling/createMessage} request to the
 * connected client. When the demo app calls that tool through its own outbound client,
 * the request loops back into this class, so the full round trip (server tool call,
 * server-initiated sampling request, client answer) lands in the timeline as client-side
 * frames correlated with these handler beans by the inspector's {@code consumedBy}
 * enrichment.
 *
 * <p>
 * The {@code clients} attribute of every annotation names the demo client connection from
 * {@code application.yml}; the Spring AI client annotation scanner registers the methods
 * on that client only, and the sampling registration is also what makes the client
 * advertise the {@code sampling} capability to the server.
 */
@Component
public class DemoClientHandlers {

	private static final Logger LOG = LoggerFactory.getLogger(DemoClientHandlers.class);

	/** Name of the demo app's own outbound client connection (application.yml). */
	public static final String DEMO_CLIENT_NAME = "demo-loopback";

	/**
	 * Answers a server-initiated sampling request with a canned echo reply.
	 * @param request the sampling request created by the {@code askLlm} demo tool
	 * @return an assistant message echoing the question text back
	 */
	@McpSampling(clients = DEMO_CLIENT_NAME)
	public CreateMessageResult onSampling(CreateMessageRequest request) {
		String question = lastUserText(request);
		String answer = "demo-echo: " + question;
		LOG.info("demo-loopback answered sampling/createMessage with: {}", answer);
		return CreateMessageResult.builder()
			.role(Role.ASSISTANT)
			.content(new TextContent(answer))
			.model("demo-loopback-echo")
			.build();
	}

	/**
	 * Consumes server-initiated logging notifications for the demo client.
	 * @param notification the logging message sent by the demo server
	 */
	@McpLogging(clients = DEMO_CLIENT_NAME)
	public void onLogging(LoggingMessageNotification notification) {
		LOG.info("demo-loopback logging [{}]: {}", notification.level(), notification.data());
	}

	/**
	 * Consumes server-initiated progress notifications for the demo client.
	 * @param notification the progress notification sent by the demo server
	 */
	@McpProgress(clients = DEMO_CLIENT_NAME)
	public void onProgress(ProgressNotification notification) {
		LOG.info("demo-loopback progress: {} / {}", notification.progress(), notification.total());
	}

	private static String lastUserText(CreateMessageRequest request) {
		List<SamplingMessage> messages = request.messages();
		if (messages == null || messages.isEmpty()) {
			return "";
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i).content() instanceof TextContent text) {
				return text.text();
			}
		}
		return "";
	}

}
