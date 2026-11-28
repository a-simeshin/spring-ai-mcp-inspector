/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.e2e;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.demo.DemoApplication;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;

/**
 * End-to-end Selenide journey for the Tool Health table (issue #370): a scripted mixed
 * success/failure tool sequence must aggregate into per-tool rows (calls, errors, error
 * rate, percentiles, last result flag), sort by error rate via the header anchors, and
 * drive the timeline click-through: clicking a row narrows the timeline list to that
 * tool's calls until the filter chip clears it.
 *
 * <p>
 * Browser / boot helpers mirror {@code InspectorUiIT} and {@code ConnectFailureIT}
 * verbatim: version-resolved WebDriverManager,
 * {@link E2ePreconditions#requireChromeOrSkip(String)} (skip locally without a browser,
 * hard failure in CI), command-line {@code --server.port=0} so the demo never collides
 * with {@code 8080}, and the default SSE transport. The scripted sequence uses the
 * always-succeeding {@code echo} and the always-failing {@code errorTool} demo tools, so
 * every aggregate value below is deterministic.
 */
@Epic("MCP Inspector UI")
@Feature("Tool Health table E2E")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolHealthUiIT {

	private ConfigurableApplicationContext app;

	@BeforeAll
	void setupBrowser() {
		String binary = detectChromeBinary();
		E2ePreconditions.requireChromeOrSkip(binary);

		var wdm = WebDriverManager.chromedriver();
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
		LoggingPreferences logPrefs = new LoggingPreferences();
		logPrefs.enable(LogType.BROWSER, Level.ALL);
		options.setCapability("goog:loggingPrefs", logPrefs);
		Configuration.browserBinary = binary;
		options.setBinary(binary);
		Configuration.browserCapabilities = options;
	}

	@AfterAll
	void tearDown() {
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

	/**
	 * Boots {@link DemoApplication} on a random port with the default SSE transport and
	 * the inspector auth disabled, the same boot every browser IT uses.
	 */
	private void startApp() {
		String[] args = new String[] { "--server.port=0", "--spring.ai.mcp.server.protocol=SSE",
				"--spring.ai.mcp.inspector.auth-enabled=false", };
		app = new SpringApplicationBuilder(DemoApplication.class).run(args);

		int port = ((WebServerApplicationContext) app).getWebServer().getPort();
		Configuration.baseUrl = "http://localhost:" + port;
	}

	// ---------------------------------------------------------------------
	// Shared DOM helpers: mirrored from InspectorUiIT (UPSTREAM_DOM_MAP.md).
	// ---------------------------------------------------------------------

	/** Sidebar Connect button (first-time; has no testid, match by visible text). */
	private static SelenideElement connectButton() {
		return $(byText("Connect"));
	}

	/**
	 * Active tab panel. Radix mounts only the active TabsContent, so exactly one
	 * {@code tab-content-<value>} anchor exists while a tab is open; the
	 * {@code :not([hidden])} filter is kept as an explicit visibility guard.
	 */
	private static SelenideElement activePanel() {
		return $("[data-testid^=tab-content-]:not([hidden])");
	}

	/**
	 * Selects a row from the active ListPane by its exact visible name. The name lives in
	 * a {@code <span data-testid="row-name">} inside the clickable row; {@code exactText}
	 * keeps "echo" from matching the "slowEcho" row.
	 */
	private static void selectRow(String name) {
		activePanel().$$("[data-testid=row-name]")
			.findBy(exactText(name))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
	}

	/**
	 * Click the tab trigger with the given Radix value and confirm the pane mounted.
	 */
	private static void clickTab(String value) {
		$("[data-testid=tab-list]").shouldBe(visible, Duration.ofSeconds(15));
		$("[data-testid=tab-trigger-" + value + "]").shouldBe(visible, Duration.ofSeconds(10)).click();
		$("[data-testid=tab-content-" + value + "]").shouldBe(visible, Duration.ofSeconds(5));
	}

	/**
	 * Runs one tool call through the Tools tab and waits for its outcome text in the
	 * ToolResults pane, so the next step never races a pending call.
	 * @param name the tool to select in the list
	 * @param argument the echo-style free-text argument ({@code #text} input), or
	 * {@code null} for a no-argument tool
	 * @param expectedOutcome a text only the finished result pane renders
	 */
	private void runTool(String name, String argument, String expectedOutcome) {
		selectRow(name);
		if (argument != null) {
			$("#text").shouldBe(visible, Duration.ofSeconds(10)).setValue(argument);
		}
		activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(10)).click();
		activePanel().shouldHave(text(expectedOutcome), Duration.ofSeconds(30));
	}

	@Test
	@Story("Tool Health")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A scripted mixed success/failure sequence (two answered echo calls, one "
			+ "failing errorTool call) aggregates into per-tool rows, the error-rate sort "
			+ "orders the rows, and clicking the failing tool's row filters the timeline "
			+ "to exactly its request/response pair until the chip clears the filter.")
	@DisplayName("toolHealthJourney: aggregates a mixed sequence, sorts, and filters the timeline")
	void toolHealth_mixedSequence_aggregatesSortsAndFilters() {
		startApp();
		open("/mcp-inspector/index.html");
		connectButton().shouldBe(visible, Duration.ofSeconds(15)).click();
		// Restart/Reconnect is only mounted once connectionStatus === "connected".
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));

		// --- the scripted mixed success/failure sequence -------------------------
		clickTab("tools");
		activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10)).click();
		activePanel().$$("[data-testid^=tool-row-]").shouldHave(CollectionCondition.sizeGreaterThan(0));
		runTool("echo", "hello", "hello");
		runTool("echo", "world", "world");
		runTool("errorTool", null, "intentional demo failure");

		// --- the Tool Health table aggregates exactly the two called tools -------
		clickTab("timeline");
		$("[data-testid=tool-health-table]").shouldBe(visible, Duration.ofSeconds(15));
		// echo: 2 calls, 0 errors (0%), last call ok. errorTool: 1 call, 1 error
		// (100%), last call error. tools/list and the initialize handshake are
		// recorded in the timeline but are not tools/call, so they aggregate to
		// nothing and the table holds exactly two rows.
		$("[data-testid=tool-health-row-echo]").shouldHave(text("0%"), text("ok"));
		$("[data-testid=tool-health-row-errorTool]").shouldHave(text("100%"), text("error"));
		$$("[data-testid^=tool-health-row-]").shouldHave(CollectionCondition.size(2));

		// --- sorting by error rate ----------------------------------------------
		// Default view: error rate descending, the failing tool first.
		$$("[data-testid^=tool-health-row-]").first()
			.shouldHave(attribute("data-testid", "tool-health-row-errorTool"), Duration.ofSeconds(5));
		// Header click toggles the same column to ascending: the healthy tool rises.
		$("[data-testid=tool-health-sort-error-rate]").click();
		$$("[data-testid^=tool-health-row-]").first()
			.shouldHave(attribute("data-testid", "tool-health-row-echo"), Duration.ofSeconds(5));

		// --- click-through: the row filters the timeline to that tool's calls ----
		$$("[data-testid=timeline-row]").shouldHave(CollectionCondition.sizeGreaterThan(2));
		int unfilteredTimelineRows = $$("[data-testid=timeline-row]").size();
		$("[data-testid=tool-health-row-errorTool]").click();
		$("[data-testid=timeline-tool-filter-clear]").shouldBe(visible, Duration.ofSeconds(10))
			.shouldHave(text("tool: errorTool"));
		// errorTool made exactly one call: one request plus its correlated response.
		ElementsCollection filtered = $$("[data-testid=timeline-row]");
		filtered.shouldHave(CollectionCondition.size(2));
		// Expand both frames; the request payload carries params.name=errorTool, so
		// at least one expanded row must name the tool (order-independent: keep-alive
		// merges may re-sort the list while the filter is active).
		filtered.get(0).click();
		filtered.get(1).click();
		$$("[data-testid=timeline-row]").findBy(text("errorTool")).shouldBe(visible, Duration.ofSeconds(5));

		// --- the chip clears the filter ------------------------------------------
		$("[data-testid=timeline-tool-filter-clear]").click();
		$$("[data-testid=timeline-row]").shouldHave(CollectionCondition.sizeGreaterThanOrEqual(unfilteredTimelineRows));
		$("[data-testid=timeline-tool-filter-clear]").shouldNotBe(visible);
	}

	// ---------------------------------------------------------------------
	// Browser binary resolution: mirrors ConnectFailureIT verbatim.
	// ---------------------------------------------------------------------

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
				List<Path> matches = new ArrayList<>();
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(puppeteerRoot, "mac_arm-*")) {
					for (Path versionDir : stream) {
						matches.add(versionDir);
					}
				}
				catch (IOException ignored) {
					/* best-effort */
				}
				matches.sort(ToolHealthUiIT::compareVersionDirs);
				for (Path versionDir : matches) {
					Path bin = versionDir.resolve(
							"chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
					if (Files.isExecutable(bin)) {
						return bin.toString();
					}
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
		try {
			Process proc = new ProcessBuilder(binaryPath, "--version").redirectErrorStream(true).start();
			String output = new String(proc.getInputStream().readAllBytes()).trim();
			proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			// Chromium can interleave stderr noise (e.g.
			// "[<pid>:MMDD/HHMMSS.mmm:ERROR:...]")
			// with the version line. Match the full four-segment version rather than
			// the first digit run (which may be a pid/timestamp fragment like
			// "231106.") and return its major part.
			java.util.regex.Matcher v = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(output);
			if (v.find()) {
				return v.group(1).substring(0, v.group(1).indexOf('.'));
			}
		}
		catch (Exception ignored) {
			// fall through to null
		}
		return null;
	}

	private static int compareVersionDirs(Path a, Path b) {
		List<Integer> ta = versionTupleFromDirName(a);
		List<Integer> tb = versionTupleFromDirName(b);
		int n = Math.max(ta.size(), tb.size());
		for (int i = 0; i < n; i++) {
			int va = i < ta.size() ? ta.get(i) : Integer.MIN_VALUE;
			int vb = i < tb.size() ? tb.get(i) : Integer.MIN_VALUE;
			int cmp = Integer.compare(vb, va);
			if (cmp != 0) {
				return cmp;
			}
		}
		return 0;
	}

	private static List<Integer> versionTupleFromDirName(Path dir) {
		String name = dir.getFileName().toString();
		int dash = name.indexOf('-');
		if (dash == -1 || dash == name.length() - 1) {
			return List.of(Integer.MIN_VALUE);
		}
		String[] parts = name.substring(dash + 1).split("\\.");
		List<Integer> tuple = new ArrayList<>(parts.length);
		for (String part : parts) {
			try {
				tuple.add(Integer.parseInt(part));
			}
			catch (NumberFormatException e) {
				tuple.add(Integer.MIN_VALUE);
			}
		}
		return tuple;
	}

}
