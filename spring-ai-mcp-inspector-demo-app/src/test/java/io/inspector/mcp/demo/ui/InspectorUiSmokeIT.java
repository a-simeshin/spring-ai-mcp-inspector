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

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
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

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * The one browser check that has to pass on <em>both</em> web stacks: boot the demo, open
 * the upstream inspector page, press Connect, and confirm the sidebar actually filled in
 * the server info.
 *
 * <p>
 * It lives in {@code demo-app} and ships in this module's test-jar, so
 * {@code demo-webmvc} and {@code demo-webflux} both pick it up through Failsafe's
 * {@code dependenciesToScan} and run it once each, against a classpath holding exactly
 * one server. Before the split the equivalent coverage came from a "webflux" row in
 * {@code InspectorUiIT}'s combo matrix — a row that, as it turns out, never ran on Netty
 * at all (see {@link ProxyAppHarness}'s class comment).
 *
 * <p>
 * It is deliberately tiny. The exhaustive browser suite is {@code InspectorUiIT} in
 * {@code demo-webmvc}: seven minutes of tools, resources, prompts, history and theme
 * assertions that exercise vendored JavaScript, not the server stack underneath it.
 * Running that twice would double the slowest job in CI to re-verify the same React
 * bundle. What genuinely differs per stack is whether the proxy can carry an SSE session
 * at all from a real browser, and that is exactly — and only — what this test asserts.
 */
@Epic("MCP Inspector UI")
@Feature("Cross-stack browser smoke")
class InspectorUiSmokeIT {

	private ConfigurableApplicationContext app;

	@BeforeAll
	static void setupBrowser() {
		String binary = detectChromeBinary();
		Assumptions.assumeTrue(binary != null && !binary.isBlank(), "Chrome binary not found; e2e skipped");

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
	 * {@code InspectorUiIT#detectChromeBinary()}: {@code webdriver.chrome.binary}
	 * property → {@code CHROME_BINARY} env → macOS production Chrome → Puppeteer cache →
	 * Linux fallbacks. Returning {@code null} is not a failure — CI images without a
	 * browser skip this test rather than break the build.
	 *
	 * <p>
	 * Unlike the full suite this takes the <em>first</em> Puppeteer-cached Chrome it
	 * finds instead of sorting the version directories newest-first. We pin the driver to
	 * whatever major version that binary reports anyway, so any cached Chrome works, and
	 * a smoke test has no reason to carry a version comparator.
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
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(puppeteerRoot, "mac_arm-*")) {
					for (Path versionDir : stream) {
						Path bin = versionDir.resolve(
								"chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");
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
		return m.find() ? m.group(1) : null;
	}

	/**
	 * Closes the browser and the app, both best-effort, like the neighbouring proxy ITs.
	 */
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

	/** Sidebar wrapper — the {@code bg-card border-r border-border} flex column. */
	private static SelenideElement sidebar() {
		return $(".bg-card.border-r");
	}

	@Test
	@DisplayName("connectsAndShowsServerInfo — the inspector page reaches the connected state on this stack")
	@Story("Connect from a real browser")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo over SSE, opens the upstream inspector page, clicks Connect and asserts the "
			+ "sidebar server-info panel shows the demo name and version — once per web stack.")
	void connect_overSse_showsServerInfoInSidebar() {
		// given
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);

		// when
		open("/mcp-inspector/index.html");
		// Sidebar must finish its first render before we click anything.
		sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();

		// then
		// The Restart/Reconnect button is only mounted when connectionStatus ===
		// "connected", so its presence is an unambiguous signal — unlike a text check,
		// which trips over the "Disconnected" / "Connected" substring overlap.
		$("[data-testid=connect-button]").as("connected state on the " + ProxyAppHarness.stack() + " stack")
			.shouldBe(visible, Duration.ofSeconds(30));
		// Sidebar's serverImplementation panel: gray box with server name + Version:
		// 0.1.0.
		sidebar().as("sidebar server info on the " + ProxyAppHarness.stack() + " stack")
			.shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
		sidebar().as("sidebar server version on the " + ProxyAppHarness.stack() + " stack")
			.shouldHave(text("Version: 0.1.0"));
	}

}
