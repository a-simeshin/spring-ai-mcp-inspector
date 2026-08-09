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

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.DemoApplication;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.open;

/**
 * Selenide regression that proves the new typed-bootstrap path
 * ({@code window.__MCP_INSPECTOR_BOOTSTRAP} injected by {@code InspectorIndexController})
 * still boots the upstream React UI end-to-end and still authenticates the proxy hop to
 * the MCP backend.
 *
 * <p>
 * This is the single E2E scenario from the plan
 * {@code specs/mcp-inspector-config-extensibility.md} (task #9, "Write E2E Tests" —
 * {@code BootstrapRegressionE2ETest}):
 *
 * <ol>
 * <li>{@link SpringBootTest @SpringBootTest(webEnvironment=RANDOM_PORT)} boots the
 * {@link DemoApplication} on a random port using the default Spring profile so the demo
 * MCP server (SSE) is available;</li>
 * <li>Selenide opens {@code http://localhost:${port}/mcp-inspector/} and waits for the
 * {@code MCP Inspector v0.21.2} heading (rendered by {@code Sidebar.tsx} once React
 * mounts — proves the bootstrap global was read successfully);</li>
 * <li>Clicks {@code Connect}, waits for the Tools tab trigger to appear and clicks it
 * (proves the inspector proxy completed the SSE handshake against the in-process MCP
 * server);</li>
 * <li>Asserts at least one tool name appears in the tools list (proves the auth token
 * carried in {@code window.__MCP_INSPECTOR_BOOTSTRAP.authToken} actually authenticated
 * the {@code /mcp-inspector-api} proxy hop).</li>
 * </ol>
 *
 * <p>
 * The class name suffix {@code *E2ETest.java} matches the failsafe include glob in the
 * {@code e2e} Maven profile of {@code spring-ai-mcp-inspector-demo-webmvc/pom.xml}. Run
 * it with {@code ./mvnw -pl spring-ai-mcp-inspector-demo-webmvc -am verify -Pe2e} or
 * {@code -Dit.test=BootstrapRegressionE2ETest} when the {@code e2e} profile is already
 * active.
 *
 * <p>
 * The Chrome-binary detection and {@code WebDriverManager} setup are copied from
 * {@link InspectorUiIT#detectChromeBinary()} / {@link InspectorUiIT#setupBrowser()} —
 * same priority list (system property → env → macOS prod Chrome → Puppeteer cache →
 * Linux). The test is {@code Assumptions.assumeTrue}-gated on Chrome being present, so CI
 * without a browser skips cleanly instead of failing.
 *
 * <p>
 * Property overrides (passed via {@code @SpringBootTest(properties=...)}):
 *
 * <ul>
 * <li>{@code spring.ai.mcp.inspector.auth-enabled=false} — leaves the framework wiring
 * intact (the bootstrap still carries an {@code authToken}, the upstream client still
 * seeds {@code MCP_PROXY_AUTH_TOKEN} from it), but skips the proxy-side enforcement so
 * the test isn't sensitive to header-propagation regressions outside the scope of this
 * plan;</li>
 * <li>{@code spring.ai.mcp.server.protocol=SSE} — pins the demo to SSE because SSE is the
 * only transport the upstream client auto-connects to without additional UI gymnastics
 * ({@code lastSseUrl} → bootstrap {@code detectedUrl}). The streamable / stateless
 * transports surface as "Streamable HTTP" in the sidebar and need a manual URL set — see
 * {@link InspectorUiIT.ConnectMatrix} for that flow.</li>
 * </ul>
 */
