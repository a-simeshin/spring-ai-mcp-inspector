/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.demo.DemoApplication;

import static com.codeborne.selenide.Condition.exactValue;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;

/**
 * End-to-end Selenide test for the transport-switch hint (issue #250, upstream
 * spring-projects/spring-ai#7207): pointing the inspector at an SSE-only upstream with
 * the Streamable HTTP transport must surface the strong S1 hint verbatim inside the
 * connect-failure alert, and clicking "Switch to SSE" must flip the transport selector to
 * SSE and reconnect against the same URL.
 *
 * <p>
 * The upstream is the shared {@link E2eSseMcpStub} (JDK {@code HttpServer}): it serves
 * the SSE handshake at {@code GET /sse} and answers {@code POST /sse} (and every other
 * non-SSE path) with a non-2xx that the mcp-core streamable transport cannot map to a
 * specific status, which is exactly the bucket the SSE probe exists for. The probe GETs
 * {@code /sse}, sees {@code text/event-stream}, and the proxy attaches the
 * {@code sse_only} hint to the {@code MCP_CONNECT_FAILED} payload.
 *
 * <p>
 * Browser / boot helpers mirror {@link ConnectFailureIT} verbatim: version-pinned
 * WebDriverManager, {@code E2ePreconditions#requireChromeOrSkip} (skip locally, hard
 * failure in CI), command-line {@code --server.port=0} so the demo never collides with
 * {@code 8080}. The timeline is enabled by the demo's own {@code application.yml}, so the
 * recorded {@code MCP_CONNECT_FAILED} event is queryable through the timeline tab the
 * browser already renders.
 */
