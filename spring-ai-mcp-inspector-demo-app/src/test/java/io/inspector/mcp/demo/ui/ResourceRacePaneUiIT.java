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
import java.time.Instant;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.e2e.E2ePreconditions;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.inspector.mcp.demo.resources.DemoSlowResourcesProvider;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.ConfigurableApplicationContext;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser E2E for the Resources-preview-pane race (issue #344), run once per web stack
 * (this class ships in the demo-app test-jar and both stack modules pick it up through
 * Failsafe's {@code dependenciesToScan}).
 *
 * <p>
 * The demo server exposes a delayed resource ({@code demo://slow-greeting}, about two
 * seconds of server-side delay) and an immediate one ({@code demo://fast-greeting}).
 * Selecting the slow row and then the fast row means the slow reply can only land after
 * the fast one is already rendered, which is exactly the interleaving that used to
 * overwrite the preview with the stale payload.
 *
 * Stack scope: the race is asserted on the servlet stack only. The reactive proxy relays
 * browser frames serially, because the upstream send path occupies the Netty event loop
 * of the incoming POST (the legacy SSE transport's blocking sendMessage runs on the
 * subscribing thread), so the second resources/read is forwarded only after the first one
 * completes and the interleaving is unobservable on that wire. The Assumptions gate in
 * the test records this capability check; the core proxy fix and the re-enabled webflux
 * leg are tracked as a follow-up.
 *
 * The test:
 * <ul>
 * <li>asserts the preview shows the fast reply after the selection switch;</li>
 * <li>waits on the proxy timeline until the slow {@code resources/read} reply has
 * actually landed (so the negative assertion is deterministic, not timed);</li>
 * <li>asserts the preview still shows the fast reply and never the slow one;</li>
 * <li>asserts on the timeline that the slow reply did overtake the fast one, proving the
 * race really happened rather than the slow read never returning; an attempt whose stack
 * serialized the two reads cannot race at all and is retried with a fresh selection pair
 * until the inversion shows up on the wire.</li>
 * </ul>
 *
 * <p>
 * SSE is used as the transport deliberately: the streamable-http proxy handshake is still
 * unreliable from the upstream client (see {@code InspectorUiIT.Connect}'s comment), and
 * the preview logic under test is transport-agnostic.
 */
@Epic("MCP Inspector UI")
@Feature("Resources pane stale-preview race")
class ResourceRacePaneUiIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Duration BUDGET = Duration.ofSeconds(30);

	/**
	 * Bounded retry budget for the wire-level interleaving: whether the slow and the fast
	 * read actually interleave is a scheduler property of the stack under test (a stack
	 * that serializes per-session requests answers the slow one first, so no race can
	 * occur). An attempt without the inversion is inconclusive, not a pass or a guard
	 * failure: the selection pair is retried until the wire proves it.
	 */
	private static final int RACE_ATTEMPTS = 4;

	/**
	 * Visible name of the neutral {@code demo://greeting} row (DemoResourcesProvider):
	 * selecting it resets the selection between attempts, because re-clicking an already
	 * selected row does not issue a fresh resources/read.
	 */
	private static final String NEUTRAL_ROW_NAME = "demo-greeting";

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
		options.setCapability("goog:loggingPrefs", java.util.Map.of("performance", "ALL", "browser", "ALL"));
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
		return majorVersionFromBinary(binaryPath);
	}

	/**
	 * Distro packages such as {@code /usr/bin/chromium-browser} carry no version
	 * directory, so the path regex above cannot pin the driver major for them. Ask the
	 * binary itself: a ChromeDriver from the latest release refuses to drive an older
	 * browser, and an unpinned WebDriverManager download always resolves to the latest
	 * release instead.
	 */
	private static String majorVersionFromBinary(String binaryPath) {
		try {
			Process process = new ProcessBuilder(binaryPath, "--version").redirectErrorStream(true).start();
			if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			String output;
			try (java.util.Scanner scanner = new java.util.Scanner(process.getInputStream()).useDelimiter("\\A")) {
				output = scanner.hasNext() ? scanner.next() : "";
			}
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(output);
			return m.find() ? m.group(1) : null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (Exception ex) {
			return null;
		}
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
	@DisplayName("slowReplyMustNotOvertakeFastSelection: the late reply of a superseded "
			+ "resources/read never overwrites the preview")
	@Story("Stale resources/read reply must not overwrite the preview of the current selection")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Selects the delayed demo://slow-greeting resource, immediately selects the "
			+ "immediate demo://fast-greeting one, and asserts the preview shows the fast reply, "
			+ "keeps showing it after the delayed reply lands, and never shows the slow payload. "
			+ "The proxy timeline proves the slow reply really arrived after the fast one, so the "
			+ "negative assertion is deterministic (issue #344 regression). An attempt whose "
			+ "stack serialized the two reads cannot race and is retried until the inversion "
			+ "shows up on the wire.")
	void slowReplyMustNotOvertakeFastSelection() throws Exception {
		// The race needs two resources/read requests in flight on one session. The
		// reactive proxy cannot produce that interleaving yet (its upstream send path
		// blocks the Netty event loop of the POST thread, so frames relay serially),
		// so this test is a webmvc-only capability until the core fix lands.
		Assumptions.assumeTrue("webmvc".equals(ProxyAppHarness.stack()),
				"resources/read interleaving is unobservable on the webflux stack: the "
						+ "reactive proxy relays browser frames serially because the upstream "
						+ "send path occupies the Netty event loop of the POST thread; this "
						+ "race test runs on webmvc until the core proxy fix lands");
		// given: boot, connect, open the Resources pane, list the demo resources
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();

		clickTab("resources");
		// resources/list is not automatic after connect; List Resources is idempotent
		// once the list is non-empty (InspectorUiIT, same recipe).
		activePanel().$(byText("List Resources")).shouldBe(visible, Duration.ofSeconds(10)).click();

		// when: select the slow resource, then immediately the fast one; the slow
		// read is still in flight (server sleeps ~2s) when the fast reply renders.
		// Whether the two reads interleave on the wire is a scheduler property of the
		// stack under test: an attempt where the slow reply lands first cannot race,
		// so the guard is not exercised and the attempt is inconclusive. Reset the
		// selection with the neutral row and retry until the wire proves the
		// inversion (bounded by RACE_ATTEMPTS).
		Instant slowReplyAt = null;
		for (int attempt = 1; attempt <= RACE_ATTEMPTS && slowReplyAt == null; attempt++) {
			if (attempt > 1) {
				selectRow(NEUTRAL_ROW_NAME);
			}
			Instant attemptStart = Instant.now();
			selectRow(DemoSlowResourcesProvider.SLOW_NAME);
			selectRow(DemoSlowResourcesProvider.FAST_NAME);
			activePanel().shouldHave(text(DemoSlowResourcesProvider.FAST_CONTENT), Duration.ofSeconds(10));
			// both replies of this attempt must have landed before judging the order:
			// the since filter keeps earlier attempts' replies out of the comparison
			Instant attemptSlowAt = awaitReplySince(DemoSlowResourcesProvider.SLOW_URI,
					DemoSlowResourcesProvider.SLOW_CONTENT, attemptStart);
			Instant attemptFastAt = awaitReplySince(DemoSlowResourcesProvider.FAST_URI,
					DemoSlowResourcesProvider.FAST_CONTENT, attemptStart);
			if (attemptSlowAt.isAfter(attemptFastAt)) {
				slowReplyAt = attemptSlowAt;
			}
		}

		// then: the race really happened, the slow reply overtook the fast one on the
		// wire (this is the interleaving the guard must be immune to)
		assertThat(slowReplyAt)
			.as("interleaved attempt with the slow reply strictly after " + "the fast one, within " + RACE_ATTEMPTS
					+ " attempts")
			.isNotNull();

		// then: the preview still shows the current selection and never the stale
		// slow payload (regression guard for issue #344)
		activePanel().shouldHave(text(DemoSlowResourcesProvider.FAST_CONTENT), Duration.ofSeconds(10));
		activePanel().shouldNotHave(text(DemoSlowResourcesProvider.SLOW_CONTENT));

		// and: no raw JSON-RPC toast leaked (issue #212 regression class)
		assertNoRawToast();
	}

	// ---------------------------------------------------------------------
	// DOM helpers (kept in step with InspectorUiIT / InputRequiredRoundTripUiIT).
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
	 * substring matching would resolve the fast resource to the slow one and vice versa,
	 * same recipe as InputRequiredRoundTripUiIT#selectRow).
	 */
	private static void selectRow(String name) {
		activePanel().$$(".cursor-pointer .truncate")
			.findBy(exactText(name))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
	}

	/**
	 * Issue #212 regression class: error paths must surface as clean UI errors, never as
	 * raw JSON-RPC toasts.
	 */
	private static void assertNoRawToast() {
		$$("[data-sonner-toast]").shouldBe(com.codeborne.selenide.CollectionCondition.empty);
	}

	// ---------------------------------------------------------------------
	// Wire-level readback: /api/timeline must show the slow resources/read
	// reply arriving after the fast one (proof the race actually happened).
	// ---------------------------------------------------------------------

	/**
	 * Polls the proxy timeline until the latest {@code resources/read} reply carrying the
	 * given resource's payload has landed, and returns its timestamp. Only replies at or
	 * after {@code since} count, so a retry attempt is judged by its own reply pair.
	 * Fails if the reply never arrives: the assertions after this wait are only
	 * meaningful once the superseded reply really returned.
	 */
	private Instant awaitReplySince(String uri, String expectedContent, Instant since) throws Exception {
		Instant deadline = Instant.now().plus(BUDGET);
		Instant found = null;
		while (found == null && Instant.now().isBefore(deadline)) {
			found = timelineReplyTimestamp(uri, expectedContent, since);
			if (found == null) {
				Thread.sleep(250);
			}
		}
		assertThat(found).as("resources/read reply for %s with its payload on the timeline", uri).isNotNull();
		return found;
	}

	/**
	 * Reads the proxy timeline and returns the timestamp of the latest
	 * {@code resources/read} reply whose result carries the given resource URI and
	 * payload text and whose timestamp is not before {@code since}, or {@code null} if
	 * there is none: the latest match wins, so a retry attempt is judged by its own reply
	 * pair, not by earlier attempts'.
	 */
	private Instant timelineReplyTimestamp(String uri, String expectedContent, Instant since) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(Configuration.baseUrl + "/mcp-inspector/api/timeline?limit=500"))
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("timeline API status").isEqualTo(200);

		JsonNode events = MAPPER.readTree(response.body());
		assertThat(events.isArray()).as("timeline API returns an array").isTrue();

		Instant latest = null;
		for (JsonNode event : events) {
			if (!"MCP_JSONRPC_RESPONSE".equals(event.path("type").asText())) {
				continue;
			}
			JsonNode contents = event.path("payload").path("result").path("contents");
			if (!contents.isArray() || contents.isEmpty()) {
				continue;
			}
			JsonNode first = contents.get(0);
			if (!uri.equals(first.path("uri").asText()) || !expectedContent.equals(first.path("text").asText())) {
				continue;
			}
			Instant at = Instant.parse(event.path("timestamp").asText());
			if (!at.isBefore(since) && (latest == null || at.isAfter(latest))) {
				latest = at;
			}
		}
		return latest;
	}

}
