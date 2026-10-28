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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.support.E2ePreconditions;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.inspector.mcp.core.proxy.ProxySessionRegistry;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
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
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selenide regression for the silent-drop reconnect bug (issue #157, PR #166).
 *
 * <p>
 * When a streamable-HTTP connection silently drops, the UI stays in the "connected"
 * branch (the SDK does not surface the drop to React state). The next connect must start
 * from a clean slate: the pre-PR base code reused the stale {@code mcp-session-id}
 * closure value as a request header in the direct branch, so every reconnect POST carried
 * the id of a session the server had already forgotten and failed with -32001 forever.
 *
 * <p>
 * Falsifiability: the direct-mode test recreates exactly that base scenario (connect
 * direct, kill the server session behind the UI's back, reconnect) and asserts a fresh
 * handshake with a NEW session id, so restoring the stale header reuse (the removed
 * {@code requestHeaders["mcp-session-id"]} line) turns it red. The proxy-mode test keeps
 * the proxy path covered with positive assertions only (fresh registry session and
 * connected UI); it is green on the base by design because the proxy transport never sent
 * the stale header - the mutation carrier for the fix is the direct test.
 *
 * <p>
 * Both tests run on both web stacks (webmvc and webflux) via the test-jar / Failsafe
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

	// ------------------------------------------------------------------
	// Direct connection: the falsifiable core of this regression.
	// ------------------------------------------------------------------

	@Test
	@DisplayName("direct silent drop - server session killed, Reconnect handshakes with a fresh session id")
	@Story("Reconnect after silent drop")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Connects directly to the demo MCP endpoint, records the session id the server "
			+ "assigned (via a fetch wrapper installed before the first connect), kills that session "
			+ "server-side with DELETE (simulating a silent drop the UI cannot see), then clicks "
			+ "Reconnect. The reconnect must complete a fresh handshake with a NEW session id and "
			+ "return the UI to the connected state. The pre-fix base reused the stale "
			+ "mcp-session-id header here and every reconnect failed; restoring that reuse fails " + "this test.")
	void directSilentDrop_whenSessionKilled_reconnectsWithFreshSessionId() {
		// given - boot the demo on a random port
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		final int port = ProxyAppHarness.port(app);
		Configuration.baseUrl = "http://localhost:" + port;

		// when - open the inspector
		open("/mcp-inspector/index.html");

		// Wrap window.fetch before any connect so every mcp-session-id the
		// server hands out is recorded; this is how the test learns the id of
		// the session to kill (the UI keeps it only in React state).
		Selenide.executeJavaScript("""
				window.__seenSessionIds = [];
				window.__origFetch = window.fetch;
				window.fetch = function() {
				  return window.__origFetch.apply(window, arguments).then(function(resp) {
				    try {
				      var sid = resp.headers.get('mcp-session-id');
				      if (sid && window.__seenSessionIds.indexOf(sid) === -1) {
				        window.__seenSessionIds.push(sid);
				      }
				    } catch (e) { /* headers unavailable on opaque responses */ }
				    return resp;
				  });
				};
				""");

		// and - switch to a Direct connection (the base bug lived in the
		// direct branch's request headers)
		switchToDirectConnection();

		// and - connect
		sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();

		// then - connected, and the server assigned a session id
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
		final String firstSessionId = awaitSessionId();

		// when - kill the server-side session behind the UI's back
		final String directBase = "http://127.0.0.1:" + port + "/mcp";
		deleteSession(directBase, firstSessionId);
		assertSessionRejected(directBase, firstSessionId);

		// then - the UI still shows the connected branch (it does not know)
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(10));

		// when - click Reconnect
		$("[data-testid=connect-button]").click();

		// then - a fresh handshake with a NEW session id completes and the UI
		// is connected again. No fallback: if the reconnect wedges with the
		// stale header (the base bug), the new id never appears and this
		// fails.
		final String secondSessionId = Awaitility.await("fresh session id after silent drop")
			.pollInSameThread()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(250))
			.until(() -> latestSessionIdAfter(firstSessionId), sid -> sid != null);
		assertNotEquals(firstSessionId, secondSessionId, "Reconnect must handshake with a fresh session id");

		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(10));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
		// The reconnect recovered; the error alert must not be showing.
		assertFalse($("[data-testid=connect-error-alert]").isDisplayed(),
				"Recovered reconnect must not leave an error alert open");
	}

	// ------------------------------------------------------------------
	// Proxy connection: positive regression for the proxy path.
	// ------------------------------------------------------------------

	@Test
	@DisplayName("proxy silent drop - proxy session killed without UI disconnect, Connect recovers")
	@Story("Reconnect after silent drop")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Connects via the proxy, deletes the proxy session server-side (registry-backed "
			+ "DELETE), clicks Connect again and asserts a fresh proxy session is registered and the "
			+ "UI returns to connected. Positive assertions only: an error alert appearing instead "
			+ "fails the test.")
	void proxySilentDrop_whenSessionKilled_reconnectsSuccessfully() {
		// given - boot the demo on a random port
		app = ProxyAppHarness.start("STREAMABLE", false, null);
		final int port = ProxyAppHarness.port(app);
		Configuration.baseUrl = "http://localhost:" + port;

		// when - open the inspector and connect (default Via Proxy)
		open("/mcp-inspector/index.html");
		sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();

		// then - connected state is reached
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));

		// when - force-close the proxy session behind the UI's back (silent drop).
		final ProxySessionRegistry registry = app.getBean(ProxySessionRegistry.class);
		final Set<String> sessionIds = new HashSet<>(registry.sessionIds());
		assertFalse(sessionIds.isEmpty(), "Must have at least one active session to delete");
		final String proxyBase = "http://127.0.0.1:" + port + "/mcp-inspector-api";
		for (String sid : sessionIds) {
			deleteSession(proxyBase + "/mcp", sid);
		}
		// Verify the session was actually removed from the registry.
		assertFalse(registry.sessionIds().containsAll(sessionIds),
				"Session should be removed from registry after DELETE");

		// then - the UI should still show the connect-button (it doesn't know the
		// session was dropped)
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(10));

		// when - click Connect again (exercises the stale-session cleanup and
		// the serialized disconnect-then-connect in the Reconnect handler)
		$("[data-testid=connect-button]").click();

		// then - a fresh handshake registers a NEW proxy session (positive
		// only: wedging into an error state fails here) and the UI reconnects.
		Awaitility.await("fresh handshake")
			.pollInSameThread()
			.atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> {
				Set<String> currentIds = new HashSet<>(registry.sessionIds());
				currentIds.removeAll(sessionIds);
				assertFalse(currentIds.isEmpty(), "Expected a new session ID from fresh handshake");
			});
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(10));
		sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
		assertFalse($("[data-testid=connect-error-alert]").isDisplayed(),
				"Recovered reconnect must not leave an error alert open");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Radix select: open the Connection Type dropdown and pick Direct. */
	private static void switchToDirectConnection() {
		$("#connection-type-select").shouldBe(visible, Duration.ofSeconds(10)).click();
		// Radix renders the options in a portal as div[role=option]; pick the
		// one whose text is Direct (the first option is Via Proxy).
		$$("[role=option]").findBy(text("Direct")).shouldBe(visible, Duration.ofSeconds(5)).click();
	}

	/**
	 * Waits until the wrapped fetch observed at least one mcp-session-id and returns the
	 * first (the initial connection's id).
	 */
	private static String awaitSessionId() {
		return Awaitility.await("initial session id")
			.pollInSameThread()
			.atMost(Duration.ofSeconds(15))
			.pollInterval(Duration.ofMillis(250))
			.until(() -> seenSessionIds(), ids -> !ids.isEmpty())
			.get(0);
	}

	/** First session id observed after the given one, or null while absent. */
	private static String latestSessionIdAfter(String before) {
		List<String> ids = seenSessionIds();
		int idx = ids.indexOf(before);
		return (idx >= 0 && idx + 1 < ids.size()) ? ids.get(idx + 1) : null;
	}

	@SuppressWarnings("unchecked")
	private static List<String> seenSessionIds() {
		Object value = Selenide.executeJavaScript("return window.__seenSessionIds || [];");
		return value instanceof List ? new ArrayList<>((List<String>) value) : List.of();
	}

	/** DELETEs a session by id and asserts the server accepted it. */
	private static void deleteSession(String base, String sessionId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base))
				.timeout(Duration.ofSeconds(10))
				.header("mcp-session-id", sessionId)
				.DELETE()
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			assertTrue(status == 200 || status == 204 || status == 404 || status == 405 || status == 410,
					"DELETE for session " + sessionId + " should be accepted, got " + status);
		}
		catch (Exception e) {
			throw new RuntimeException("DELETE failed for session " + sessionId, e);
		}
	}

	/**
	 * Proves the session is really gone server-side: a follow-up initialize POST carrying
	 * the killed id must not be accepted as that session.
	 */
	private static void assertSessionRejected(String base, String sessionId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base))
				.timeout(Duration.ofSeconds(10))
				.header("mcp-session-id", sessionId)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers
					.ofString("{\"jsonrpc\":\"2.0\",\"id\":999999,\"method\":\"tools/list\"}"))
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			assertTrue(response.statusCode() >= 400, "A request for the killed session " + sessionId
					+ " must fail, got " + response.statusCode() + ": " + response.body());
		}
		catch (Exception e) {
			throw new RuntimeException("Session rejection probe failed for " + sessionId, e);
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