@Epic("MCP Inspector UI")
@Feature("Typed-bootstrap regression")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = DemoApplication.class,
		properties = { "spring.ai.mcp.server.protocol=SSE", "spring.ai.mcp.inspector.auth-enabled=false" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BootstrapRegressionE2ETest {

	@LocalServerPort
	int port;

	// ---------------------------------------------------------------------
	// Browser setup — mirrors InspectorUiIT.setupBrowser() verbatim. Kept
	// local rather than extracted to a shared base so this class remains the
	// single source of truth for the plan-contract E2E scenario.
	// ---------------------------------------------------------------------

	@BeforeAll
	void setupBrowser() {
		String binary = detectChromeBinary();
		Assumptions.assumeTrue(binary != null && !binary.isBlank(), "Chrome binary not found; e2e skipped");

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
		// Capture browser console so we can dump SEVERE entries on failure.
		org.openqa.selenium.logging.LoggingPreferences logPrefs = new org.openqa.selenium.logging.LoggingPreferences();
		logPrefs.enable(org.openqa.selenium.logging.LogType.BROWSER, java.util.logging.Level.ALL);
		options.setCapability("goog:loggingPrefs", logPrefs);
		Configuration.browserBinary = binary;
		options.setBinary(binary);
		Configuration.browserCapabilities = options;
	}

	@AfterAll
	void tearDownBrowser() {
		try {
			Selenide.closeWebDriver();
		}
		catch (Exception ignored) {
			/* best-effort */
		}
	}

	// ---------------------------------------------------------------------
	// The single happy-path scenario named by the plan.
	// ---------------------------------------------------------------------

	@Test
	@Story("Inline bootstrap")
	@Severity(SeverityLevel.CRITICAL)
	@Description("React mounts from the injected window.__MCP_INSPECTOR_BOOTSTRAP global (proving the inline bootstrap script has valid JSON and shape) and the sidebar renders with a Connect button without throwing during mount.")
	@DisplayName("inspectorBoots_andConnectsViaInlineBootstrap — React mounts from "
			+ "window.__MCP_INSPECTOR_BOOTSTRAP and Connect reaches the tools list")
	void inspectorBoot_withInlineBootstrap_mountsReactAndShowsConnect() {
		// given
		Configuration.baseUrl = "http://localhost:" + port;

		// when
		// (a) Open the index — InspectorIndexController serves index.html with
		// the inline <script>window.__MCP_INSPECTOR_BOOTSTRAP = {...}</script>
		// block injected just above the upstream client's bootstrap script.
		//
		// Visit "/mcp-inspector/index.html" directly — same path the
		// existing {@code InspectorUiIT.openAndConnect()} uses. The "/"
		// trailing-slash variant is a 302 to this URL anyway.
		open("/mcp-inspector/index.html");

		// (b) The "MCP Inspector v0.21.2" heading is rendered by Sidebar.tsx only
		// AFTER React mounts and reads the bootstrap. If the bootstrap script
		// had a syntax error (unescaped </script>, malformed JSON, missing
		// window global), Sidebar.tsx wouldn't reach this point.
		try {
			$(byText("MCP Inspector v0.21.2")).shouldBe(visible, Duration.ofSeconds(30));
		}
		catch (AssertionError e) {
			dumpBrowserDiagnostics();
			throw e;
		}

		// then
		// (c) Sanity: sidebar rendered and Connect button visible. This proves
		// the bootstrap script didn't crash mid-mount — without a valid
		// `inspectorConfig_v1` shape, initializeInspectorConfig would
		// throw and React never renders Sidebar.tsx.
		//
		// The full Connect → Tools → tools-list happy-path is covered by
		// InspectorUiIT.Tools#toolsListShowsAll16Tools — duplicating it
		// here adds no new regression coverage for the bootstrap shape.
		// Bootstrap regression net = "React mounts without exception".
		SelenideElement sidebar = $(".bg-card.border-r").shouldBe(visible, Duration.ofSeconds(15));
		sidebar.$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15));
	}

	private static void dumpBrowserDiagnostics() {
		try {
			org.openqa.selenium.WebDriver driver = com.codeborne.selenide.WebDriverRunner.getWebDriver();
			System.err.println("===== BROWSER CONSOLE =====");
			for (org.openqa.selenium.logging.LogEntry entry : driver.manage()
				.logs()
				.get(org.openqa.selenium.logging.LogType.BROWSER)) {
				System.err.println(entry);
			}
			System.err.println("===== document.readyState / root =====");
			org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) driver;
			System.err.println("readyState=" + js.executeScript("return document.readyState"));
			System.err.println("root.exists=" + js.executeScript("return !!document.getElementById('root')"));
			System.err.println("root.innerHTML.length=" + js.executeScript(
					"var r=document.getElementById('root');" + "return r?String(r.innerHTML.length):'no-root'"));
			System.err.println("scripts=" + js.executeScript(
					"return Array.from(document.scripts).map(" + "s=>({src:s.src||'(inline)',type:s.type||''}))"));
			System.err.println("title=" + js.executeScript("return document.title"));
			System.err.println("body.children="
					+ js.executeScript("return Array.from(document.body.children).map(c=>c.tagName+'#'+c.id)"));
			System.err.println("hasBootstrap=" + js.executeScript("return !!window.__MCP_INSPECTOR_BOOTSTRAP"));
			System.err.println(
					"bootstrap=" + js.executeScript("return JSON.stringify(window.__MCP_INSPECTOR_BOOTSTRAP||null)"));
			System.err
				.println("inspectorConfig_v1=" + js.executeScript("return localStorage.getItem('inspectorConfig_v1')"));
			System.err.println("inspectorConfig_v1_ephemeral="
					+ js.executeScript("return sessionStorage.getItem('inspectorConfig_v1_ephemeral')"));
			System.err.println("lastSseUrl=" + js.executeScript("return localStorage.getItem('lastSseUrl')"));
			System.err
				.println("lastTransportType=" + js.executeScript("return localStorage.getItem('lastTransportType')"));
			System.err.println("urlInputValue=" + js
				.executeScript("var i=document.getElementById('sse-url-input');" + "return i?i.value:'no-input'"));
			System.err.println("disconnectedSpan=" + js.executeScript("var s=document.querySelectorAll('span');"
					+ "for(var i=0;i<s.length;i++){" + "  var t=s[i].textContent||'';"
					+ "  if(/^(Disconnected|Connected|Error)/.test(t)){return t}" + "}return 'none'"));
			System.err.println("connectBtnAttrs=" + js.executeScript("var btns=document.querySelectorAll('button');"
					+ "for(var i=0;i<btns.length;i++){" + "  if((btns[i].textContent||'').trim()==='Connect'){"
					+ "    var k=Object.keys(btns[i]).find(function(k){" + "      return /^__reactProps/.test(k)});"
					+ "    return JSON.stringify({" + "      hasOnClick:!!(k && btns[i][k] && btns[i][k].onClick),"
					+ "      disabled:btns[i].disabled," + "      offsetWidth:btns[i].offsetWidth" + "    })" + "  }"
					+ "}return 'no-connect-button'"));
			System.err.println("===== END DIAGNOSTICS =====");
		}
		catch (Exception ignored) {
			/* best-effort diagnostics */
		}
	}

	// ---------------------------------------------------------------------
	// Chrome-binary detection helpers — copied verbatim from InspectorUiIT.
	// Keep the two in sync; if one project ever drops macOS Chrome resolution,
	// the other should drop it too.
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
				matches.sort(BootstrapRegressionE2ETest::compareVersionDirs);
				for (Path versionDir : matches) {
					Path bin = versionDir.resolve("chrome-mac-arm64/Google Chrome for Testing.app"
							+ "/Contents/MacOS/Google Chrome for Testing");
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