@Epic("MCP Inspector UI")
@Feature("Connect hint E2E")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectHintSseOnlyIT {

	/**
	 * The verbatim strong-hint copy rendered inside the alert (spec section 6). Asserted
	 * as a whole string on purpose: wording drift must fail this test, not an eyeball
	 * review.
	 */
	private static final String STRONG_HINT_TEXT = "This server appears to serve the legacy SSE transport only. For Spring AI servers set `spring.ai.mcp.server.protocol=STREAMABLE`, or connect with the SSE transport.";

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown - mirrors ConnectFailureIT.
	// ---------------------------------------------------------------------

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

	@AfterEach
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
	 * Boots {@link DemoApplication} on a random port with authentication disabled. The
	 * demo's own MCP server protocol is irrelevant: the inspector connects to the SSE
	 * stub through the proxy, so only the servlet stack and the timeline seed matter.
	 */
	private void startApp() {
		String[] args = new String[] { "--server.port=0", "--spring.ai.mcp.server.protocol=SSE",
				"--spring.ai.mcp.inspector.auth-enabled=false", };
		app = new SpringApplicationBuilder(DemoApplication.class).run(args);

		int port = ((WebServerApplicationContext) app).getWebServer().getPort();
		Configuration.baseUrl = "http://localhost:" + port;
	}

	/** Sidebar wrapper - the {@code bg-card border-r border-border} flex column. */
	private static SelenideElement sidebar() {
		return $("[data-testid=sidebar]");
	}

	/** Sidebar Connect button (first-time; has no testid - match by visible text). */
	private static SelenideElement connectButton() {
		return sidebar().$(byText("Connect"));
	}

	/**
	 * The connect-failure alert mounted by the Sidebar, scoped by the
	 * {@code [data-testid=retry-connect-button]} anchor instead of {@code role=alert}:
	 * the auth-profiles panel renders alert roles of its own when expanded.
	 */
	private static SelenideElement alert() {
		return $("[data-testid=retry-connect-button]").parent();
	}

	/**
	 * Sets the value of a React-controlled {@code <input>} reliably by invoking the
	 * native HTMLInputElement setter and then dispatching a synthetic {@code input} event
	 * - same helper as {@code ConnectFailureIT#setReactInputValue}.
	 */
	private static void setReactInputValue(String cssSelector, String value) {
		org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) com.codeborne.selenide.WebDriverRunner
			.getWebDriver();
		js.executeScript("var el = document.querySelector(arguments[0]);" + "if (!el) { return; }"
				+ "var setter = Object.getOwnPropertyDescriptor(" + "  window.HTMLInputElement.prototype, 'value').set;"
				+ "setter.call(el, arguments[1]);" + "el.dispatchEvent(new Event('input', { bubbles: true }));"
				+ "el.dispatchEvent(new Event('change', { bubbles: true }));", cssSelector, value);
	}

	// =====================================================================
	// The transport-switch hint scenario (issue #250).
	// =====================================================================

	@Test
	@Story("Connect hint")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Streamable connect to an SSE-only stub shows the verbatim S1 hint; clicking Switch to SSE reconnects over SSE; the timeline records the failed connect.")
	@DisplayName("connectToSseOnlyStub - verbatim hint, switch button reconnects, timeline entry")
	void connectToSseOnlyStub_showsHint_switchesToSse_andRecordsTimeline() throws IOException {
		// The stub serves SSE at GET /sse; POST /sse (and any other path than /sse and
		// /message) fails, which is the streamable-connect failure the hint exists for.
		try (final E2eSseMcpStub stub = new E2eSseMcpStub("{\"tools\":{}}")) {
			startApp();
			open("/mcp-inspector/index.html");

			// given - Streamable HTTP transport pointed at the stub's SSE URL.
			connectButton().shouldBe(visible, Duration.ofSeconds(15));
			$("#transport-type-select").shouldBe(visible).click();
			$("[data-testid=select-option-streamable-http]").shouldBe(visible).click();
			$("#sse-url-input").shouldBe(visible);
			setReactInputValue("#sse-url-input", stub.sseUrl());

			// when - the connect POST fails upstream, the probe proves /sse alive, and
			// the proxy answers 502 MCP_CONNECT_FAILED with the sse_only hint.
			connectButton().click();

			// then - the alert renders the strong hint verbatim, with the upstream
			// refs links and the Switch to SSE button.
			alert().shouldBe(visible, Duration.ofSeconds(30));
			alert().shouldHave(text("Failed to connect to the MCP server"));
			alert().shouldHave(text(STRONG_HINT_TEXT));
			$("[data-testid=connect-hint-strong]").shouldBe(visible).shouldHave(text(STRONG_HINT_TEXT));
			$("[data-testid=switch-to-sse-button]").shouldBe(visible).shouldHave(text("Switch to SSE"));

			// when - Switch to SSE flips the transport selector to SSE and reconnects
			// with the same URL; the proxy opens its SSE branch against the stub and
			// the handshake completes.
			$("[data-testid=switch-to-sse-button]").click();

			// then - the connected branch mounts the Restart/Reconnect button; the URL
			// field still carries the exact URL the user typed (not rewritten).
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
			sidebar().$(byText("Disconnect")).shouldBe(visible);
			$("#sse-url-input").shouldHave(exactValue(stub.sseUrl()));

			// and - the timeline records the failed connect attempt with the reason
			// and the hint text, no button, plain text only.
			$("[data-testid=tab-trigger-timeline]").shouldBe(visible).click();
			$("[data-testid=tab-content-timeline]").shouldBe(visible, Duration.ofSeconds(10));
			$$("[data-testid=timeline-row]").findBy(text("MCP connect failed"))
				.shouldBe(visible, Duration.ofSeconds(15))
				.shouldHave(text(STRONG_HINT_TEXT));
		}
	}

	// ---------------------------------------------------------------------
	// Chrome binary detection - mirrors ConnectFailureIT verbatim.
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
					for (Path p : stream) {
						matches.add(p);
					}
				}
				catch (IOException ignored) {
					/* best-effort */
				}
				matches.sort(ConnectHintSseOnlyIT::compareVersionDirs);
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
			// Chromium can interleave stderr noise with the version line; match the
			// full four-segment version and return its major part.
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
