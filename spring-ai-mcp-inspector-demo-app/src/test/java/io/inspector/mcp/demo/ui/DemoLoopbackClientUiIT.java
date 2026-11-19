/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.ui;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Condition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.DemoApplication;
import io.inspector.mcp.demo.client.DemoClientHandlers;
import io.inspector.mcp.demo.e2e.E2ePreconditions;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Browser E2E for the demo app's own outbound MCP client (issue #120), run once per web
 * stack (this class ships in the demo-app test-jar and both stack modules pick it up
 * through Failsafe's {@code dependenciesToScan}).
 *
 * <p>
 * The demo app connects one named streamable-http client ({@code demo-loopback}) to its
 * own MCP server endpoint. The initialization is deferred to
 * {@code DemoLoopbackClientInitializer} (an {@code ApplicationReadyEvent} listener),
 * because the client beans are created before the server port exists. Integration tests
 * therefore pick a free port <em>before</em> boot and pass an explicit
 * {@code --server.port}: {@code RANDOM_PORT} resolves to 0, and the loopback URL in
 * {@code application.yml} embeds {@code ${server.port}}.
 *
 * <p>
 * Asserted here:
 * <ul>
 * <li>the Clients tab shows the {@code demo-loopback} connection with the live state
 * badge;</li>
 * <li>the timeline contains the client's own {@code initialize} and {@code tools/call}
 * frames, badged as side=client and filterable;</li>
 * <li>the {@code askLlm} round trip through the demo client shows the server-initiated
 * {@code sampling/createMessage} request correlated with the
 * {@code DemoClientHandlers#onSampling} bean ({@code consumedBy}) and the client's
 * outbound {@code JSONRPCResponse} sharing the same correlation id.</li>
 * </ul>
 */
@Epic("MCP Inspector UI")
@Feature("Outbound client connections (issue #120)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoLoopbackClientUiIT {

	/** Wait budget for every asynchronous wait in this class. */
	private static final Duration BUDGET = Duration.ofSeconds(30);

	/** Delay between two REST polls inside an await loop. */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

	/**
	 * Pinned to HTTP/1.1: {@link HttpClient} negotiates HTTP/2 by default and remembers
	 * the outcome per host:port, while the harness boots on recycled ports (see
	 * {@code ProxyAppHarness#httpClient} for the full rationale).
	 */
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown (same recipe as ElicitationExpiredUiIT).
	// ---------------------------------------------------------------------

	@BeforeAll
	static void setupBrowser() {
		String binary = detectChromeBinary();
		E2ePreconditions.requireChromeOrSkip(binary);

		WebDriverManager wdm = WebDriverManager.chromedriver();
		String majorVersion = detectMajorVersion(binary);
		if (majorVersion != null) {
			wdm.browserVersion(majorVersion);
		}
		wdm.setup();

		Configuration.browser = "chrome";
		Configuration.headless = true;
		Configuration.browserSize = "1366x900";
		Configuration.timeout = 20_000;
		Configuration.pageLoadTimeout = 60_000;

		ChromeOptions options = new ChromeOptions();
		options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--remote-allow-origins=*");
		Configuration.browserBinary = binary;
		options.setBinary(binary);
		Configuration.browserCapabilities = options;
	}

	private static String detectChromeBinary() {
		String candidate = System.getProperty("webdriver.chrome.binary");
		if (candidate != null && !candidate.isBlank() && Files.isExecutable(Paths.get(candidate))) {
			return candidate;
		}
		candidate = System.getenv("CHROME_BINARY");
		if (candidate != null && !candidate.isBlank() && Files.isExecutable(Paths.get(candidate))) {
			return candidate;
		}

		String userHome = System.getProperty("user.home");
		if (userHome != null) {
			Path puppeteerRoot = Paths.get(userHome, ".cache", "puppeteer", "chrome");
			if (Files.isDirectory(puppeteerRoot)) {
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(puppeteerRoot, "linux-*")) {
					for (Path versionDir : stream) {
						Path bin = versionDir.resolve("chrome-linux64/chrome");
						if (Files.isExecutable(bin)) {
							return bin.toString();
						}
					}
				}
				catch (IOException ignored) {
					/* best-effort */
				}
			}
		}

		for (String linuxPath : new String[] { "/usr/bin/google-chrome", "/usr/bin/chromium",
				"/usr/bin/chromium-browser" }) {
			Path p = Paths.get(linuxPath);
			if (Files.isExecutable(p)) {
				return p.toString();
			}
		}

		return null;
	}

	private static String parseMajorVersionFromPath(String binaryPath) {
		if (binaryPath == null) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:linux|win64|win32|mac|mac_arm)-(\\d+)\\.")
			.matcher(binaryPath);
		if (m.find()) {
			return m.group(1);
		}
		return null;
	}

	private static String detectMajorVersion(String binaryPath) {
		String fromPath = parseMajorVersionFromPath(binaryPath);
		if (fromPath != null) {
			return fromPath;
		}
		if (binaryPath == null) {
			return null;
		}
		try {
			Process probe = new ProcessBuilder(binaryPath, "--version").start();
			boolean finished = probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
			String output = finished
					? new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
			probe.destroyForcibly();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(output);
			if (m.find()) {
				return m.group(1);
			}
		}
		catch (Exception ignored) {
			/* fall through: driver resolution proceeds without a pin */
		}
		return null;
	}

	@AfterEach
	void stopApp() {
		try {
			// Drop the direct-connect localStorage leftovers (lastTransportType,
			// lastConnectionType, lastSseUrl) so the next boot starts from the
			// default connect flow. Must run while the page is still loaded.
			Selenide.executeJavaScript("localStorage.clear();");
		}
		catch (Exception ignored) {
			/* the browser may be closed already */
		}
		try {
			Selenide.closeWebDriver();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
		if (this.app != null) {
			try {
				this.app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			this.app = null;
		}
	}

	// ---------------------------------------------------------------------
	// Tests.
	// ---------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("clientConnectionShowsLiveStateInUi: the Clients tab shows demo-loopback as live")
	@Story("Client connections")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo app, waits for the loopback client to reach CONNECTED through the REST snapshot, "
			+ "and asserts the Clients tab renders the demo-loopback connection card with the live state badge.")
	void clientConnectionShowsLiveStateInUi() throws Exception {
		// given: booted demo app with the loopback client connected
		int port = freePort();
		this.app = startDemoApp(port);
		awaitDemoClientConnected(port);

		// when: the inspector UI opens and connects directly to the demo server
		openAndConnectDirect(port);

		// then: the Clients tab shows the demo-loopback connection as live
		clickTab("clients");
		$$("[data-testid=client-connection-card]").shouldHave(CollectionCondition.size(1));
		SelenideElement card = $("[data-testid=client-connection-card]").shouldBe(visible);
		card.shouldHave(text(DemoClientHandlers.DEMO_CLIENT_NAME));
		card.$("[data-testid=client-connection-state]").shouldHave(text("live"));
	}

	@Test
	@Order(2)
	@DisplayName("timelineShowsClientInitializeAndToolCall: client frames are badged and filterable")
	@Story("Client timeline")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls echo through the demo's own client and asserts the timeline contains the client-side "
			+ "initialize and tools/call frames; the UI side=client filter keeps both rows and every client row "
			+ "carries the client: demo-loopback provenance badge.")
	void timelineShowsClientInitializeAndToolCall() throws Exception {
		// given: booted demo app, loopback client connected, echo called through it
		int port = freePort();
		this.app = startDemoApp(port);
		awaitDemoClientConnected(port);

		McpSyncClient client = demoLoopbackClient();
		CallToolResult echo = client.callTool(new CallToolRequest("echo", Map.of("text", "loopback-timeline-probe")));
		assertThat(echo.isError()).as("echo isError").isFalse();
		assertThat(firstText(echo)).as("echo answer").isEqualTo("loopback-timeline-probe");

		// then: the timeline holds the client-side initialize request ...
		JsonNode initialize = awaitTimelineEvent(port, event -> payloadMatches(event, "initialize", "out"),
				"client-side initialize request (side=client, direction=out)");
		assertThat(initialize.path("payload").path("clientName").asText()).as("clientName of the initialize frame")
			.isEqualTo(DemoClientHandlers.DEMO_CLIENT_NAME);
		// ... and the client-side tools/call request.
		awaitTimelineEvent(port, event -> payloadMatches(event, "tools/call", "out"),
				"client-side tools/call request (side=client, direction=out)");

		// when: the inspector UI opens and filters the timeline to client traffic
		openAndConnectDirect(port);
		clickTab("timeline");
		$("[data-testid=timeline-side-filter]").shouldBe(visible, Duration.ofSeconds(10));
		$("[data-testid=timeline-side-filter-client]").shouldBe(visible).click();

		// then: both client frames render, each carrying the provenance badge
		$$("[data-testid=timeline-row]").findBy(text("initialize")).shouldBe(visible, Duration.ofSeconds(10));
		$$("[data-testid=timeline-row]").findBy(text("tools/call")).shouldBe(visible, Duration.ofSeconds(10));
		$$("[data-testid=timeline-row]").filterBy(Condition.text("initialize"))
			.first()
			.$("[data-testid=timeline-side-badge]")
			.shouldBe(visible)
			.shouldHave(text("client: " + DemoClientHandlers.DEMO_CLIENT_NAME));
	}

	@Test
	@Order(3)
	@DisplayName("askLlmRoundTripCorrelatesWithHandlerBean: sampling round trip carries consumedBy")
	@Story("Server-initiated sampling correlation")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Calls askLlm through the demo's own client. The server-initiated sampling/createMessage "
			+ "request must land in the timeline correlated with DemoClientHandlers#onSampling (consumedBy), "
			+ "the client's outbound JSONRPCResponse must share the request correlation id, and the UI "
			+ "timeline must show the sampling frame under the client filter.")
	void askLlmRoundTripCorrelatesWithHandlerBean() throws Exception {
		// given: booted demo app, loopback client connected
		int port = freePort();
		this.app = startDemoApp(port);
		awaitDemoClientConnected(port);

		// when: askLlm is invoked through the demo's own client: the server sends
		// sampling/createMessage back, DemoClientHandlers#onSampling answers it
		String question = "What is the demo loopback client's name?";
		CallToolResult askLlm = demoLoopbackClient()
			.callTool(new CallToolRequest("askLlm", Map.of("question", question)));
		assertThat(askLlm.isError()).as("askLlm isError").isFalse();
		assertThat(firstText(askLlm)).as("askLlm answer from the @McpSampling handler")
			.contains("demo-echo")
			.contains(question);

		// then: the sampling request is in the timeline, correlated with the handler bean
		JsonNode sampling = awaitTimelineEvent(port, event -> payloadMatches(event, "sampling/createMessage", "in"),
				"server-initiated sampling/createMessage request (side=client, direction=in)");
		assertThat(sampling.path("payload").path("consumedBy").toString()).as("consumedBy of the sampling request")
			.contains("demoClientHandlers")
			.contains("onSampling");

		// ... and the client's outbound JSONRPCResponse shares the correlation id
		String correlationId = sampling.path("correlationId").asText();
		JsonNode response = awaitTimelineEvent(port, event -> {
			if (!"MCP_JSONRPC_RESPONSE".equals(event.path("type").asText())) {
				return false;
			}
			JsonNode payload = event.path("payload");
			return "client".equals(payload.path("side").asText()) && "out".equals(payload.path("direction").asText())
					&& correlationId.equals(event.path("correlationId").asText());
		}, "client's outbound JSONRPCResponse paired with the sampling/createMessage request");
		assertThat(response.path("payload").path("consumedBy").toString())
			.as("consumedBy of the outbound JSONRPCResponse")
			.contains("demoClientHandlers");

		// when: the inspector UI opens and filters the timeline to client traffic
		openAndConnectDirect(port);
		clickTab("timeline");
		$("[data-testid=timeline-side-filter]").shouldBe(visible, Duration.ofSeconds(10));
		$("[data-testid=timeline-side-filter-client]").shouldBe(visible).click();

		// then: the sampling round trip is visible in the UI
		$$("[data-testid=timeline-row]").findBy(text("sampling/createMessage"))
			.shouldBe(visible, Duration.ofSeconds(10));
	}

	// ---------------------------------------------------------------------
	// App / client / REST helpers.
	// ---------------------------------------------------------------------

	/**
	 * Picks a free port before boot: the loopback client URL in {@code application.yml}
	 * embeds {@code ${server.port}}, so the same explicit port has to be passed to the
	 * application. {@code RANDOM_PORT} (0) would end up inside the client URL.
	 */
	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException ex) {
			throw new IllegalStateException("no free port available", ex);
		}
	}

	private ConfigurableApplicationContext startDemoApp(int port) {
		this.app = new SpringApplicationBuilder(DemoApplication.class).run("--server.port=" + port,
				"--spring.ai.mcp.server.protocol=STREAMABLE", "--spring.ai.mcp.inspector.auth-enabled=false");
		return this.app;
	}

	/**
	 * The Spring AI sync client bean list; the demo configures exactly one client.
	 */
	private McpSyncClient demoLoopbackClient() {
		List<McpSyncClient> clients = this.app.getBean("mcpSyncClients", List.class);
		assertThat(clients).as("Spring AI sync MCP clients configured in the demo").hasSize(1);
		return clients.get(0);
	}

	private static String firstText(CallToolResult result) {
		assertThat(result.content()).as("tool result content").isNotEmpty();
		assertThat(result.content().get(0)).as("tool result first content").isInstanceOf(TextContent.class);
		return ((TextContent) result.content().get(0)).text();
	}

	private static boolean payloadMatches(JsonNode event, String method, String direction) {
		JsonNode payload = event.path("payload");
		return "client".equals(payload.path("side").asText()) && method.equals(payload.path("method").asText())
				&& direction.equals(payload.path("direction").asText());
	}

	/**
	 * Polls the timeline REST endpoint until an event matching the predicate shows up.
	 * The loop starts from the empty state and keeps polling while the result is absent
	 * or not yet the wanted one; the timeout exit fails with a descriptive message.
	 */
	private static JsonNode awaitTimelineEvent(int port, Predicate<JsonNode> eventMatches, String description)
			throws Exception {
		String url = "http://127.0.0.1:" + port + "/mcp-inspector/api/timeline?limit=500";
		long deadline = System.nanoTime() + BUDGET.toNanos();
		int scanned = 0;
		while (System.nanoTime() < deadline) {
			JsonNode events = getJson(url);
			if (events != null && events.isArray()) {
				for (JsonNode event : events) {
					scanned++;
					if (eventMatches.test(event)) {
						return event;
					}
				}
			}
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
		fail(description + " was not found in the timeline within " + BUDGET + " (scanned " + scanned + " events)");
		return null;
	}

	/**
	 * Polls the client-connections REST snapshot until the demo-loopback client reports
	 * CONNECTED. The loop starts from the empty state (no snapshot, or the connection not
	 * yet connected) and fails with the last snapshot on timeout.
	 */
	private static void awaitDemoClientConnected(int port) throws Exception {
		String url = "http://127.0.0.1:" + port + "/mcp-inspector/api/client-connections";
		long deadline = System.nanoTime() + BUDGET.toNanos();
		String lastSnapshot = "<no response yet>";
		while (System.nanoTime() < deadline) {
			JsonNode snapshot = getJson(url);
			if (snapshot != null) {
				lastSnapshot = snapshot.toString();
				for (JsonNode connection : snapshot) {
					if (DemoClientHandlers.DEMO_CLIENT_NAME.equals(connection.path("name").asText())
							&& "CONNECTED".equals(connection.path("state").asText())) {
						return;
					}
				}
			}
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
		fail("the " + DemoClientHandlers.DEMO_CLIENT_NAME + " client did not reach CONNECTED within " + BUDGET
				+ "; last snapshot: " + lastSnapshot);
	}

	private static JsonNode getJson(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return null;
			}
			return JSON.readTree(response.body());
		}
		catch (Exception ex) {
			// The server may not be listening yet during the early poll cycles.
			return null;
		}
	}

	// ---------------------------------------------------------------------
	// Shared DOM helpers (same recipe as InspectorUiIT / ElicitationExpiredUiIT).
	// ---------------------------------------------------------------------

	private static void clickTab(String value) {
		$("[data-testid=tab-list]").shouldBe(visible, Duration.ofSeconds(15));
		$("[data-testid=tab-trigger-" + value + "]").shouldBe(visible, Duration.ofSeconds(10)).click();
		$("[data-testid=tab-content-" + value + "]").shouldBe(visible, Duration.ofSeconds(5));
	}

	/**
	 * Opens the page and connects directly to the demo's own MCP endpoint: streamable
	 * HTTP transport, Direct connection type, absolute same-origin URL (same recipe as
	 * InspectorUiIT's direct-connect boot). The direct path bypasses the inspector proxy,
	 * which this class never needs.
	 */
	private void openAndConnectDirect(int port) {
		Configuration.baseUrl = "http://localhost:" + port;
		open("/mcp-inspector/index.html");
		// Sidebar must finish first render before we touch the selects.
		$("[data-testid=sidebar]").shouldBe(visible, Duration.ofSeconds(15));
		$("#transport-type-select").shouldBe(visible, Duration.ofSeconds(15)).click();
		$("[data-testid=select-option-streamable-http]").shouldBe(visible).click();
		$("#connection-type-select").shouldBe(visible, Duration.ofSeconds(15)).click();
		$("[data-testid=select-option-direct]").shouldBe(visible).click();
		setReactInputValue("#sse-url-input", Configuration.baseUrl + "/mcp");
		$("[data-testid=sidebar]").$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();
		// Restart/Reconnect is only mounted when connectionStatus === "connected".
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
	}

	/**
	 * Sets a React-controlled input's value via the native setter plus a synthetic input
	 * event, so React's onChange observes it (Selenide's stock setValue loses characters
	 * on conditional re-renders).
	 */
	private static void setReactInputValue(String cssSelector, String value) {
		JavascriptExecutor js = (JavascriptExecutor) WebDriverRunner.getWebDriver();
		js.executeScript("var el = document.querySelector(arguments[0]);" + "if (!el) { return; }"
				+ "var setter = Object.getOwnPropertyDescriptor(" + "  window.HTMLInputElement.prototype, 'value').set;"
				+ "setter.call(el, arguments[1]);" + "el.dispatchEvent(new Event('input', { bubbles: true }));"
				+ "el.dispatchEvent(new Event('change', { bubbles: true }));", cssSelector, value);
	}

}
