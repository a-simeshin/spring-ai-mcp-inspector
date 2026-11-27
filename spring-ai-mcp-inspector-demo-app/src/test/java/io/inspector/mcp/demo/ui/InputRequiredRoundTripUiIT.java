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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.e2e.E2ePreconditions;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.ConfigurableApplicationContext;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser E2E for the SEP-2322 input_required round-trip against the
 * {@code multiRoundTripEcho} demo tool, run once per web stack (this class ships in the
 * demo-app test-jar and both stack modules pick it up through Failsafe's
 * {@code dependenciesToScan}).
 *
 * <p>
 * What the card asks to prove, and where:
 * <ul>
 * <li>the {@code input_required} requests render as typed controls (string input, enum
 * select, integer input, then a boolean checkbox for round 2) in the Input Required
 * panel;</li>
 * <li>each re-send carries {@code inputResponses} plus the {@code requestState} echoed
 * byte-exact. Verified against the proxy's own {@code /api/timeline} record, the same
 * bytes the server validated. The tool only accepts a re-send that echoes a token it
 * minted, so the happy path finishing proves the echo was byte-exact;</li>
 * <li>the final result renders in the Timeline as one correlated chain (3 rounds, state
 * "final result");</li>
 * <li>error path: a server-rejected answer (negative refills) surfaces as a clean UI
 * error in the tool result pane and the chain flips to "error" in the Timeline. The
 * mismatched-requestState rejection itself is not drivable from the UI (the client always
 * echoes the token it received), so the browser-level error path is the server
 * {@code -32602} rejection, and the raw-token rejection contract stays in
 * {@code MultiRoundTripEchoIT} at the protocol level;</li>
 * <li>issue #212 regression class: no raw JSON-RPC toast leaks to the user.</li>
 * </ul>
 *
 * <p>
 * SSE is used as the transport deliberately: the streamable-http proxy handshake is still
 * unreliable from the upstream client (see {@code InspectorUiIT.Connect}'s comment), and
 * the round-trip logic under test is transport-agnostic.
 */
@Epic("MCP Inspector UI")
@Feature("SEP-2322 input_required round-trip")
class InputRequiredRoundTripUiIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown (same recipe as InspectorUiSmokeIT).
	// ---------------------------------------------------------------------

	@BeforeAll
	static void setupBrowser() {
		String binary = detectChromeBinary();
		E2ePreconditions.requireChromeOrSkip(binary);

		WebDriverManager wdm = WebDriverManager.chromedriver();
		String majorVersion = parseMajorVersionFromPath(binary);
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

	/**
	 * Resolves a Chrome binary with the same priority list as
	 * {@code InspectorUiSmokeIT#detectChromeBinary()}: {@code webdriver.chrome.binary}
	 * property, {@code CHROME_BINARY} env, macOS production Chrome, Puppeteer cache,
	 * Linux fallbacks. {@code null} means skip, not fail.
	 */
	private static String detectChromeBinary() {
		String candidate = System.getProperty("webdriver.chrome.binary");
		if (candidate != null && !candidate.isBlank() && Files.isExecutable(Paths.get(candidate))) {
			return candidate;
		}
		candidate = System.getenv("CHROME_BINARY");
		if (candidate != null && !candidate.isBlank() && Files.isExecutable(Paths.get(candidate))) {
			return candidate;
		}

		Path macProd = Paths.get("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
		if (Files.isExecutable(macProd)) {
			return macProd.toString();
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
				catch (java.io.IOException ignored) {
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
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:mac_arm|mac|linux|win64|win32)-(\\d+)\\.")
			.matcher(binaryPath);
		if (m.find()) {
			return m.group(1);
		}
		return null;
	}

	@AfterEach
	void stopApp() {
		try {
			Selenide.closeWebDriver();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
		if (app != null) {
			try {
				app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			app = null;
		}
	}

	// ---------------------------------------------------------------------
	// Tests.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("multiRoundTripEchoHappyPath: two input_required rounds then the final echo")
	@Story("Round-trip happy path from the browser")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Runs multiRoundTripEcho from the Tools tab, answers both input_required rounds in the "
			+ "Input Required panel (string, enum, integer, then boolean), and asserts the final result "
			+ "renders and the Timeline threads the whole conversation as one correlated chain. The "
			+ "re-send payloads are read back from /api/timeline and must carry inputResponses plus the "
			+ "requestState token byte-exact as the panel showed it.")
	void multiRoundTripEchoHappyPath() throws Exception {
		// given: boot, connect, invoke the tool
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();

		clickTab("tools");
		// tools/list is not automatic after connect; List Tools is idempotent once
		// the list is non-empty (InspectorUiIT, same recipe).
		activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10)).click();
		// The row label is the tool's title annotation ("Multi round-trip echo
		// (SEP-2322 fixture)"), not the wire name multiRoundTripEcho.
		selectRow("Multi round-trip echo (SEP-2322 fixture)");
		$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10)).click();

		// when: round 1 auto-focuses the Input Required panel
		$("[data-testid=input-required-chain]").shouldBe(visible, BUDGET);
		$("[data-testid=input-required-round]").shouldHave(text("Round 1"), BUDGET);
		String tokenOne = requestStateToken();
		assertThat(tokenOne).as("round 1 requestState token").startsWith("mrt-echo.r1.");

		// when: answer the three typed requests
		setReactInputValue("[data-testid=input-request-favoriteCity-field-city] input", "Lisbon");
		$("[data-testid=input-request-coffeeSize-field-size] select").selectOptionByValue("large");
		setReactInputValue("[data-testid=input-request-refillCount-field-refills] input", "3");
		$("[data-testid=input-required-submit]").shouldBe(visible, Duration.ofSeconds(10)).click();

		// then: round 2 arrives with a new token and a boolean control
		$("[data-testid=input-required-round]").shouldHave(text("Round 2"), BUDGET);
		String tokenTwo = requestStateToken();
		assertThat(tokenTwo).as("round 2 requestState token").startsWith("mrt-echo.r2.").isNotEqualTo(tokenOne);

		$("[data-testid=input-request-confirmation-field-confirmed] input").shouldBe(visible, Duration.ofSeconds(10))
			.click();
		$("[data-testid=input-required-submit]").shouldBe(visible, Duration.ofSeconds(10)).click();

		// then: the chain closes and the final result renders in the Tools tab.
		// The values are deliberately different from the tool description's example
		// (Berlin / 2), so this string can only come from the rendered result.
		$("[data-testid=input-required-chain]").shouldNotBe(visible, BUDGET);
		clickTab("tools");
		activePanel().shouldHave(
				text("multiRoundTripEcho: round 1 - city=Lisbon, size=large, refills=3; round 2 - confirmed=true"),
				BUDGET);

		// then: the Timeline threads the whole conversation as one chain
		clickTab("timeline");
		$("[data-testid=timeline-chain]").shouldBe(visible, BUDGET);
		$("[data-testid=timeline-chain]").click();
		$("[data-testid=timeline-chain-state]").shouldHave(text("final result"), BUDGET);
		$("[data-testid=timeline-chain-rounds]").shouldHave(text("3 rounds"), BUDGET);

		// and: the outgoing re-sends on the wire carried inputResponses plus the
		// byte-exact echoed requestState, as recorded by the proxy's timeline
		assertReSendPayloads(tokenOne, tokenTwo);

		// and: no raw JSON-RPC toast leaked (issue #212 regression class)
		assertNoRawToast();
	}

	@Test
	@DisplayName("serverRejectionIsCleanError: a -32602 answer surfaces without a raw toast")
	@Story("Round-trip error path from the browser")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Answers round 1 with a negative refill count; the server rejects the re-send with "
			+ "JSON-RPC -32602. The chain must close, the Tools tab must show a clean wrapped error "
			+ "('input_required chain failed: ...'), the Timeline chain must flip to the error state, "
			+ "and no raw JSON-RPC error toast may appear (issue #212 regression class).")
	void serverRejectionIsCleanError() throws Exception {
		// given: boot, connect, invoke, answer round 1 with an invalid integer
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();

		clickTab("tools");
		// tools/list is not automatic after connect; List Tools is idempotent once
		// the list is non-empty (InspectorUiIT, same recipe).
		activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10)).click();
		// The row label is the tool's title annotation, not the wire name.
		selectRow("Multi round-trip echo (SEP-2322 fixture)");
		$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10)).click();

		$("[data-testid=input-required-chain]").shouldBe(visible, BUDGET);
		setReactInputValue("[data-testid=input-request-favoriteCity-field-city] input", "Lisbon");
		$("[data-testid=input-request-coffeeSize-field-size] select").selectOptionByValue("large");
		setReactInputValue("[data-testid=input-request-refillCount-field-refills] input", "-1");

		// when: the rejected re-send
		$("[data-testid=input-required-submit]").shouldBe(visible, Duration.ofSeconds(10)).click();

		// then: the chain is closed, no dead panel left behind
		$("[data-testid=input-required-chain]").shouldNotBe(visible, BUDGET);

		// then: the Tools tab surfaces the wrapped, human-readable error
		clickTab("tools");
		activePanel().shouldHave(text("input_required chain failed:"), BUDGET);
		activePanel().shouldHave(text("must be >= 0"), BUDGET);

		// then: the Timeline shows the chain in the error state
		clickTab("timeline");
		$("[data-testid=timeline-chain]").shouldBe(visible, BUDGET);
		$("[data-testid=timeline-chain]").click();
		$("[data-testid=timeline-chain-state]").shouldHave(text("error"), BUDGET);

		// and: no raw JSON-RPC toast leaked (issue #212 regression class)
		assertNoRawToast();
	}

	// ---------------------------------------------------------------------
	// DOM helpers (kept in step with InspectorUiIT / InspectorUiSmokeIT).
	// ---------------------------------------------------------------------

	private static SelenideElement sidebar() {
		return $(".bg-card.border-r");
	}

	private static SelenideElement activePanel() {
		return $("[role=tabpanel][data-state=active]");
	}

	private static void openAndConnect() {
		open("/mcp-inspector/index.html");
		sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
	}

	/**
	 * Clicks the Radix tab trigger by value suffix and asserts both the trigger and the
	 * panel turned active (same recipe as InspectorUiIT#clickTab).
	 */
	private static void clickTab(String value) {
		$("[role=tablist]").shouldBe(visible, Duration.ofSeconds(15));
		$("[role=tab][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(10)).click();
		$("[role=tab][data-state=active][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
		$("[role=tabpanel][data-state=active][id$='-content-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
	}

	/**
	 * Selects a row from the active ListPane by its exact visible name (exact match:
	 * substring matching resolved "echo" to "slowEcho" in InspectorUiIT).
	 */
	private static void selectRow(String name) {
		activePanel().$$(".cursor-pointer .truncate")
			.findBy(exactText(name))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
	}

	/**
	 * The full requestState token. The visible span is CSS-truncated, so the byte-exact
	 * value is read from the title attribute instead.
	 */
	private static String requestStateToken() {
		return $("[data-testid=input-required-request-state]").shouldBe(visible, Duration.ofSeconds(10))
			.getAttribute("title");
	}

	/**
	 * Sets a React-controlled input's value via the native setter plus a synthetic input
	 * event, so React's onChange observes it (Selenide's stock setValue loses characters
	 * on conditional re-renders; same recipe as InspectorUiIT#setReactInputValue).
	 */
	private static void setReactInputValue(String cssSelector, String value) {
		JavascriptExecutor js = (JavascriptExecutor) com.codeborne.selenide.WebDriverRunner.getWebDriver();
		js.executeScript("var el = document.querySelector(arguments[0]);" + "if (!el) { return; }"
				+ "var setter = Object.getOwnPropertyDescriptor(" + "  window.HTMLInputElement.prototype, 'value').set;"
				+ "setter.call(el, arguments[1]);" + "el.dispatchEvent(new Event('input', { bubbles: true }));"
				+ "el.dispatchEvent(new Event('change', { bubbles: true }));", cssSelector, value);
	}

	/**
	 * Issue #212 regression class: error paths must surface as clean UI errors, never as
	 * raw JSON-RPC toasts.
	 */
	private static void assertNoRawToast() {
		$$("[data-sonner-toast]").shouldBe(com.codeborne.selenide.CollectionCondition.empty);
	}

	// ---------------------------------------------------------------------
	// Wire-level readback: /api/timeline must show the re-sends with the
	// byte-exact echoed requestState plus the answers.
	// ---------------------------------------------------------------------

	/**
	 * Fetches the proxy's timeline and asserts the two {@code tools/call} re-sends
	 * carried {@code inputResponses} plus the exact requestState tokens the panel
	 * displayed.
	 */
	private void assertReSendPayloads(String expectedTokenOne, String expectedTokenTwo) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(Configuration.baseUrl + "/mcp-inspector/api/timeline?limit=500"))
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("timeline API status").isEqualTo(200);

		JsonNode events = MAPPER.readTree(response.body());
		assertThat(events.isArray()).as("timeline API returns an array").isTrue();

		List<JsonNode> reSends = new ArrayList<>();
		for (JsonNode event : events) {
			if (!"MCP_JSONRPC_REQUEST".equals(event.path("type").asText())) {
				continue;
			}
			JsonNode payload = event.path("payload");
			if (!"tools/call".equals(payload.path("method").asText())) {
				continue;
			}
			if (payload.path("params").path("inputResponses").isMissingNode()) {
				continue;
			}
			reSends.add(payload);
		}
		assertThat(reSends).as("tools/call re-sends with inputResponses on the timeline").hasSize(2);

		// The timeline API returns newest-first; match by round instead of relying
		// on list order.
		JsonNode sendOne = reSends.get(0).path("params").path("requestState").asText().startsWith("mrt-echo.r1.")
				? reSends.get(0) : reSends.get(1);
		JsonNode sendTwo = sendOne == reSends.get(0) ? reSends.get(1) : reSends.get(0);

		// Byte-exact echo: the tokens must be the exact strings the panel showed.
		assertThat(sendOne.path("params").path("requestState").asText())
			.as("re-send 1 echoes the round-1 requestState byte-exact")
			.isEqualTo(expectedTokenOne);
		assertThat(sendTwo.path("params").path("requestState").asText())
			.as("re-send 2 echoes the round-2 requestState byte-exact")
			.isEqualTo(expectedTokenTwo);

		// The answers reached the server as inputResponses.
		assertThat(sendOne.path("params")
			.path("inputResponses")
			.path("favoriteCity")
			.path("content")
			.path("city")
			.asText()).as("re-send 1 city answer").isEqualTo("Lisbon");
		assertThat(
				sendOne.path("params").path("inputResponses").path("coffeeSize").path("content").path("size").asText())
			.as("re-send 1 size answer")
			.isEqualTo("large");
		assertThat(sendOne.path("params")
			.path("inputResponses")
			.path("refillCount")
			.path("content")
			.path("refills")
			.asInt()).as("re-send 1 refill answer").isEqualTo(3);
		assertThat(sendTwo.path("params")
			.path("inputResponses")
			.path("confirmation")
			.path("content")
			.path("confirmed")
			.asBoolean()).as("re-send 2 confirmation answer").isTrue();
	}

}
