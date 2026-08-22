/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.stress;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
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
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.demo.DemoApplication;
import io.inspector.mcp.demo.e2e.E2ePreconditions;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * Selenide UI smoke for big-data scenarios against the vendored upstream React Inspector.
 * Gated <em>only</em> on Chrome availability: the same
 * {@link E2ePreconditions#requireChromeOrSkip(String)} gate as
 * {@link io.inspector.mcp.demo.e2e.InspectorUiIT#setupBrowser()}. On a developer host
 * without a usable browser the test is skipped (not failed); in CI, where the workflow
 * installs Chrome, a missing binary fails instead of silently disabling the suite.
 *
 * <p>
 * Historical note: this class used to be double-gated behind a {@code STRESS_UI=true}
 * opt-in env var, which made the test invisible to everyone in practice. The earlier
 * UI-shape selectors (e.g. {@code aside nav}, {@code form input[type='text']}) also did
 * not match the upstream DOM at all — those buttons / forms don't exist in the React
 * client.
 *
 * <p>
 * The test was rewritten against {@code docs/UPSTREAM_DOM_MAP.md}: the
 * {@code [data-testid=connect-button]} mounts only after the SDK reports a successful
 * {@code initialize}; tool inputs are keyed by their property names ({@code #sizeKb});
 * the Run button is plain text ({@code "Run Tool"}).
 *
 * <p>
 * Connect path: webmvc + STREAMABLE. This is the simplest combination with the full tool
 * surface available. The previous {@code STREAMABLE} stall was the same bug fixed under
 * T26, so we can hit the {@code /mcp} proxy directly without falling back to SSE.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Epic("Stress & Scale")
@Feature("Inspector big-data UI")
class UiBigDataIT {

	private ConfigurableApplicationContext app;

	// ------------------------------------------------------------------
	// Browser setup / teardown.
	// ------------------------------------------------------------------

	@BeforeAll
	void setupBrowser() {
		// Single gate: Chrome present. The earlier STRESS_UI=true env opt-in is gone
		// (see class javadoc): it kept the test perpetually skipped on dev laptops, so
		// the suite never benefited from it. A dev host without a browser still skips;
		// CI, which installs one, fails instead of quietly running nothing.
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
		Configuration.timeout = 30_000;
		Configuration.pageLoadTimeout = 60_000;

		ChromeOptions options = new ChromeOptions();
		options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--remote-allow-origins=*");
		Configuration.browserBinary = binary;
		options.setBinary(binary);
		Configuration.browserCapabilities = options;
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

	/** Boots the demo on webmvc + STREAMABLE — full tool surface, /mcp proxy path. */
	private void startApp() {
		String[] args = new String[] { "--server.port=0", "--spring.ai.mcp.server.protocol=STREAMABLE",
				"--spring.ai.mcp.inspector.auth-enabled=false", };
		app = new SpringApplicationBuilder(DemoApplication.class).run(args);
		int port = ((WebServerApplicationContext) app).getWebServer().getPort();
		Configuration.baseUrl = "http://localhost:" + port;
	}

	// ------------------------------------------------------------------
	// Shared DOM helpers — copy of the InspectorUiIT helpers (kept tiny here
	// to avoid a cross-test-class dependency that would force the whole
	// suite into one runtime).
	// ------------------------------------------------------------------

	private static SelenideElement sidebar() {
		return $(".bg-card.border-r");
	}

	private static SelenideElement connectButton() {
		return sidebar().$(byText("Connect"));
	}

	private static SelenideElement activePanel() {
		return $("[role=tabpanel][data-state=active]");
	}

	private static void clickTab(String value) {
		$("[role=tablist]").shouldBe(visible, Duration.ofSeconds(15));
		$("[role=tab][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(10)).click();
		$("[role=tab][data-state=active][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
	}

	private static void openAndConnect() {
		open("/mcp-inspector/index.html");
		connectButton().shouldBe(visible, Duration.ofSeconds(15));
		connectButton().click();
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
	}

	// ------------------------------------------------------------------
	// Tests.
	// ------------------------------------------------------------------

	@Test
	@Story("Large output rendering")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Verifies running largeOutput in the UI does not freeze the inspector — tab switching stays responsive")
	@DisplayName("largeOutput run does not freeze the UI")
	void largeOutput_whenRunInUi_doesNotFreezeUi() {
		// given
		startApp();
		openAndConnect();

		// when
		clickTab("tools");
		SelenideElement listTools = activePanel().$(byText("List Tools"));
		if (listTools.exists() && listTools.isEnabled()) {
			listTools.click();
		}

		// Pick largeOutput by visible name. The row is a div.cursor-pointer inside the
		// active tab panel — ListPane row shape (UPSTREAM_DOM_MAP.md §16).
		activePanel().$$(".cursor-pointer")
			.findBy(text("largeOutput"))
			.shouldBe(visible, Duration.ofSeconds(15))
			.click();

		// sizeKb is the integer prop on largeOutput; renders as <Input type=number
		// id="sizeKb">.
		SelenideElement sizeKb = $("#sizeKb");
		if (sizeKb.exists()) {
			sizeKb.setValue("128");
		}
		activePanel().$(byText("Run Tool")).shouldBe(visible).click();

		// Wait for the run to complete (the button text reverts from "Running..." to
		// "Run Tool"); 60 s is generous for a 128 KiB payload.
		activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(60));

		// then
		// UI not frozen: switching to Ping responds within 5 s.
		clickTab("ping");
		activePanel().$(byText("Ping Server")).shouldBe(visible, Duration.ofSeconds(5));
	}

	@Test
	@Story("Resource fixtures rendering")
	@Severity(SeverityLevel.NORMAL)
	@Description("Verifies the resources tab renders the demo static fixtures and templated items at scale")
	@DisplayName("resources tab renders demo fixtures")
	void resourcesTab_whenOpened_rendersDemoFixtures() {
		// given
		startApp();
		openAndConnect();

		// when
		clickTab("resources");

		// Trigger resources/list — button is disabled when the list is already populated
		// (which it isn't on first visit). UPSTREAM_DOM_MAP.md §4.1.
		SelenideElement listResources = activePanel().$(byText("List Resources"));
		if (listResources.exists() && listResources.isEnabled()) {
			listResources.click();
		}
		SelenideElement listTemplates = activePanel().$(byText("List Templates"));
		if (listTemplates.exists() && listTemplates.isEnabled()) {
			listTemplates.click();
		}

		// then
		// The 5 static resources have stable names — demo-config is the most "checkable"
		// (JSON resource name in DEMO_CAPABILITIES.md).
		activePanel().shouldHave(text("demo-config"), Duration.ofSeconds(15));
		// Templates section shows the templated item URI by name.
		activePanel().shouldHave(text("demo-item"), Duration.ofSeconds(15));
		// At least 5 rows visible in the resources pane.
		activePanel().$$(".cursor-pointer")
			.shouldHave(CollectionCondition.sizeGreaterThanOrEqual(5), Duration.ofSeconds(15));
	}

	// ------------------------------------------------------------------
	// Chrome binary detection — mirror of InspectorUiIT helpers.
	// ------------------------------------------------------------------

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
					for (Path p : stream) {
						matches.add(p);
					}
				}
				catch (IOException ignored) {
					/* best-effort */
				}
				matches.sort(UiBigDataIT::compareVersionDirs);
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
		return m.find() ? m.group(1) : null;
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
		if (dash < 0 || dash == name.length() - 1) {
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
