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
import java.util.Set;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.e2e.E2ePreconditions;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
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
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.ConfigurableApplicationContext;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * Selenide regression for the silent-drop reconnect bug (issue #157, PR #166).
 *
 * <p>
 * When an SSE or streamable-HTTP connection silently drops, the UI stays in the
 * "connected" branch (the SDK does not surface the drop to React state). Clicking
 * Reconnect calls {@code disconnect()} then {@code connect()}. Without the fix, the stale
 * {@code mcp-session-id} header survives in the fetch closure and the server rejects the
 * new handshake with {@code -32001 Request timed out}. With the fix, {@code connect()}
 * clears stale state before the new handshake.
 *
 * <p>
 * This test boots the demo, connects via the browser, clicks the Reconnect button (which
 * exercises the disconnect+connect path that the fix serializes and protects with a
 * generation counter), and asserts the UI reaches the connected state again. It runs on
 * both web stacks (webmvc and webflux) via the test-jar / Failsafe
 * {@code dependenciesToScan} mechanism.
 */
@Epic("MCP Inspector UI")
@Feature("Silent-drop reconnect")
class SilentDropReconnectIT {

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private ConfigurableApplicationContext app;

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

	@Test
	@DisplayName("forcedSilentDrop - proxy session killed without UI disconnect, then Connect recovers")
	@Story("Reconnect after silent drop")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Connects to the MCP server via the UI, then force-closes the proxy session "
			+ "via HTTP DELETE (simulating a silent transport drop without the UI knowing). "
			+ "Clicking Connect again should recover: the stale-session guard in connect() "
			+ "clears the old session state and establishes a fresh handshake. "
			+ "Without the fix, the stale session causes -32001 on every reconnect attempt.")
	void forcedSilentDrop_whenSessionKilled_reconnectsSuccessfully() {
		// given - boot the demo on a random port
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		final int port = ProxyAppHarness.port(app);
		Configuration.baseUrl = "http://localhost:" + port;

		// when - open the inspector and connect
		open("/mcp-inspector/index.html");
		sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();

		// then - connected state is reached
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));

		// when - force-close the proxy session behind the UI's back (silent drop).
		// The UI does not go through the normal disconnect path, so it still holds
		// stale client/transport/session-id state.
		final ProxySessionRegistry registry = app.getBean(ProxySessionRegistry.class);
		final Set<String> sessionIds = registry.sessionIds();
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";
		for (String sid : sessionIds) {
			deleteSession(proxyBase, sid);
		}

		// then - the UI should still show the connect-button (it doesn't know the
		// session was dropped)
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(10));

		// when - click Connect again (this exercises the stale-session guard:
		// connect() must detect the old state, clean up, and establish a fresh
		// handshake)
		$("[data-testid=connect-button]").click();

		// then - the reconnect succeeds and the connected state is re-established
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
		// No error should be surfaced (the reconnect must succeed, not fail).
		$("[role=alert]").shouldNotBe(visible, Duration.ofSeconds(5));
	}

	/** DELETEs a proxy session by id. */
	private static void deleteSession(String proxyBase, String sessionId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp"))
				.timeout(Duration.ofSeconds(10))
				.header("mcp-session-id", sessionId)
				.DELETE()
				.build();
			HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

	/** Sidebar wrapper - the bg-card border-r border-border flex column. */
	private static SelenideElement sidebar() {
		return $(".bg-card.border-r");
	}

	// ------------------------------------------------------------------
	// Chrome binary detection - mirror of InspectorUiSmokeIT helpers.
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
		if (m.find()) {
			return m.group(1);
		}
		try {
			Process proc = new ProcessBuilder(binaryPath, "--version").redirectErrorStream(true).start();
			String output = new String(proc.getInputStream().readAllBytes()).trim();
			proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
			java.util.regex.Matcher v = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(output);
			if (v.find()) {
				return v.group(1);
			}
		}
		catch (Exception ignored) {
			/* fall through to null */
		}
		return null;
	}

}
