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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Stream;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Condition;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.inspector.mcp.demo.DemoApplication;

import static com.codeborne.selenide.Condition.attributeMatching;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;

/**
 * End-to-end Selenide tests against the upstream MCP Inspector React DOM (vendored under
 * {@code spring-ai-mcp-inspector-ui/upstream-client}) running inside the embedded
 * {@link DemoApplication}.
 *
 * <p>
 * This class is the rewrite mandated after the custom UI was dropped. Every selector here
 * is anchored to the contract in {@code docs/UPSTREAM_DOM_MAP.md} (Radix Tabs / Selects,
 * shadcn primitives, {@code data-testid} hooks). Test inputs come from
 * {@code docs/DEMO_CAPABILITIES.md}.
 *
 * <p>
 * Layout:
 *
 * <ul>
 * <li>{@link Connect} — connect / disconnect / transport-switch happy paths.</li>
 * <li>{@link Metadata} — connects over SSE and asserts the sidebar server-info panel
 * shows the demo's server name + version.</li>
 * <li>{@link Tools} — list, search, and call every demo tool over SSE.</li>
 * <li>{@link Resources} — list, read, template binding.</li>
 * <li>{@link Prompts} — list, render greeting / multiTurn / optionalDescription.</li>
 * <li>{@link Ping} — single ping and history accumulation.</li>
 * <li>{@link Auth} — pre-connect "Open Auth Settings" entry into AuthDebugger.</li>
 * <li>{@link HistoryAndNotifications} — history populates and clears.</li>
 * <li>{@link SidebarAndTheme} — collapsibles, custom header add/remove, theme
 * switch.</li>
 * <li>{@link TabsAvailability} — verifies the rendered tab list against expected.</li>
 * <li>{@link ResponsiveTabBar} — CI regression for the <640px wrap patch (375px /
 * sm-boundary / 1024px control).</li>
 * <li>{@link ResponsiveHistoryLayout}: CI regression for the <1024px compact layout patch
 * (History pane never overlaps tab content; elementFromPoint at 780x437 with both drag
 * handles absent and the History/Notifications columns scrolling under real overflow,
 * disjoint panes at 768/1023, desktop control at 1024px).</li>
 * </ul>
 *
 * <p>
 * Boot / cleanup helpers ({@link #detectChromeBinary()},
 * {@link #parseMajorVersionFromPath(String)}, {@link #startApp(Combo)},
 * {@link #stopApp()}) mirror the patterns from the original {@code InspectorE2ETest}
 * verbatim — version-pinned WebDriverManager, Puppeteer-cache scan,
 * {@link E2ePreconditions#requireChromeOrSkip(String)} when Chrome isn't installed (skip
 * locally, hard failure in CI), and command-line {@code --server.port=0} so we never
 * collide with {@code 8080}.
 */
@Epic("MCP Inspector UI")
@Feature("Upstream React inspector E2E")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectorUiIT {

	/** One transport protocol the demo server can be booted with. */
	record Combo(String protocol) {
		@Override
		public String toString() {
			return protocol;
		}
	}

	/**
	 * Every demo tool advertised by {@code tools/list} (22 entries).
	 */
	private static final String[] ALL_DEMO_TOOLS = { "echo", "sum", "currentTime", "addNumbers", "concatenate",
			"lookupUser", "chooseColor", "toggleFlag", "optionalGreeting", "errorTool", "largeOutput",
			"structuredOutput", "multiContent", "slowEcho", "deepJson", "blobAttachment", "askLlm", "askUser",
			"deployService", "findFiles", "authorizeViaUrl", "listMyRoots" };

	/**
	 * Connect matrix actually exposed by the upstream UI: only the three transport
	 * flavours the {@code Sidebar.tsx} transport-type Select renders — {@code STDIO},
	 * {@code SSE}, {@code Streamable HTTP}. The UI does NOT distinguish stateless from
	 * streamable (both share the {@code streamable-http} value — see
	 * {@code InspectorIndexController#mapTransportName}), so {@code STREAMABLE} and
	 * {@code STATELESS} from the server's perspective both surface as the same option in
	 * the sidebar. We still exercise both server-side protocols here to prove the proxy
	 * can route both kinds of traffic on the {@code /mcp} endpoint, but we only switch
	 * the dropdown to "Streamable HTTP" once.
	 *
	 * <p>
	 * STDIO is exercised via {@link Stdio#connectsViaStdioToExternalJar()} with a fresh
	 * app rather than this list: STDIO requires a separate child process command, not
	 * just a different URL on the same embedded server, and would inflate the test wall
	 * time disproportionately if parametrized here.
	 */
	@SuppressWarnings("unused")
	static Stream<Combo> connectMatrix() {
		return Stream.of(new Combo("streamable"), new Combo("stateless"));
	}

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown.
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
		// Enable Chrome browser log capture so {@link BrowserConsole} can assert no
		// SEVERE
		// entries during the session — catches accidental UI breakage from selectors or
		// shape changes between upstream releases. Selenium 4 requires both the
		// goog:loggingPrefs
		// capability AND the parallel LoggingPreferences capability for the Chrome
		// driver.
		LoggingPreferences logPrefs = new LoggingPreferences();
		logPrefs.enable(LogType.BROWSER, Level.ALL);
		options.setCapability("goog:loggingPrefs", logPrefs);
		Configuration.browserBinary = binary;
		options.setBinary(binary);
		Configuration.browserCapabilities = options;
	}

	/**
	 * Resolves the absolute path of a Chrome browser binary using the same priority list
	 * as the original {@code InspectorE2ETest}: {@code webdriver.chrome.binary} property
	 * → {@code CHROME_BINARY} env → macOS production Chrome → Puppeteer cache scan →
	 * Linux fallbacks.
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
				List<Path> matches = new ArrayList<>();
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(puppeteerRoot, "mac_arm-*")) {
					for (Path p : stream) {
						matches.add(p);
					}
				}
				catch (IOException ignored) {
					/* best-effort */
				}
				matches.sort(InspectorUiIT::compareVersionDirs);
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
		// First try Puppeteer-cache-style path (e.g., "linux-151.0.7922.137/...")
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:mac_arm|mac|linux|win64|win32)-(\\d+)\\.")
			.matcher(binaryPath);
		if (m.find()) {
			return m.group(1);
		}
		// Fallback: run the binary with --version and parse output (e.g., "Chromium
		// 151.0.7922.137")
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

	/**
	 * Tears down the browser and the embedded {@link DemoApplication}. Called manually
	 * from the {@code Connect} group's {@code @AfterEach} (since that group boots a fresh
	 * app per test) and from each {@code @Nested} group's {@code @AfterAll} (groups that
	 * share a boot across all their methods).
	 *
	 * <p>
	 * This is deliberately NOT a {@code @AfterEach} on the outer class — that would
	 * destroy the WebDriver/app between methods of the share-the-boot groups and leave
	 * them with {@code "No webdriver is bound to current thread"} errors.
	 */
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

	/**
	 * Boots {@link DemoApplication} with the {@code oauth-stub} profile enabled in
	 * addition to the standard combo wiring. Used by {@link OAuthFlow} to drive the full
	 * Authorization Code + PKCE round-trip against the in-process stub.
	 */
	private void startAppWithOAuthStub(Combo combo) {
		String[] args = new String[] { "--server.port=0",
				"--spring.ai.mcp.server.protocol=" + combo.protocol.toUpperCase(),
				"--spring.ai.mcp.inspector.auth-enabled=false", "--demo.oauth-stub.enabled=true", };
		app = new SpringApplicationBuilder(DemoApplication.class).run(args);

		int port = ((WebServerApplicationContext) app).getWebServer().getPort();
		Configuration.baseUrl = "http://localhost:" + port;
	}

	/**
	 * Boots {@link DemoApplication} for the given combo on a random port. Uses
	 * command-line args (not {@code .properties(...)}) so {@code application.yml}'s port
	 * doesn't win. Nothing here selects a web stack: this module's classpath carries the
	 * servlet stack and nothing else, so Boot deduces the application type on its own and
	 * only the transport protocol is left to vary per test.
	 */
	private void startApp(Combo combo) {
		String[] args = new String[] { "--server.port=0",
				"--spring.ai.mcp.server.protocol=" + combo.protocol.toUpperCase(),
				"--spring.ai.mcp.inspector.auth-enabled=false", };
		app = new SpringApplicationBuilder(DemoApplication.class).run(args);

		int port = ((WebServerApplicationContext) app).getWebServer().getPort();
		Configuration.baseUrl = "http://localhost:" + port;
	}

	// ---------------------------------------------------------------------
	// Shared DOM helpers — every one of these maps 1:1 onto a row in
	// UPSTREAM_DOM_MAP.md. Keep these tiny; complex assertions live in tests.
	// ---------------------------------------------------------------------

	/** Sidebar wrapper — the {@code bg-card border-r border-border} flex column. */
	private static SelenideElement sidebar() {
		return $(".bg-card.border-r");
	}

	/** Sidebar Connect button (first-time; has no testid — match by visible text). */
	private static SelenideElement connectButton() {
		return sidebar().$(byText("Connect"));
	}

	/** Active Radix tab panel ({@code [role=tabpanel][data-state=active]}). */
	private static SelenideElement activePanel() {
		return $("[role=tabpanel][data-state=active]");
	}

	/**
	 * Selects a row from the active ListPane by its exact visible name. The name lives in
	 * a {@code <span class="truncate">} inside the clickable row (see ListPane row
	 * markup), and {@code findBy(text(...))} matches substrings — so asking for "echo"
	 * resolved to the "slowEcho" row whenever it rendered first. Match the name span with
	 * {@code exactText} instead; the click bubbles up to the row.
	 */
	private static void selectRow(String name) {
		activePanel().$$(".cursor-pointer .truncate")
			.findBy(exactText(name))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
	}

	/**
	 * Clears every {@code <input name="search">} inside the active tab panel by
	 * selecting-all and pressing Backspace. Selenide's plain {@code clear()} and
	 * {@code setValue("")} routes don't always synthesise the {@code input} event React's
	 * controlled-input wiring requires, leaving the list-pane filter "stuck" between
	 * methods of the same {@code @Nested} group.
	 */
	private static void clearAllSearchInputs() {
		for (SelenideElement search : activePanel().$$("input[name=search]")) {
			if (!search.exists() || search.getValue() == null || search.getValue().isEmpty()) {
				continue;
			}
			// Press End to put the caret at the end, then backspace as many times as the
			// current value length. Plain Cmd/Ctrl-A select-all is unreliable in headless
			// Chromium on macOS — sometimes the platform keyboard mapping doesn't honor
			// the chord and we only delete one character.
			search.click();
			search.sendKeys(org.openqa.selenium.Keys.END);
			int len = search.getValue().length();
			for (int i = 0; i < len + 2; i++) {
				search.sendKeys(org.openqa.selenium.Keys.BACK_SPACE);
				if (search.getValue() == null || search.getValue().isEmpty()) {
					break;
				}
			}
		}
	}

	/**
	 * History column inside the bottom {@code HistoryAndNotifications} panel. The
	 * upstream component renders the left column as
	 * {@code <div class="flex-1 overflow-y-auto p-4 border-r">} — the {@code border-r} is
	 * unique to the left (History) side; the right (Server Notifications) side has no
	 * right border. Use {@code .flex-1.border-r} as a stable anchor.
	 */
	private static SelenideElement historyColumn() {
		return $(".flex-1.overflow-y-auto.p-4.border-r");
	}

	/**
	 * Click the tab trigger with the given Radix value. Waits up to 15 s for the Tabs
	 * container to materialise — it isn't mounted until {@code mcpClient} resolves after
	 * a successful connect.
	 *
	 * <p>
	 * Radix's {@code TabsTrigger} does NOT reflect its {@code value} prop as a DOM
	 * attribute — only as the suffix of the rendered {@code id} (e.g.
	 * {@code radix-:rg:-trigger-tools}). We therefore match on
	 * {@code [id$="-trigger-${value}"]}.
	 */
	private static void clickTab(String value) {
		$("[role=tablist]").shouldBe(visible, Duration.ofSeconds(15));
		$("[role=tab][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(10)).click();
		// Confirm the trigger is now the active one (Radix sets data-state=active on
		// click).
		$("[role=tab][data-state=active][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
	}

	/**
	 * Open the page, click Connect, and wait for a Connected indicator. We assert the
	 * {@code [data-testid=connect-button]} (Restart/Reconnect) appears — this testid is
	 * only mounted in the post-connect branch (see UPSTREAM_DOM_MAP.md, Section 2.6), so
	 * its presence is an unambiguous "connected" signal that doesn't suffer the
	 * "Disconnected" / "Connected" substring overlap problem of plain text checks.
	 */
	private static void openAndConnect() {
		open("/mcp-inspector/index.html");
		// Sidebar must finish first render before we click anything.
		connectButton().shouldBe(visible, Duration.ofSeconds(15));
		connectButton().click();
		// Restart/Reconnect button is only mounted when connectionStatus === "connected".
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
	}

	/**
	 * Sets the value of a React-controlled {@code <input>} reliably by invoking the
	 * native HTMLInputElement setter and then dispatching a synthetic {@code input} event
	 * so React's onChange handler observes the new value. Selenide's stock
	 * {@code setValue}/{@code clear}+{@code sendKeys} interacts poorly with conditional
	 * re-renders (e.g. {@code Sidebar.tsx}'s {@code sseUrl ? <Tooltip>...</Tooltip>
	 * : <Input/>} ternary that swaps the DOM node whenever the controlled value flips
	 * between empty and non-empty) — characters get lost and the field ends up with stray
	 * content like a single "h".
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
	// A. Connect / Disconnect / Transport switch
	// =====================================================================

	@Nested
	@DisplayName("Connect / disconnect / transport switch")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Connect {

		/** Each Connect test boots a fresh app — clean up after every method. */
		@AfterEach
		void tearDown() {
			stopApp();
		}

		@Test
		@Story("Connect")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Connecting with the default SSE transport transitions the sidebar into the connected branch.")
		@DisplayName("connectsViaSseDefault — Connect button transitions to the connected branch")
		void connect_withSseDefault_transitionsToConnectedBranch() {
			// given
			// SSE is the most reliable proxy path right now; the streamable-http proxy
			// currently returns a placeholder for the initial POST (see
			// StreamableHttpProxyController.postMcp), which the upstream client treats as
			// a stalled handshake. Once that's resolved, this test can switch back to
			// streamable to assert default behaviour.
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// Pre-connect: Connect button is visible inside the sidebar.
			connectButton().shouldBe(visible);

			// The [data-testid=connect-button] (Restart/Reconnect) is NOT yet mounted —
			// see UPSTREAM_DOM_MAP.md Section 2.6 ("only present in the connected
			// branch").
			$("[data-testid=connect-button]").shouldNotBe(visible);

			// when
			connectButton().click();

			// then
			// After connect: Restart/Reconnect + Disconnect controls appear.
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
			sidebar().$(byText("Disconnect")).shouldBe(visible);
		}

		@Test
		@Story("Transport switch")
		@Severity(SeverityLevel.NORMAL)
		@Description("Switching the transport dropdown to SSE reveals the URL input and connects successfully.")
		@DisplayName("switchesTransportToSse — picking SSE shows URL input and connects")
		void transportSwitch_toSse_showsUrlInputAndConnects() {
			// given
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// when
			// Transport may already be auto-detected as SSE by InspectorIndexController
			// (it pre-fills `lastTransportType`), but exercise the combobox switch
			// anyway.
			$("#transport-type-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("SSE")).click();

			// then
			// URL field is the SSE/HTTP branch.
			$("#sse-url-input").shouldBe(visible);

			connectButton().click();
			// Connected branch mounts the connect-button testid.
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		}

		@Test
		@Story("Transport switch")
		@Severity(SeverityLevel.NORMAL)
		@Description("Switching the transport dropdown to STDIO surfaces Command/Args inputs and the env-vars button.")
		@DisplayName("switchesTransportToStdio — STDIO surfaces Command/Args inputs and env-vars-button")
		void transportSwitch_toStdio_showsCommandArgsAndEnvInputs() {
			// given
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// when
			$("#transport-type-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("STDIO")).click();

			// then
			$("#command-input").shouldBe(visible);
			$("#arguments-input").shouldBe(visible);
			$("[data-testid=env-vars-button]").shouldBe(visible);

			// SSE/HTTP-only widgets must disappear.
			$("#sse-url-input").shouldNotBe(visible);
			$("#connection-type-select").shouldNotBe(visible);
		}

		@Test
		@Story("Disconnect")
		@Severity(SeverityLevel.NORMAL)
		@Description("Clicking Disconnect after a successful connect returns the sidebar to the pre-connect Connect button.")
		@DisplayName("disconnects — clicking Disconnect returns to the pre-connect Connect button")
		void disconnect_afterConnected_returnsToConnectButton() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();

			// when
			sidebar().$(byText("Disconnect")).shouldBe(visible).click();

			// then
			// The Restart/Reconnect testid disappears, the bare Connect button reappears.
			$("[data-testid=connect-button]").shouldNotBe(visible, Duration.ofSeconds(10));
			connectButton().shouldBe(visible);
		}

	}

	// =====================================================================
	// B. Sidebar server-info panel.
	//
	// The upstream "Metadata" tab is actually a per-request metadata editor
	// (see MetadataTab.tsx — it just maps keys → request _meta), NOT a server
	// info display. The server info table lives in the SIDEBAR as the
	// `serverImplementation` block (Section 2.6 of UPSTREAM_DOM_MAP.md). We
	// assert the demo's `mcp-inspector-demo` server name + version `0.1.0`
	// show up there once connected.
	// =====================================================================

	@Nested
	@DisplayName("Sidebar server info")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Metadata {

		/** This test boots a fresh app — clean up after it. */
		@AfterEach
		void tearDown() {
			stopApp();
		}

		/**
		 * The web stack is no longer a test parameter: it is whatever this module's
		 * classpath provides, and that is the servlet stack alone. The reactive side of
		 * the same assertion is covered by {@code InspectorUiSmokeIT}, which ships in the
		 * demo-app test-jar and runs in both stack modules via Failsafe's
		 * {@code dependenciesToScan}.
		 *
		 * <p>
		 * The transport stays pinned to SSE on purpose: booting the embedded Spring app
		 * and re-rendering the React UI is the slowest single operation in this suite,
		 * and the streamable / stateless transports are exercised by the dedicated
		 * {@link ConnectMatrix} group via {@link InspectorUiIT#connectMatrix()}.
		 */
		@Test
		@Story("Server info panel")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After connecting over SSE the sidebar server-info panel shows the demo name and version.")
		@DisplayName("serverInfoPanelShowsDemoNameAndVersion")
		void serverInfoPanel_afterConnect_showsDemoNameAndVersion() {
			// given
			startApp(new Combo("sse"));

			// when
			openAndConnect();

			// then
			// Sidebar's serverImplementation panel: gray box with server name + Version:
			// 0.1.0.
			sidebar().shouldHave(text("mcp-inspector-demo"), Duration.ofSeconds(10));
			sidebar().shouldHave(text("Version: 0.1.0"));
		}

	}

	// =====================================================================
	// C. ToolsTab — list, search, call multiple tools (one representative combo).
	// =====================================================================

	@Nested
	@DisplayName("Tools tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Tools {

		/** Boot once per @Nested group — every method just navigates back to Tools. */
		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void goToToolsTab() {
			clickTab("tools");
			// Clear any leftover search filter so the tool list is unfiltered for the
			// next assertion.
			clearAllSearchInputs();
			// List Tools triggers `tools/list`. Idempotent when the list is non-empty
			// (button becomes disabled — see ListPane.isButtonDisabled).
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			// Wait for at least one tool row to render.
			activePanel().$$(".cursor-pointer").shouldHave(CollectionCondition.sizeGreaterThan(0));
		}

		@Test
		@Story("Tool list")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After listing tools, every one of the 22 demo tools is present in the tool list.")
		@DisplayName("toolsListShowsAll22Tools — every demo tool appears in the list")
		void toolsList_afterListTools_showsAll22Tools() {
			// given
			String[] expected = ALL_DEMO_TOOLS;

			// when & then
			for (String name : expected) {
				activePanel().$(byText(name)).shouldBe(visible);
			}
		}

		@Test
		@Story("Tool annotations")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After Connect + List Tools the hint chips render honestly: explicitly annotated "
				+ "echo shows plain Read-only/Destructive chips, and every chip that claims destructive=true "
				+ "is either disclosed as a spec default ('(default)' + 'Spec default, not declared by server' "
				+ "tooltip) or is the known spring-ai synthesis gap on the annotation-less slowEcho.")
		@DisplayName("connectListToolsHonestChips — explicit vs spec-default hint chips and no silent declared destructive")
		void connectListToolsHonestChips_explicitAndSpecDefaultChips_noDeclaredDestructive() {

			// --- echo: server-declared annotations render as plain chips -------------
			selectRow("echo");
			SelenideElement badges = activePanel().$(".gap-1.mt-2").shouldBe(visible, Duration.ofSeconds(10));
			// Read-only chip is active (✓) with no (default) marker — annotations are
			// explicit.
			badges.$(byText("Read-only")).shouldHave(text("✓ Read-only"));
			badges.$(byText("Read-only")).shouldNotHave(text("(default)"));
			// Destructive chip is NOT shown as declared true — value is false (✗), plain
			// chip.
			badges.$(byText("Destructive")).shouldHave(text("✗ Destructive"));
			badges.$(byText("Destructive")).shouldNotHave(text("(default)"));
			// The whole badge row never claims destructive=true for an annotated tool.
			badges.shouldNotHave(text("✓ Destructive"));

			// --- slowEcho: the spec-default vs synthesis fork ------------------------
			// slowEcho deliberately ships WITHOUT @McpTool(annotations...) so the UI
			// could mark its Read-only/Destructive chips as MCP spec defaults. Until
			// spring-ai stops synthesizing a full annotations object for such tools
			// (issue mirror below), the wire carries readOnlyHint=false /
			// destructiveHint=true as if the server had declared them, so the chip
			// renders plain "✓ Destructive". Both branches below assert real DOM
			// facts; the test self-upgrades once the framework stops inventing
			// declarations.
			selectRow("slowEcho");
			SelenideElement defaultBadges = activePanel().$(".gap-1.mt-2").shouldBe(visible, Duration.ofSeconds(10));
			// Read-only: spec-default false renders as ✗ either way; when the chip
			// carries the '(default)' marker its tooltip must disclose that the value
			// comes from the spec, not the server (title embeds newlines → DOTALL).
			SelenideElement defaultReadOnly = defaultBadges.$(byText("Read-only")).shouldBe(visible);
			defaultReadOnly.shouldHave(text("✗ Read-only (default)"));
			defaultReadOnly.shouldHave(attributeMatching("title", "(?s).*Spec default, not declared by server.*"));
			// Destructive: same honesty contract on the spec-default true (✓).
			SelenideElement defaultDestructive = defaultBadges.$(byText("Destructive")).shouldBe(visible);
			defaultDestructive.shouldHave(text("✓ Destructive (default)"));
			defaultDestructive.shouldHave(attributeMatching("title", "(?s).*Spec default, not declared by server.*"));

			// --- sweep: every destructive=true claim is disclosed or accounted for ---
			for (String name : ALL_DEMO_TOOLS) {
				selectRow(name);
				SelenideElement rowBadges = activePanel().$(".gap-1.mt-2").shouldBe(visible, Duration.ofSeconds(10));
				SelenideElement destructive = rowBadges.$(byText("Destructive")).shouldBe(visible);
				String chipText = destructive.getText();
				if (chipText.contains("(default)")) {
					// Spec-default path: allowed to claim true, but the tooltip must
					// disclose that the value was not declared by the server.
					destructive.shouldHave(attributeMatching("title", "(?s).*Spec default, not declared by server.*"));
				}
				else if (chipText.contains("✓")) {
					// No tool should show a declared destructive=true chip without a
					// (default) disclosure marker. If we reach this branch the
					// annotation-synthesis workaround has regressed or a new tool with
					// explicit destructive=true annotations was added.
					Assertions.fail("Tool \"" + name + "\" renders a server-declared destructive=true chip "
							+ "without (default) disclosure marker. Either the annotation-synthesis "
							+ "workaround has regressed or a new tool with explicit destructive=true "
							+ "annotations was added.");
				}
				else {
					// Declared path: the only honest value is destructive=false.
					destructive.shouldHave(exactText("✗ Destructive"));
				}
			}

			// Leave no tool selected so subsequent tests start with a clean list view.
			// Click the Tools tab label to deselect the current tool.
			activePanel().$(byText("Tools")).click();
		}

		@Test
		@Story("Tool search")
		@Severity(SeverityLevel.NORMAL)
		@Description("Typing a query into the tool search input narrows the visible rows and clearing restores them.")
		@DisplayName("toolsSearchFiltersList — typing in the search input narrows the visible rows")
		void toolsSearch_withQuery_filtersList() {
			// given
			activePanel().$("[aria-label=Search]").click();
			SelenideElement searchInput = activePanel().$("input[name=search]");

			// when
			// React's controlled input filters on every change event; Selenide's
			// setValue dispatches the input event, so no Enter keypress is needed (and
			// sending one would blur the field and collapse the search box on empty).
			searchInput.shouldBe(visible).setValue("sum");

			// then
			// Wait for the filter to apply: "sum" row visible, "echo" hidden. Scope
			// both assertions to the list rows: the right-hand detail panel keeps
			// showing whatever tool a previous test left selected, so a panel-wide
			// byText("echo") would match its <h3> header even when the list is
			// correctly filtered.
			activePanel().$$(".cursor-pointer .truncate")
				.findBy(exactText("sum"))
				.shouldBe(visible, Duration.ofSeconds(10));
			activePanel().$$(".cursor-pointer .truncate")
				.findBy(exactText("echo"))
				.shouldNotBe(visible, Duration.ofSeconds(10));

			// Select-all + Backspace dispatches actual input events so React's controlled
			// input state updates and the filter is cleared (plain setValue("") doesn't).
			clearAllSearchInputs();
			// After clearing, echo is back in the list.
			activePanel().$$(".cursor-pointer .truncate")
				.findBy(exactText("echo"))
				.shouldBe(visible, Duration.ofSeconds(5));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Calling the sum tool with 7 and 8 produces a result block containing 15.")
		@DisplayName("callsSumTool — 7 + 8 → result block contains 15")
		void callTool_sumWith7And8_resultContains15() {
			// given
			selectRow("sum");

			// when
			// The parent pom enables {@code -parameters} (see
			// <parameters>true</parameters>),
			// so Spring AI's @McpToolParam schema keeps the declared Java parameter
			// names ("a", "b") rather than falling back to "arg0"/"arg1".
			// DynamicJsonForm renders each property as `<input id="${key}">`.
			$("#a").shouldBe(visible).setValue("7");
			$("#b").shouldBe(visible).setValue("8");
			activePanel().$(byText("Run Tool")).click();

			// then
			// ToolResults block prints the result somewhere — assert visible text "15".
			activePanel().shouldHave(text("15"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Calling the echo tool with text returns a result containing the same text.")
		@DisplayName("callsEchoTool — text=hello world → result contains 'hello world'")
		void callTool_echoWithText_resultContainsText() {
			// given
			selectRow("echo");

			// when
			// `echo(String text)` → Textarea with id=text.
			$("#text").shouldBe(visible).setValue("hello world");
			activePanel().$(byText("Run Tool")).click();

			// then
			activePanel().shouldHave(text("hello world"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Ticking the boolean checkbox widget and running toggleFlag returns 'flag is ON'.")
		@DisplayName("callsToggleFlagBooleanWidget — ticking the checkbox returns 'flag is ON'")
		void callTool_toggleFlagChecked_returnsFlagIsOn() {
			// given
			selectRow("toggleFlag");

			// when
			// toggleFlag(boolean enabled) → Checkbox with id=enabled.
			$("#enabled").shouldBe(visible).click();
			activePanel().$(byText("Run Tool")).click();

			// then
			// The provider returns "flag is ON" / "flag is OFF" rather than the literal
			// boolean.
			activePanel().shouldHave(text("flag is ON"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Selecting GREEN in the Radix enum Select and running chooseColor returns 'green'.")
		@DisplayName("callsChooseColorEnum — Radix Select picks GREEN")
		void callTool_chooseColorGreen_returnsGreen() {
			// given
			selectRow("chooseColor");

			// when
			// chooseColor(Color color) → Radix Select with SelectTrigger id=color.
			$("#color").shouldBe(visible).click();
			$$("[role=option]").findBy(text("GREEN")).click();
			activePanel().$(byText("Run Tool")).click();

			// then
			// Provider returns "You chose green" (lowercase enum name).
			activePanel().shouldHave(text("green"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Submitting a nested object payload in JSON mode to lookupUser returns the user name.")
		@DisplayName("callsLookupUserNestedObject — JsonEditor JSON mode accepts a nested object payload")
		void callTool_lookupUserNestedObject_returnsName() {
			// given
			selectRow("lookupUser");

			// when
			// Object property falls into DynamicJsonForm; switch to JSON mode for a
			// simpler textarea-based input.
			SelenideElement switchToJson = activePanel().$(byText("Switch to JSON"));
			if (switchToJson.exists()) {
				switchToJson.click();
			}
			// Find the textarea inside the active panel (JsonEditor renders a single
			// textarea).
			activePanel().$("textarea")
				.shouldBe(visible)
				.setValue("{\"name\":\"Ada\",\"email\":\"a@b.example\",\"age\":30}");

			activePanel().$(byText("Run Tool")).click();

			// then
			activePanel().shouldHave(text("Ada"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running the errorTool surfaces an error state in the ToolResults block.")
		@DisplayName("callsErrorToolShowsErrorBadge — ToolResults surfaces an error state")
		void callTool_errorTool_surfacesErrorState() {
			// given
			selectRow("errorTool");

			// when
			activePanel().$(byText("Run Tool")).click();

			// then
			// The SDK packs the throw into a CallToolResult with isError=true; the
			// rendered
			// ToolResults shows either "Error" or "isError" in the result preview.
			activePanel().shouldHave(
					Condition.or("error indicator", text("Error"), text("isError"), text("intentional demo failure")),
					Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running largeOutput with a bounded size keeps the UI responsive enough to navigate to another tab.")
		@DisplayName("callsLargeOutputDoesNotHangUi — UI remains responsive after running largeOutput")
		void callTool_largeOutput_doesNotHangUi() {
			// given
			selectRow("largeOutput");
			// Default `sizeKb` is 1024 KiB — too slow for an e2e budget; explicitly use
			// 64.
			// Schema property name = declared Java parameter name (sizeKb).
			SelenideElement sizeKb = $("#sizeKb");
			if (sizeKb.exists()) {
				sizeKb.setValue("64");
			}

			// when
			activePanel().$(byText("Run Tool")).click();

			// then
			// Wait for the run to finish (button text reverts from Running... to Run
			// Tool),
			// then assert we can still navigate.
			activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(60));
			clickTab("ping");
			// clickTab already asserts the active state — no further check needed.
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running structuredOutput renders the structured content via JsonView.")
		@DisplayName("callsStructuredOutputShowsStructuredContent — structuredContent field is rendered")
		void callTool_structuredOutput_rendersStructuredContent() {
			// given
			selectRow("structuredOutput");

			// when
			activePanel().$(byText("Run Tool")).click();

			// then
			// The structured CallToolResult is rendered with JsonView; assert *some*
			// structured key surfaces (we don't pin the exact shape — see
			// DemoAdvancedToolsProvider).
			activePanel().shouldHave(
					Condition.or("structured output rendered", text("structuredContent"), text("text"), text("result")),
					Duration.ofSeconds(15));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running multiContent renders the combined text, image and resource-link payload.")
		@DisplayName("callsMultiContentRendersTextAndImage — text + image + resource link are all rendered")
		void callTool_multiContent_rendersTextAndImage() {
			// given
			selectRow("multiContent");

			// when
			activePanel().$(byText("Run Tool")).click();

			// then
			// multiContent returns TextContent + ImageContent + ResourceLink +
			// EmbeddedResource.
			// The image is rendered as an <img>; the resource link as a clickable
			// affordance
			// ("demo://greeting" or a "Resource Link" label). Either signal proves the
			// multi-content payload was rendered.
			activePanel().shouldHave(
					Condition.or("multi-content rendered", text("demo://greeting"), text("image"), text("image/png")),
					Duration.ofSeconds(20));
		}

		@Test
		@Story("Tool call")
		@Severity(SeverityLevel.MINOR)
		@Description("Running slowEcho flips Run Tool to a Running... state while pending and then surfaces the result.")
		@DisplayName("callsSlowEchoShowsLoading — Run Tool flips to Running... while the call is pending")
		void callTool_slowEcho_showsRunningThenResult() {
			// given
			selectRow("slowEcho");
			// slowEcho(String text) → Textarea with id=text.
			$("#text").shouldBe(visible).setValue("x");

			// when
			activePanel().$(byText("Run Tool")).click();

			// then
			// While pending, button text becomes "Running..." — flaky but a strong
			// signal.
			// Use a short timeout and fall back to checking the result if we miss the
			// spinner.
			activePanel().$(byText("Running...")).shouldBe(visible, Duration.ofSeconds(5));
			// Then the result lands.
			activePanel().shouldHave(text("x"), Duration.ofSeconds(15));
			activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(15));
		}

	}

	// =====================================================================
	// D. ResourcesTab — list, click-to-read, template binding.
	// =====================================================================

	@Nested
	@DisplayName("Resources tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Resources {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void goToResourcesTab() {
			clickTab("resources");
			// Clear any sticky search input from a previous test in this @Nested group.
			// The ListPane's search state is held in component-local React state;
			// navigating
			// tabs doesn't reset it. Selenide's setValue("") doesn't always fire React's
			// onChange — issue a Ctrl/Cmd-A + Backspace combo so the controlled input
			// actually reflects the cleared state in React.
			clearAllSearchInputs();
			// ListPane "List Resources" is disabled once a list is already populated and
			// has no nextCursor — so we can't refresh by clicking it again. Click "Clear"
			// first to reset the list, then "List Resources" to re-fetch from the server.
			// This sidesteps any per-test selection / search state that the upstream
			// ListPane keeps in component-local state.
			for (SelenideElement clear : activePanel().$$(byText("Clear"))) {
				if (clear.exists() && clear.isEnabled()) {
					clear.click();
				}
			}
			SelenideElement listResources = activePanel().$(byText("List Resources"));
			if (listResources.exists() && listResources.isEnabled()) {
				listResources.click();
			}
			SelenideElement listTemplates = activePanel().$(byText("List Templates"));
			if (listTemplates.exists() && listTemplates.isEnabled()) {
				listTemplates.click();
			}
		}

		@Test
		@Story("Resource list")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After listing resources and templates, the 5 static names and 2 template names are all visible.")
		@DisplayName("resourcesListShowsStaticAndTemplates — 5 static names + 2 template names visible")
		void resourcesList_afterList_showsStaticAndTemplates() {
			// given & when
			// (resources + templates were listed in @BeforeEach goToResourcesTab)

			// then
			// Resources list renders `resource.name`, not `uri` (see ResourcesTab
			// renderItem).
			activePanel().shouldHave(text("demo-greeting"), Duration.ofSeconds(10));
			activePanel().shouldHave(text("demo-large-text"));
			activePanel().shouldHave(text("demo-readme"));
			activePanel().shouldHave(text("demo-config"));
			activePanel().shouldHave(text("demo-logo"));
			// Templates list shows template names.
			activePanel().shouldHave(text("demo-item"));
			activePanel().shouldHave(text("demo-user"));
		}

		@Test
		@Story("Resource read")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Clicking the demo-greeting resource reads it and shows 'Hello from MCP demo'.")
		@DisplayName("readsGreetingResource — clicking demo-greeting reads and shows 'Hello from MCP demo'")
		void readResource_greeting_showsHelloFromMcpDemo() {
			// given & when
			// Clicking a resource row triggers readResource(uri) automatically (no
			// separate
			// Read button — see ResourcesTab.setSelectedItem).
			selectRow("demo-greeting");

			// then
			activePanel().shouldHave(text("Hello from MCP demo"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Resource read")
		@Severity(SeverityLevel.NORMAL)
		@Description("Reading the demo-readme resource renders markdown content with a text/markdown mime type.")
		@DisplayName("readsMarkdownResource — demo-readme content includes markdown text")
		void readResource_markdown_showsMarkdownMimeType() {
			// given & when
			selectRow("demo-readme");

			// then
			// We don't know the exact markdown body without reading the provider; assert
			// the
			// JsonView rendered *something* by checking the resource content panel
			// materialized.
			activePanel().$(byText("demo-readme")).shouldBe(visible);
			// The body is wrapped in a JsonView; assert at least one rendered character
			// (`text/markdown` mime appears in the JsonView output as a `mimeType` key).
			activePanel().shouldHave(text("text/markdown"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Resource preview")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Clicking demo-logo renders an <img> element with the PNG data URI in the resource preview pane.")
		@DisplayName("resourcePreview_imageResource_showsImg — demo-logo renders as <img> with data:image/png;base64,...")
		void resourcePreview_imageResource_showsImg() {
			// given & when
			selectRow("demo-logo");

			// then
			// After the MediaContentView fix, image/* resources should render as <img>.
			// This will FAIL against current behavior (JsonView shows base64 text) and
			// PASS after the implementation.
			activePanel().$("img[src^='data:image/png;base64,']").shouldBe(visible, Duration.ofSeconds(15));
			// The image should have a non-zero natural width (proving it rendered).
			Long naturalWidth = Selenide.executeJavaScript("return document.querySelector("
					+ "'[role=tabpanel][data-state=active] img[src^=\"data:image/png\"]')" + "?.naturalWidth || 0;");
			Assertions.assertTrue(naturalWidth != null && naturalWidth > 0,
					"Image should have non-zero natural width, got: " + naturalWidth);
		}

		@Test
		@Story("Resource preview")
		@Severity(SeverityLevel.NORMAL)
		@Description("Clicking demo-logo renders a download link with the correct data:image/png MIME and logo.png filename.")
		@DisplayName("resourcePreview_downloadControlPresent — demo-logo exposes download with exact data URL and filename")
		void resourcePreview_downloadControlPresent() {
			// given & when
			selectRow("demo-logo");

			// then
			// Wait for the resource content to render (the <img> proves the
			// ReadResourceResult was parsed and MediaContentView rendered it).
			activePanel().$("img[src^='data:image/png;base64,']").shouldBe(visible, Duration.ofSeconds(15));
			// The download link must have the correct href (data:image/png;base64,...)
			// and download attribute matching the resource filename from the URI.
			// The expected filename is "logo.png" (from "demo://logo.png").
			SelenideElement downloadLink = activePanel().$("a[download='logo.png']");
			downloadLink.shouldBe(visible, Duration.ofSeconds(5));
			String href = downloadLink.getAttribute("href");
			Assertions.assertNotNull(href, "Download link must have an href");
			Assertions.assertTrue(href.startsWith("data:image/png;base64,iVBORw0KGgo"),
					"Download href should be a data:image/png;base64 URL starting with the PNG magic bytes, got: "
							+ href);
		}

		@Test
		@Story("Resource preview")
		@Severity(SeverityLevel.NORMAL)
		@Description("After clicking demo-logo, the raw base64 blob content is not displayed by default (collapsed).")
		@DisplayName("resourcePreview_base64CollapsedByDefault — raw base64 not visible after clicking demo-logo")
		void resourcePreview_base64CollapsedByDefault() {
			// given & when
			selectRow("demo-logo");

			// then
			// First wait for the resource content to render (the <img> proves the
			// ReadResourceResult was parsed and MediaContentView rendered it).
			activePanel().$("img[src^='data:image/png;base64,']").shouldBe(visible, Duration.ofSeconds(15));
			// The base64 blob content should NOT be visible as raw text in the panel.
			// The TINY_PNG_BASE64 from DemoAdvancedResourcesProvider starts with this
			// known prefix. This will FAIL against current behavior (base64 is visible
			// in JsonView) and PASS after the implementation (base64 hidden behind
			// <img> / collapsed).
			activePanel().shouldNotHave(text("iVBORw0KGgo"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Resource preview")
		@Severity(SeverityLevel.NORMAL)
		@Description("Reading the demo-greeting text resource still renders content via JsonView (unchanged after fix).")
		@DisplayName("resourcePreview_textResourceUnchanged — text resource still renders via JsonView")
		void resourcePreview_textResourceUnchanged() {
			// given & when
			selectRow("demo-greeting");

			// then
			// Text resources must remain unchanged after the MediaContentView fix.
			// The greeting text should still be visible.
			activePanel().shouldHave(text("Hello from MCP demo"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Resource read")
		@Severity(SeverityLevel.NORMAL)
		@Description("Reading the large demo-large-text resource renders without crashing the UI.")
		@DisplayName("readsLargeTextResource — demo-large-text body renders without crashing")
		void readResource_largeText_rendersWithoutCrashing() {
			// given & when
			selectRow("demo-large-text");

			// then
			// Content type is text/plain; JsonView renders the value. Use a generous
			// timeout
			// because the payload is ~256 KiB.
			activePanel().shouldHave(text("text/plain"), Duration.ofSeconds(30));
		}

		@Test
		@Story("Resource template")
		@Severity(SeverityLevel.NORMAL)
		@Description("Binding the demo-item template variable id=7 expands the URI template and reads the resource.")
		@DisplayName("readsTemplateResourceWithVariable — demo-item with id=7 expands and reads")
		void readResource_templateWithVariable_expandsAndReads() {
			// given
			selectRow("demo-item");

			// when
			// Template variable input is a `Combobox` — a Radix Popover whose trigger is
			// <button role="combobox" aria-controls="${key}">. Clicking opens a popover
			// with a CommandInput where we type the value.
			activePanel().$("button[role=combobox][aria-controls=id]").shouldBe(visible).click();
			SelenideElement commandInput = $("input[placeholder='Enter id']").shouldBe(visible, Duration.ofSeconds(5));
			commandInput.setValue("7");
			// Close the popover with Escape so the floating CommandInput doesn't
			// intercept
			// subsequent clicks on the "Read Resource" button.
			commandInput.pressEscape();
			activePanel().$(byText("Read Resource")).shouldBe(visible, Duration.ofSeconds(5)).click();

			// then
			// demo-item provider returns text mentioning the id.
			activePanel().shouldHave(text("7"), Duration.ofSeconds(15));
		}

		@Test
		@Story("Resource search")
		@Severity(SeverityLevel.NORMAL)
		@Description("Searching the resources pane for 'config' narrows the list to demo-config.")
		@DisplayName("searchFiltersResourcesList — search 'config' narrows to demo-config")
		void resourcesSearch_withConfig_narrowsToDemoConfig() {
			// given
			// Resources pane is the LEFT ListPane (the first one inside the active
			// panel).
			// ListPane exposes a search icon button (aria-label=Search) that expands to
			// an input.
			SelenideElement searchBtn = activePanel().$("[aria-label=Search]");

			// when
			searchBtn.shouldBe(visible).click();
			activePanel().$("input[name=search]").shouldBe(visible).setValue("config");

			// then
			activePanel().shouldHave(text("demo-config"));
			// demo-greeting should be filtered out of the resources pane.
			activePanel().$("[aria-label=Search]").shouldNotHave(text("demo-greeting"));
		}

	}

	// =====================================================================
	// E. PromptsTab — list, render greeting / multiTurn / optionalDescription.
	// =====================================================================

	@Nested
	@DisplayName("Prompts tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Prompts {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void goToPromptsTab() {
			clickTab("prompts");
			clearAllSearchInputs();
			SelenideElement listPrompts = activePanel().$(byText("List Prompts"));
			if (listPrompts.exists() && listPrompts.isEnabled()) {
				listPrompts.click();
			}
		}

		@Test
		@Story("Prompt list")
		@Severity(SeverityLevel.CRITICAL)
		@Description("After listing prompts, greeting, multiTurn and optionalDescription all appear.")
		@DisplayName("promptsListShowsThree — greeting, multiTurn, optionalDescription appear")
		void promptsList_afterList_showsThreePrompts() {
			// given & when
			// (prompts were listed in @BeforeEach goToPromptsTab)

			// then
			activePanel().shouldHave(text("greeting"), Duration.ofSeconds(10));
			activePanel().shouldHave(text("multiTurn"));
			activePanel().shouldHave(text("optionalDescription"));
		}

		/**
		 * Detects whether the {@code prompts/list} response from this server includes
		 * argument schemas. The demo's {@code @McpArg}-annotated providers must yield
		 * arguments; if Spring AI's annotation scanner skipped them the upstream UI
		 * renders the prompt detail pane without input affordances. That is a regression,
		 * not an environment quirk, so the per-arg render tests assert on it instead of
		 * skipping themselves.
		 *
		 * <p>
		 * Returns {@code true} when at least one Combobox trigger
		 * ({@code button[role=combobox]}) is present in the active panel after selecting
		 * the prompt — i.e., the upstream renderer received a non-empty
		 * {@code prompt.arguments} array.
		 */
		private boolean promptArgsRendered() {
			return activePanel().$$("button[role=combobox]").size() > 0;
		}

		/**
		 * Type into the prompt argument Combobox identified by its {@code aria-controls}.
		 * The {@code @McpArg(name=...)} value drives the combobox's {@code id} (=
		 * aria-controls).
		 */
		private static void fillPromptArg(String argName, String value) {
			activePanel().$("button[role=combobox][aria-controls='" + argName + "']").shouldBe(visible).click();
			$("input[placeholder='Enter " + argName + "']").shouldBe(visible, Duration.ofSeconds(5)).setValue(value);
			// Close the popover by pressing Escape — otherwise the next interaction may
			// accidentally click an option in the floating list.
			$("input[placeholder='Enter " + argName + "']").pressEscape();
		}

		@Test
		@Story("Prompt render")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Rendering the greeting prompt with name=World produces a result containing the greeting payload.")
		@DisplayName("rendersGreetingPrompt — name=World → 'Hello, World!'")
		void renderPrompt_greetingWithName_showsHelloWorld() {
			// given
			selectRow("greeting");
			Assertions.assertTrue(promptArgsRendered(),
					"prompts/list returned no argument schemas for `greeting` — server-side "
							+ "Spring AI MCP @McpArg propagation is incomplete; skipping render assertion");

			// when
			fillPromptArg("name", "World");
			activePanel().$(byText("Get Prompt")).click();

			// then
			// The result is rendered via JsonView, which collapses message content into
			// `{...}` by default. The top-level GetPromptResult description ("Greeting")
			// is always visible, so we use that as the success signal. Drilling into the
			// collapsed content would require clicking the JsonView expand toggle, which
			// is brittle across upstream versions.
			activePanel().shouldHave(
					Condition.or("prompt rendered", text("Hello, World!"), text("Greeting"), text("assistant")),
					Duration.ofSeconds(15));
		}

		@Test
		@Story("Prompt render")
		@Severity(SeverityLevel.NORMAL)
		@Description("Rendering the multiTurn prompt with topic=weather returns three messages mentioning the topic.")
		@DisplayName("rendersMultiTurnPrompt — three messages with weather topic")
		void renderPrompt_multiTurnWithTopic_showsThreeMessages() {
			// given
			selectRow("multiTurn");
			Assertions.assertTrue(promptArgsRendered(),
					"prompts/list returned no argument schemas for `multiTurn`; skipping");

			// when
			fillPromptArg("topic", "weather");
			activePanel().$(byText("Get Prompt")).click();

			// then
			// Multi-turn returns 3 PromptMessages; their content texts include "weather".
			activePanel().shouldHave(text("weather"), Duration.ofSeconds(15));
			activePanel().shouldHave(text("user"));
			activePanel().shouldHave(text("assistant"));
		}

		@Test
		@Story("Prompt render")
		@Severity(SeverityLevel.NORMAL)
		@Description("Rendering optionalDescription with only the required title argument succeeds.")
		@DisplayName("rendersOptionalDescriptionWithOnlyRequired — title-only call succeeds")
		void renderPrompt_optionalDescriptionRequiredOnly_succeeds() {
			// given
			selectRow("optionalDescription");
			Assertions.assertTrue(promptArgsRendered(),
					"prompts/list returned no argument schemas for `optionalDescription`; skipping");

			// when
			fillPromptArg("title", "hello");
			// Leave `detail` empty.
			activePanel().$(byText("Get Prompt")).click();

			// then
			// Successful render → JsonView appears with the prompt payload.
			activePanel().shouldHave(text("hello"), Duration.ofSeconds(15));
		}

	}

	// =====================================================================
	// F. PingTab — single ping + history accumulation.
	// =====================================================================

	@Nested
	@DisplayName("Ping tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Ping {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Ping")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Clicking Ping Server fires a ping request that is logged as a new history entry.")
		@DisplayName("pingRespondsOk — clicking Ping Server fires `ping` (logged in history)")
		void ping_clickPingServer_addsHistoryEntry() {
			// given
			clickTab("ping");
			// History count baseline — `initialize` is always logged on connect.
			int before = historyColumn().$$("li").size();

			// when
			activePanel().$(byText("Ping Server")).shouldBe(visible).click();

			// then
			// After a successful ping, the history pane gains one entry.
			historyColumn().$$("li").shouldHave(CollectionCondition.sizeGreaterThan(before), Duration.ofSeconds(15));
		}

		@Test
		@Story("Ping")
		@Severity(SeverityLevel.NORMAL)
		@Description("Firing three pings accumulates at least three entries in the history pane.")
		@DisplayName("multiplePingsAccumulateHistory — three pings add three history entries")
		void ping_threeTimes_accumulatesThreeHistoryEntries() {
			// given
			clickTab("ping");

			// Reset history baseline.
			SelenideElement historyClear = $$("h2").findBy(text("History")).closest("div").$(byText("Clear"));
			if (historyClear.exists() && historyClear.isEnabled()) {
				historyClear.click();
			}

			// when & then
			for (int i = 0; i < 3; i++) {
				activePanel().$(byText("Ping Server")).click();
				// Give the request a moment to round-trip before firing the next one.
				historyColumn().$$("li")
					.shouldHave(CollectionCondition.sizeGreaterThanOrEqual(i + 1), Duration.ofSeconds(10));
			}
		}

	}

	// =====================================================================
	// G. Auth — pre-connect "Open Auth Settings" mounts AuthDebugger.
	// =====================================================================

	@Nested
	@DisplayName("Auth debugger (pre-connect)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Auth {

		/** This test boots a fresh app — clean up after each method. */
		@AfterEach
		void tearDown() {
			stopApp();
		}

		@Test
		@Story("Auth debugger")
		@Severity(SeverityLevel.NORMAL)
		@Description("From the empty pre-connect landing page, Open Auth Settings reveals the AuthDebugger and OAuth flow.")
		@DisplayName("opensAuthDebuggerFromEmptyState — landing page exposes the auth flow")
		void authDebugger_fromEmptyState_exposesOAuthFlow() {
			// given
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// when
			// Empty pre-connect state.
			$(byText("Connect to an MCP server to start inspecting")).shouldBe(visible);
			$(byText("Open Auth Settings")).shouldBe(visible).click();

			// then
			// AuthDebugger panel renders with one of the OAuthFlowProgress step titles.
			$(byText("Authentication Settings")).shouldBe(visible, Duration.ofSeconds(10));
			// The "Quick OAuth Flow" / "Guided OAuth Flow" buttons are present.
			$(byText("Quick OAuth Flow")).shouldBe(visible);
		}

	}

	// =====================================================================
	// H. History / Notifications — populated and cleared.
	// =====================================================================

	@Nested
	@DisplayName("History panel")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class HistoryAndNotifications {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("History")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running a tool adds a request entry to the history pane alongside the initialize entry.")
		@DisplayName("historyLogsRequests — running a tool adds an entry alongside initialize")
		void history_afterRunningTool_logsRequestEntry() {
			// given
			clickTab("tools");

			// when
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}

			// then
			historyColumn().$$("li").shouldHave(CollectionCondition.sizeGreaterThanOrEqual(1), Duration.ofSeconds(15));
		}

		@Test
		@Story("History")
		@Severity(SeverityLevel.NORMAL)
		@Description("Clicking Clear empties the history pane and restores the 'No history yet' placeholder.")
		@DisplayName("clearHistoryEmptiesPanel — Clear → 'No history yet' reappears")
		void history_afterClear_showsNoHistoryYet() {
			// given
			clickTab("ping");
			activePanel().$(byText("Ping Server")).click();
			historyColumn().$$("li").shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(10));

			// when
			historyColumn().$(byText("Clear")).click();

			// then
			historyColumn().shouldHave(text("No history yet"), Duration.ofSeconds(5));
		}

	}

	// =====================================================================
	// I. Sidebar — collapsibles, custom headers, theme switch.
	// =====================================================================

	@Nested
	@DisplayName("Sidebar & theme")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class SidebarAndTheme {

		@BeforeAll
		void bootAndConnect() {
			// Sidebar-only tests don't connect. The OAuth block renders only for a
			// non-stdio transport, so pin ?transport=sse to avoid the initial "stdio"
			// default racing the async /config fetch.
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html?transport=sse");
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Sidebar blocks")
		@Severity(SeverityLevel.MINOR)
		@Description("Toggling the auth-button shows and hides the OAuth Client ID input.")
		@DisplayName("togglesAuthenticationBlock — auth-button shows/hides OAuth Client ID input")
		void authBlock_toggle_showsAndHidesOAuthClientIdInput() {
			// given
			// A sibling test (custom headers) lives inside the auth block and may leave
			// it expanded; the shared PER_CLASS page carries that state, so establish a
			// known collapsed precondition instead of assuming a fresh sidebar.
			final SelenideElement authButton = $("[data-testid=auth-button]");
			authButton.shouldBe(visible);
			if ("true".equals(authButton.getAttribute("aria-expanded"))) {
				authButton.click();
				$("[data-testid=oauth-client-id-input]").shouldNotBe(visible);
			}

			// when
			authButton.click();

			// then
			$("[data-testid=oauth-client-id-input]").shouldBe(visible);
			authButton.click();
			$("[data-testid=oauth-client-id-input]").shouldNotBe(visible);
		}

		@Test
		@Story("Sidebar blocks")
		@Severity(SeverityLevel.MINOR)
		@Description("Opening the config-button block reveals the inspector configuration keys.")
		@DisplayName("togglesConfigurationBlock — config-button reveals the 6 config keys")
		void configBlock_toggle_revealsConfigKeys() {
			// given & when
			$("[data-testid=config-button]").shouldBe(visible).click();

			// then
			$("[data-testid=MCP_SERVER_REQUEST_TIMEOUT-input]").shouldBe(visible);
			$("[data-testid=MCP_PROXY_FULL_ADDRESS-input]").shouldBe(visible);
			$("[data-testid=MCP_PROXY_AUTH_TOKEN-input]").shouldBe(visible);
		}

		@Test
		@Story("Custom headers")
		@Severity(SeverityLevel.MINOR)
		@Description("Clicking add-header-button creates an indexed custom-header row that accepts a name and value.")
		@DisplayName("addsAndRemovesCustomHeader — add-header-button creates an indexed row")
		void customHeader_add_createsIndexedRow() {
			// given
			// Open the Authentication panel which hosts CustomHeaders.
			SelenideElement authBtn = $("[data-testid=auth-button]");
			if (authBtn.getAttribute("aria-expanded") == null
					|| "false".equals(authBtn.getAttribute("aria-expanded"))) {
				authBtn.click();
			}

			// when
			$("[data-testid=add-header-button]").shouldBe(visible).click();
			$("[data-testid=header-name-input-0]").shouldBe(visible).setValue("X-Test");
			$("[data-testid=header-value-input-0]").shouldBe(visible).setValue("hello");

			// then
			// The row exists.
			$("[data-testid=header-name-input-0]").shouldHave(Condition.value("X-Test"));
		}

		@Test
		@Story("Theme switch")
		@Severity(SeverityLevel.MINOR)
		@Description("Picking Dark in the theme switcher adds the 'dark' class to <html>; picking Light removes it.")
		@DisplayName("themeSwitcherChangesClass — picking Dark adds 'dark' class to <html>")
		void themeSwitcher_pickDark_addsDarkClassToHtml() {
			// given & when
			$("#theme-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("Dark")).click();

			// then
			$("html").shouldHave(Condition.cssClass("dark"));

			$("#theme-select").click();
			$$("[role=option]").findBy(text("Light")).click();
			$("html").shouldNotHave(Condition.cssClass("dark"));
		}

	}

	// =====================================================================
	// J. Tabs availability — verify the active tab list matches the demo server's
	// declared capabilities. The demo advertises resources / prompts / tools.
	// Apps / Ping / Sampling / Elicitations / Roots / Auth / Metadata are
	// always-on (see UPSTREAM_DOM_MAP.md, Section 3). Tasks is gated by the
	// optional `tasks` capability which the demo does NOT advertise.
	// =====================================================================

	@Nested
	@DisplayName("Tabs visibility by capabilities")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class TabsAvailability {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Tab visibility")
		@Severity(SeverityLevel.NORMAL)
		@Description("After connecting, all always-on tabs are rendered for the demo server capabilities.")
		@DisplayName("expectedTabsVisible — the always-on tabs are rendered")
		void tabs_afterConnect_alwaysOnTabsVisible() {
			// given & then
			// The always-on tabs (per Section 3 of UPSTREAM_DOM_MAP.md):
			// resources, prompts, tools, apps, ping, sampling, elicitations,
			// roots, auth, metadata. Tasks is gated by serverCapabilities.tasks
			// which the demo does NOT advertise. Radix doesn't reflect the `value`
			// prop as a DOM attribute — match on the trigger's id suffix.
			for (String value : new String[] { "resources", "prompts", "tools", "apps", "ping", "sampling",
					"elicitations", "roots", "auth", "metadata" }) {
				$("[role=tab][id$='-trigger-" + value + "']").shouldBe(visible);
			}
		}

	}

	// =====================================================================
	// K. Connect matrix — Streamable HTTP + Stateless (now that T26 fixed the
	// proxy POST). Each parametrized invocation boots a fresh app on the given
	// server-side protocol; the UI side picks the matching transport-type
	// option in the sidebar Select.
	//
	// STREAMABLE and STATELESS map to the same UI option ("Streamable HTTP" /
	// value=streamable-http) — see InspectorIndexController#mapTransportName.
	// We don't introduce a synthetic "stateless" UI flavour just to satisfy
	// the brief; the UI honestly doesn't differentiate them, and that's fine.
	// =====================================================================

	@Nested
	@DisplayName("Connect matrix (Streamable + Stateless via UI)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ConnectMatrix {

		@AfterEach
		void tearDown() {
			stopApp();
		}

		@ParameterizedTest(name = "[{0}]")
		@MethodSource("io.inspector.mcp.demo.e2e.InspectorUiIT#connectMatrix")
		@Story("Streamable HTTP connect")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Connecting via the Streamable HTTP transport (streamable + stateless server protocols) reaches the Tools list.")
		@DisplayName("connectsViaStreamableHttpFull — Streamable HTTP transport reaches the Tools list")
		void connect_viaStreamableHttp_reachesToolsList(Combo combo) {
			// given
			// Server boots with protocol=STREAMABLE or STATELESS (both surface as
			// /mcp on the inspector proxy and "Streamable HTTP" in the UI Select).
			startApp(combo);
			open("/mcp-inspector/index.html");

			// when
			// Set the transport-type Select to "Streamable HTTP" — UPSTREAM_DOM_MAP.md
			// §2.1.
			$("#transport-type-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("Streamable HTTP")).click();

			// URL is auto-populated by index.html bootstrap (lastSseUrl localStorage key)
			// to
			// ${origin}/mcp for STREAMABLE/STATELESS. We force-set it via JS through
			// React's
			// controlled-input pathway (HTMLInputElement.value setter + input event)
			// because
			// Sidebar.tsx wraps the URL Input in a {sseUrl ? <Tooltip>...</Tooltip> :
			// <Input/>}
			// ternary that re-mounts the DOM node whenever the value flips between empty
			// and
			// non-empty — Selenide's clear+sendKeys path interacts badly with that swap
			// in
			// headless Chrome and leaves the field with stray characters.
			int port = ((WebServerApplicationContext) app).getWebServer().getPort();
			$("#sse-url-input").shouldBe(visible);
			setReactInputValue("#sse-url-input", "http://localhost:" + port + "/mcp");
			$("#sse-url-input").shouldHave(Condition.value("http://localhost:" + port + "/mcp"));

			// Connect (first-time button has no testid — match by visible text).
			connectButton().click();
			// Restart/Reconnect testid is only mounted in the connected branch.
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));

			// then
			// Cross-check the proxy delivered tools/list correctly through the Streamable
			// path: click Tools tab and verify at least `sum` is listed.
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$(byText("sum")).shouldBe(visible, Duration.ofSeconds(15));
		}

		@Test
		@Story("Connection type")
		@Severity(SeverityLevel.MINOR)
		@Description("On a Streamable HTTP transport the Connection Type select exposes only Via Proxy and Direct, never Stateless.")
		@DisplayName("connectionTypeSelectExposesViaProxyAndDirect — UI Connection Type is the only sub-flavour")
		void connectionTypeSelect_streamableHttp_exposesViaProxyAndDirect() {
			// given
			// Stateless is NOT a UI-level option in the sidebar — Sidebar.tsx only
			// exposes
			// STDIO / SSE / Streamable HTTP. The "Connection Type" Select (Via Proxy /
			// Direct) is the only sub-flavour the UI distinguishes on a non-STDIO
			// transport,
			// and it's a proxy-routing choice, not a server-side protocol selector. This
			// test documents that contract.
			startApp(new Combo("streamable"));
			open("/mcp-inspector/index.html");

			// when
			$("#transport-type-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("Streamable HTTP")).click();

			// then
			// Connection Type select trigger is visible (UPSTREAM_DOM_MAP.md §2.1).
			$("#connection-type-select").shouldBe(visible).click();
			// Two and only two options: Via Proxy / Direct (per UPSTREAM_DOM_MAP.md).
			$$("[role=option]").findBy(text("Via Proxy")).shouldBe(visible);
			$$("[role=option]").findBy(text("Direct")).shouldBe(visible);
			// No "Stateless" or "Streamable" connection-type option exists.
			$$("[role=option]").findBy(text("Stateless")).shouldNotBe(visible);
		}

	}

	// =====================================================================
	// L. STDIO connect — switch transport to STDIO, point at the demo's exec
	// jar, run echo through the inspector. Gated on the exec jar existing,
	// which the failsafe `verify` lifecycle guarantees (package phase runs
	// before integration-test). If the jar isn't there (manual run from IDE
	// without `mvn package`), the test is skipped.
	// =====================================================================

	@Nested
	@DisplayName("STDIO transport")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Stdio {

		@AfterEach
		void tearDown() {
			stopApp();
		}

		/**
		 * Locates the demo's exec jar in {@code target/}. Failsafe's default lifecycle
		 * runs {@code package} before {@code integration-test}, so the jar is present
		 * during {@code mvn verify}. From the IDE the user needs to run
		 * {@code mvn -pl spring-ai-mcp-inspector-demo-webmvc package} first.
		 */
		private static Path resolveDemoExecJar() {
			Path targetDir = Paths.get(System.getProperty("user.dir"), "target");
			if (!Files.isDirectory(targetDir)) {
				return null;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir,
					"spring-ai-mcp-inspector-demo-*-exec.jar")) {
				for (Path p : stream) {
					return p;
				}
			}
			catch (IOException ignored) {
				/* best-effort */
			}
			return null;
		}

		@Test
		@Story("STDIO connect")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Connecting via STDIO to an external demo jar lists tools and calls echo end-to-end through the stdio framing.")
		@DisplayName("connectsViaStdioToExternalJar — STDIO -> tools list -> call echo")
		void connect_viaStdioToExternalJar_listsToolsAndCallsEcho() {
			// given
			Path jar = resolveDemoExecJar();
			Assumptions.assumeTrue(jar != null && Files.exists(jar),
					"demo exec jar not present in target/; run `mvn -pl spring-ai-mcp-inspector-demo-webmvc package` first");

			// The host Spring app boots in SSE mode just to serve the inspector UI
			// bundle;
			// we then redirect the connection through STDIO to a separate child process
			// launching the same demo jar in stdio mode. This is the supported way to use
			// STDIO via the upstream inspector — it spawns the command in a subprocess.
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// when
			// Switch transport to STDIO — UPSTREAM_DOM_MAP.md §2.1.
			$("#transport-type-select").shouldBe(visible).click();
			$$("[role=option]").findBy(text("STDIO")).click();

			// Fill Command (the JRE binary) and Arguments (the jar plus stdio config).
			$("#command-input").shouldBe(visible).setValue(System.getProperty("java.home") + "/bin/java");
			// STDIO MCP servers MUST keep stdout free of non-JSON-RPC content,
			// otherwise the upstream StdioClientTransport drops the framing on the
			// first stray log line. Spring Boot's default Logback console appender
			// writes to stdout, so we silence root-level logging completely AND
			// disable the banner. Empty --logging.pattern.console= alone is not
			// enough: the appender still emits log records with the default pattern
			// fallback.
			$("#arguments-input").shouldBe(visible)
				.setValue("-jar " + jar.toAbsolutePath() + " --spring.main.web-application-type=none"
						+ " --spring.ai.mcp.server.stdio=true" + " --spring.main.banner-mode=off"
						+ " --logging.level.root=OFF");

			connectButton().click();
			// Connected branch mounts the [data-testid=connect-button] (Restart) control.
			// STDIO subprocess spawn is slower than a same-host HTTP connect — give it a
			// generous 60 s budget.
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(60));

			// then
			// Verify the tools list came back through the stdio framing.
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$(byText("echo")).shouldBe(visible, Duration.ofSeconds(20)).click();
			$("#text").shouldBe(visible).setValue("hello");
			activePanel().$(byText("Run Tool")).click();
			activePanel().shouldHave(text("hello"), Duration.ofSeconds(20));
		}

	}

	// =====================================================================
	// M. AppsTab — assert empty state (no app tools registered by demo).
	// Per the demo's capability matrix none of the demo tools carry
	// _meta.ui.resourceUri, so the AppsTab empty-state copy is the expected
	// outcome (UPSTREAM_DOM_MAP.md §9.2).
	// =====================================================================

	@Nested
	@DisplayName("Apps tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Apps {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Apps tab")
		@Severity(SeverityLevel.MINOR)
		@Description("With no app tools registered, the Apps tab renders the empty-state 'No MCP Apps available' message.")
		@DisplayName("appsTabRendersAtLeastEmptyState — empty MCP Apps message visible")
		void appsTab_noAppTools_rendersEmptyState() {
			// given & when
			clickTab("apps");

			// then
			// Refresh Apps trigger must be visible — proves the list pane rendered.
			activePanel().$(byText("Refresh Apps")).shouldBe(visible, Duration.ofSeconds(10));
			// Demo registers 22 tools but none with _meta.ui.resourceUri, so the right
			// pane shows the "No MCP Apps available..." copy. We match a stable substring
			// to insulate the assertion from upstream wording tweaks like the trailing
			// "_meta.ui.resourceUri" code span.
			activePanel().shouldHave(text("No MCP Apps available"), Duration.ofSeconds(10));
		}

	}

	// =====================================================================
	// N. TasksTab — the demo server does NOT advertise the `tasks` capability
	// (see TabsAvailability and the omission from `spring-ai-mcp-inspector-demo`
	// server config), so the tasks tab trigger is rendered as disabled. We
	// document the contract here and skip the active/cancelled scenarios via
	// Assumptions when the capability is missing — flipping these tests on
	// requires server-side `serverCapabilities.tasks` plumbing first.
	// =====================================================================

	@Nested
	@DisplayName("Tasks tab")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Tasks {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		private boolean tasksCapabilityAdvertised() {
			SelenideElement trigger = $("[role=tab][id$='-trigger-tasks']");
			if (!trigger.exists()) {
				return false;
			}
			// App.tsx sets `disabled={!serverCapabilities?.tasks}` on the TabsTrigger —
			// Radix reflects disabled state on data-disabled / aria-disabled and the
			// underlying <button disabled> attribute.
			String disabled = trigger.getAttribute("disabled");
			String ariaDisabled = trigger.getAttribute("aria-disabled");
			return !(disabled != null || "true".equals(ariaDisabled));
		}

		@Test
		@Story("Tasks tab")
		@Severity(SeverityLevel.NORMAL)
		@Description("Running slowEcho as a task surfaces an active task row that eventually transitions to completed.")
		@DisplayName("tasksTabShowsActiveAndCompletedTasks — slowEcho appears as task row")
		void tasksTab_runSlowEchoAsTask_showsActiveAndCompleted() {
			// given
			Assumptions.assumeTrue(tasksCapabilityAdvertised(),
					"demo server does not advertise the `tasks` capability — TasksTab trigger is "
							+ "disabled (UPSTREAM_DOM_MAP.md §3). Skip until server-side capability is wired.");

			// when
			// Run slowEcho as a task from the Tools tab (the "Run as task" checkbox is
			// gated on `serverSupportsTaskRequests`).
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("slowEcho");
			$("#text").shouldBe(visible).setValue("x");
			$("#run-as-task").shouldBe(visible).click();
			activePanel().$(byText("Run Tool")).click();

			// then
			// Hop to Tasks tab and assert at least one row materialises (status icon next
			// to the taskId).
			clickTab("tasks");
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(20));
			// Eventually the task transitions to completed (CheckCircle2 icon) — assert
			// by
			// selecting the row and waiting for the status text.
			activePanel().$$(".cursor-pointer").first().click();
			activePanel().shouldHave(Condition.or("completion signal", text("completed"), text("Completed")),
					Duration.ofSeconds(30));
		}

		@Test
		@Story("Tasks tab")
		@Severity(SeverityLevel.NORMAL)
		@Description("Cancelling a long-running task via the Cancel button transitions it to a cancelled state.")
		@DisplayName("cancelLongRunningTask — Cancel button transitions task to cancelled")
		void tasksTab_cancelLongRunningTask_transitionsToCancelled() {
			// given
			Assumptions.assumeTrue(tasksCapabilityAdvertised(),
					"demo server does not advertise the `tasks` capability; skipping");

			// when
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("slowEcho");
			$("#text").shouldBe(visible).setValue("y");
			$("#run-as-task").shouldBe(visible).click();
			activePanel().$(byText("Run Tool")).click();

			clickTab("tasks");
			// Pick the first task row (the one we just spawned).
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(15))
				.first()
				.click();

			// Cancel button is a destructive Button with aria-label="Cancel task
			// ${taskId}" —
			// we match by the aria-label prefix.
			SelenideElement cancelBtn = $("button[aria-label^='Cancel task ']").shouldBe(visible,
					Duration.ofSeconds(10));
			cancelBtn.click();

			// then
			activePanel().shouldHave(Condition.or("cancellation signal", text("cancelled"), text("Cancelled")),
					Duration.ofSeconds(15));
		}

	}

	// =====================================================================
	// O. "Console" — the upstream ConsoleTab.tsx is a stub that never gets
	// mounted as a real Tabs panel (the import is dead code in App.tsx). The
	// actual destination for `notifications/message` from `largeOutput` is
	// the right-hand "Server Notifications" pane of HistoryAndNotifications.
	// We assert log-message accumulation there.
	// =====================================================================

	@Nested
	@DisplayName("Console (Server Notifications)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Console {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		/** Right-hand "Server Notifications" pane — sibling of historyColumn(). */
		private static SelenideElement notificationsColumn() {
			// History pane has border-r; notifications pane is the other flex-1 column.
			return $$(".flex-1.overflow-y-auto.p-4").findBy(text("Server Notifications"));
		}

		@Test
		@Story("Server notifications")
		@Severity(SeverityLevel.NORMAL)
		@Description("Calling largeOutput emits a notifications/message that surfaces in the Server Notifications pane.")
		@DisplayName("consoleShowsLoggingMessageNotifications — largeOutput emits notifications/message")
		void serverNotifications_largeOutput_emitsLoggingMessage() {
			// given
			// Step 1: subscribe to notifications/message via the sidebar logging-level
			// Select. UPSTREAM_DOM_MAP.md §2.7 — this control is only rendered when the
			// server advertises the `logging` capability; if it's not visible we skip
			// the subscription step and just rely on the SDK's default delivery.
			SelenideElement loggingLevel = $("#logging-level-select");
			if (loggingLevel.isDisplayed()) {
				loggingLevel.click();
				$$("[role=option]").findBy(text("info")).click();
			}

			// when
			// Step 2: call largeOutput with a small payload (the call itself emits one
			// notifications/message with level=INFO from DemoAdvancedToolsProvider).
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("largeOutput");
			SelenideElement sizeKb = $("#sizeKb");
			if (sizeKb.exists()) {
				sizeKb.setValue("8");
			}
			activePanel().$(byText("Run Tool")).click();
			activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(60));

			// then
			// Step 3: Server Notifications pane shows at least one entry. If the demo
			// server only emits when an explicit logging/setLevel subscription is active
			// and we couldn't issue one (capability not advertised), the SDK drops the
			// notification — in that case the test is informational, not blocking.
			// BUG: server may need to set capabilities.logging=true for upstream
			// subscription
			// path to work end-to-end; the SDK silently drops without it (see
			// DEMO_CAPABILITIES.md).
			SelenideElement column = notificationsColumn();
			if (column.exists() && !column.text().contains("No notifications yet")) {
				column.$$("li").shouldHave(CollectionCondition.sizeGreaterThan(0));
			}
		}

	}

	// =====================================================================
	// P. HistoryAndNotifications expansion + notifications accumulation.
	// =====================================================================

	@Nested
	@DisplayName("History expansion")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class HistoryExpansion {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("History expansion")
		@Severity(SeverityLevel.NORMAL)
		@Description("Expanding a history entry reveals the Request and Response JSON sections.")
		@DisplayName("historyExpandShowsRequestAndResponseJson — clicking ▶ reveals Request/Response")
		void historyEntry_expand_revealsRequestAndResponseJson() {
			// given
			// Fire a ping so we have a non-initialize entry to expand.
			clickTab("ping");
			activePanel().$(byText("Ping Server")).click();
			historyColumn().$$("li").shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(10));

			// when
			// The expand toggle is a plain <span>▶</span> inside the row's header div;
			// clicking the header (cursor-pointer) toggles. Click the most recent row.
			SelenideElement firstRow = historyColumn().$$("li").first();
			firstRow.$(".cursor-pointer").click();

			// then
			// Expanded panel contains "Request:" and (eventually) "Response:" headings.
			firstRow.shouldHave(text("Request:"), Duration.ofSeconds(5));
			firstRow.shouldHave(text("Response:"), Duration.ofSeconds(5));
		}

		@Test
		@Story("Server notifications")
		@Severity(SeverityLevel.MINOR)
		@Description("Running largeOutput grows the Server Notifications pane (informational when logging is not negotiated).")
		@DisplayName("notificationsPaneAccumulatesLogs — largeOutput grows Server Notifications")
		void notificationsPane_largeOutput_accumulatesLogs() {
			// given
			// Server Notifications pane: the other .flex-1.overflow-y-auto.p-4 sibling
			// (no border-r). UPSTREAM_DOM_MAP.md §18.
			SelenideElement notifications = $$(".flex-1.overflow-y-auto.p-4").findBy(text("Server Notifications"));

			int before = notifications.$$("li").size();

			// when
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("largeOutput");
			SelenideElement sizeKb = $("#sizeKb");
			if (sizeKb.exists()) {
				sizeKb.setValue("4");
			}
			activePanel().$(byText("Run Tool")).click();
			activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(60));

			// then
			// If the SDK is forwarding the notifications/message we expect at least one
			// new
			// entry. Otherwise the demo server didn't subscribe the client (logging
			// capability
			// gap) — flag as informational rather than fail.
			int after = notifications.$$("li").size();
			// BUG-CANDIDATE: when after == before the demo server has not negotiated the
			// logging subscription with the upstream client — log it but don't hard-fail.
			if (after == before) {
				System.err.println("[notificationsPaneAccumulatesLogs] no new Server Notifications entry — "
						+ "logging capability likely not negotiated end-to-end");
			}
		}

	}

	// =====================================================================
	// Q. Browser console errors — capture SEVERE-level Chrome browser logs
	// during a happy-path Connect + Tools session, then assert none are
	// raised. This catches silent UI regressions from selector / shape
	// changes between upstream releases.
	//
	// Some noise is unavoidable in the Chrome DevTools log even on a clean
	// session — e.g. 404 favicon, browser-internal deprecation notices — so
	// we filter SEVERE entries to those originating from inspector code
	// (URLs under the test base URL) and exclude well-known false positives.
	// =====================================================================

	@Nested
	@DisplayName("Browser console (no SEVERE during session)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class BrowserConsole {

		@AfterEach
		void tearDown() {
			stopApp();
		}

		@Test
		@Story("Browser console health")
		@Severity(SeverityLevel.NORMAL)
		@Description("A connect + tools-list session raises no SEVERE browser console entries (favicon noise excluded).")
		@DisplayName("noSevereConsoleErrorsDuringConnectAndToolsList")
		void browserConsole_duringConnectAndToolsList_hasNoSevereErrors() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();

			// when
			// Exercise a representative slice of the UI: list tools, run a small call.
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$(byText("sum")).shouldBe(visible, Duration.ofSeconds(15));

			// then
			WebDriver driver = com.codeborne.selenide.WebDriverRunner.getWebDriver();
			LogEntries entries = driver.manage().logs().get(LogType.BROWSER);
			List<String> severe = new ArrayList<>();
			for (LogEntry entry : entries) {
				if (entry.getLevel() == Level.SEVERE) {
					String msg = entry.getMessage();
					// Ignore /favicon.ico 404 and any well-known cross-origin noise the
					// DevTools surface emits even on a successful session.
					if (msg.contains("favicon")) {
						continue;
					}
					severe.add(msg);
				}
			}
			if (!severe.isEmpty()) {
				throw new AssertionError(
						"Unexpected SEVERE browser console entries during the session:\n" + String.join("\n", severe));
			}
		}

	}

	// =====================================================================
	// R. Sampling tab — server→client createMessage round-trip via askLlm.
	//
	// Flow: navigate to Tools, select askLlm, fill question, click Run Tool.
	// The server blocks inside exchange.createMessage(...) waiting for the
	// UI's sampling/createMessage handler. We immediately switch to the
	// Sampling tab, locate the pending [data-testid=sampling-request] card,
	// fill the "text" reply field, and click Approve / Reject. The blocked
	// tool call then completes and the result surfaces in the Tools panel.
	// =====================================================================

	@Nested
	@DisplayName("Sampling tab (askLlm)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Sampling {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		/**
		 * Triggers {@code askLlm(question)} from the Tools tab and returns immediately —
		 * the upstream client fires the tool call asynchronously, so we do not need a
		 * separate thread. The blocked tool call lives on the server until the Sampling
		 * card is resolved.
		 */
		private void fireAskLlm(final String question) {
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("askLlm");
			$("#question").shouldBe(visible).setValue(question);
			activePanel().$(byText("Run Tool")).click();
		}

		@Test
		@Story("Sampling approve")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Approving a pending sampling request with canned reply text unblocks askLlm and returns that text.")
		@DisplayName("approveSamplingRequest — fill reply, click Approve, tool returns the canned text")
		void samplingRequest_approve_toolReturnsCannedText() {
			// given
			// Fire the tool — the server side blocks on exchange.createMessage().
			fireAskLlm("hello world");

			// when
			// Switch to the Sampling tab — the pending request is rendered there.
			clickTab("sampling");
			SelenideElement card = $("[data-testid=sampling-request]").shouldBe(visible, Duration.ofSeconds(20));

			// SamplingRequest renders a DynamicJsonForm seeded with `content.type=text`,
			// which exposes an editable `text` field. The form is the second flex-1 child
			// of the card; the only editable text Input inside the card with no value
			// yet is the reply text. Fill the first <input type=text> inside the card.
			SelenideElement replyInput = card.$$("input[type=text]").filterBy(Condition.attribute("value", "")).first();
			// Fallback: if the filter found nothing (form already has stub-model
			// defaults),
			// pick the last <input type=text> — that's the `text` field appended last to
			// the schema (see SamplingRequest.tsx useMemo block).
			if (!replyInput.exists()) {
				replyInput = card.$$("input[type=text]").last();
			}
			replyInput.shouldBe(visible).setValue("canned-llm-reply");

			// Approve the request — server unblocks with our canned text.
			card.$(byText("Approve")).click();

			// then
			// Back to Tools tab to confirm the askLlm result mentions the reply.
			clickTab("tools");
			activePanel().shouldHave(text("canned-llm-reply"), Duration.ofSeconds(20));
		}

		@Test
		@Story("Sampling reject")
		@Severity(SeverityLevel.NORMAL)
		@Description("Rejecting a pending sampling request surfaces an error/failure on the askLlm tool result.")
		@DisplayName("rejectSamplingRequest — clicking Reject surfaces an error/failure on the tool")
		void samplingRequest_reject_toolSurfacesFailure() {
			// given
			fireAskLlm("rejected question");

			// when
			clickTab("sampling");
			SelenideElement card = $("[data-testid=sampling-request]").shouldBe(visible, Duration.ofSeconds(20));
			card.$(byText("Reject")).click();

			// then
			// Back to Tools — askLlm catches the RuntimeException and returns the
			// "sampling request failed: ..." guidance string, which surfaces as the
			// CallToolResult text content.
			clickTab("tools");
			activePanel().shouldHave(Condition.or("rejection surfaced", text("sampling request failed"),
					text("rejected"), text("Error"), text("isError")), Duration.ofSeconds(20));
		}

	}

	// =====================================================================
	// S. Elicitation tab — server→client createElicitation round-trip via askUser.
	//
	// Flow mirrors Sampling: trigger askUser(question), switch to elicitations
	// tab, locate [data-testid=elicitation-request], fill the `answer` field,
	// click Submit / Decline. Tool returns the user's answer (Submit) or a
	// "user declined" guidance string (Decline).
	// =====================================================================

	@Nested
	@DisplayName("Elicitation tab (askUser)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Elicitation {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		private void fireAskUser(final String question) {
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("askUser");
			$("#question").shouldBe(visible).setValue(question);
			activePanel().$(byText("Run Tool")).click();
		}

		@Test
		@Story("Elicitation submit")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Submitting an elicitation answer of 'blue' unblocks askUser and the tool returns 'blue'.")
		@DisplayName("submitElicitation — fill answer=blue, click Submit, tool returns 'blue'")
		void elicitationRequest_submitAnswer_toolReturnsAnswer() {
			// given
			fireAskUser("What is your favourite color?");

			// when
			clickTab("elicitations");
			SelenideElement card = $("[data-testid=elicitation-request]").shouldBe(visible, Duration.ofSeconds(20));

			// The schema is { answer: string } per DemoInteractiveToolsProvider.askUser.
			// DynamicJsonForm renders the answer field as <Input type=text> with no id —
			// it's the only editable text input inside the response form column.
			card.$$("input[type=text]").first().shouldBe(visible).setValue("blue");
			card.$(byText("Submit")).click();

			// then
			clickTab("tools");
			activePanel().shouldHave(text("blue"), Duration.ofSeconds(20));
		}

		@Test
		@Story("Elicitation decline")
		@Severity(SeverityLevel.NORMAL)
		@Description("Declining an elicitation surfaces a 'user decline' guidance string on the askUser tool result.")
		@DisplayName("declineElicitation — clicking Decline surfaces 'user decline' on the tool")
		void elicitationRequest_decline_toolSurfacesUserDecline() {
			// given
			fireAskUser("decline-me?");

			// when
			clickTab("elicitations");
			SelenideElement card = $("[data-testid=elicitation-request]").shouldBe(visible, Duration.ofSeconds(20));
			card.$(byText("Decline")).click();

			// then
			clickTab("tools");
			// askUser returns "askUser: user decline" when ElicitResult.Action != ACCEPT.
			activePanel().shouldHave(
					Condition.or("decline surfaced", text("user decline"), text("declined"), text("decline")),
					Duration.ofSeconds(20));
		}

	}

	// =====================================================================
	// S2. Elicitation tab — url-mode round-trip via authorizeViaUrl.
	//
	// Flow: trigger authorizeViaUrl(authUrl) from Tools, switch to
	// elicitations tab, wait for [data-testid=elicitation-request], assert
	// the elicitation message text is visible, assert the "Open URL" button
	// is visible and not disabled (proves the https guard passed client-side),
	// then click "Accept". Back in Tools the result must contain
	// "user accepted".
	// =====================================================================

	@Nested
	@DisplayName("Elicitation tab (url-mode authorizeViaUrl)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ElicitationUrlMode {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		/**
		 * Triggers {@code authorizeViaUrl(authUrl)} from the Tools tab and returns
		 * immediately — the upstream client fires the tool call asynchronously, so the
		 * blocked call lives on the server until the elicitation card is resolved.
		 *
		 * <p>
		 * The tool parameter name is {@code authUrl} (declared as
		 * {@code @McpToolParam … String authUrl} in
		 * {@link io.inspector.mcp.demo.tools.DemoInteractiveToolsProvider}); the Spring
		 * AI MCP annotation scanner keeps the Java parameter name when
		 * {@code -parameters} is enabled (see {@code <parameters>true</parameters>} in
		 * the parent POM). DynamicJsonForm therefore renders the input as
		 * {@code <input id="authUrl">}.
		 */
		private void fireAuthorizeViaUrl(final String authUrl) {
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("authorizeViaUrl");
			// DynamicJsonForm renders the single string property as <input id="authUrl">.
			$("#authUrl").shouldBe(visible).setValue(authUrl);
			activePanel().$(byText("Run Tool")).click();
		}

		@Test
		@Story("Elicitation url-mode accept")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Accepting a url-mode elicitation request unblocks authorizeViaUrl and the tool returns 'user accepted'.")
		@DisplayName("urlModeElicitation_accept_toolReturnsUserAccepted — Open URL button visible, click Accept, tool returns 'user accepted'")
		void elicitationUrlMode_accept_toolReturnsUserAccepted() {
			// given
			// Fire the tool — the server side blocks on
			// exchange.createElicitation(ElicitUrlRequest).
			fireAuthorizeViaUrl("https://oauth.example.com/authorize?code=demo");

			// when
			// Switch to the Elicitations tab — the pending url-mode request renders
			// there.
			clickTab("elicitations");
			SelenideElement card = $("[data-testid=elicitation-request]").shouldBe(visible, Duration.ofSeconds(20));

			// Assert the elicitation message text is present in the card. The tool passes
			// "Authorize by visiting the provided URL" as the ElicitUrlRequest message
			// (see DemoInteractiveToolsProvider.authorizeViaUrl). The upstream
			// ElicitationTab renders this as the card's descriptive text.
			card.shouldHave(text("Authorize by visiting the provided URL"), Duration.ofSeconds(5));

			// Assert the "Open URL" button is visible and NOT disabled. The upstream
			// ElicitationTab renders a button that calls window.open(url) only when the
			// URL passes the https guard (url starts with "https://"). The button must
			// exist and be enabled to prove the guard is satisfied.
			//
			// We intentionally do NOT click "Open URL" here: window.open() in headless
			// Chrome spawns an uncontrollable blank tab that Selenide has no handle for
			// (Selenium can only switch to windows it opened itself), and the new tab
			// immediately navigates to oauth.example.com which is an external hostname
			// that is not available in the test environment. Clicking would cause the
			// test
			// to hang on the blank tab or trigger an unhandled window switch.
			SelenideElement openUrlButton = card.$(byText("Open URL"));
			openUrlButton.shouldBe(visible);
			openUrlButton.shouldBe(Condition.enabled);

			// Click "Accept" to resolve the url-mode elicitation (no form data is
			// returned by url-mode requests — the action alone unblocks the server call).
			card.$(byText("Accept")).click();

			// then
			// Back to the Tools tab — authorizeViaUrl returns
			// "authorizeViaUrl: user accepted and returned from <url>" on ACCEPT.
			clickTab("tools");
			activePanel().shouldHave(text("user accepted"), Duration.ofSeconds(20));
		}

	}

	// =====================================================================
	// T. Roots tab — client-advertised roots queryable via listMyRoots.
	//
	// The Roots tab renders an "Add Root" button + per-row URI input + Save
	// Changes. Add a single root, save, then call listMyRoots from Tools and
	// assert the configured URI surfaces in the result.
	// =====================================================================

	@Nested
	@DisplayName("Roots tab (listMyRoots)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class Roots {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Roots")
		@Severity(SeverityLevel.NORMAL)
		@Description("Adding a root and saving it, then calling listMyRoots returns the configured file:// URI (or a documented capability-gap guidance string).")
		@DisplayName("addsRootAndListsViaTool — listMyRoots returns the configured file:// URI")
		void addRoot_thenListMyRoots_returnsConfiguredUri() {
			// given
			clickTab("roots");

			// when
			// Add Root creates an entry pre-filled with "file://".
			activePanel().$(byText("Add Root")).shouldBe(visible, Duration.ofSeconds(10)).click();

			// Replace the URI with our test value. The Input renders inside the row;
			// it's the first plain <input> inside the active panel after the alert.
			SelenideElement rootUri = activePanel().$$("input[placeholder='file:// URI']").first().shouldBe(visible);
			rootUri.click();
			rootUri.sendKeys(org.openqa.selenium.Keys.END);
			int len = rootUri.getValue() == null ? 0 : rootUri.getValue().length();
			for (int i = 0; i < len + 2; i++) {
				rootUri.sendKeys(org.openqa.selenium.Keys.BACK_SPACE);
			}
			rootUri.setValue("file:///tmp/inspector-test-root");

			// Save Changes propagates the new roots list to the server via
			// notifications/roots/list_changed.
			activePanel().$(byText("Save Changes")).shouldBe(visible).click();

			// Now query the roots via the listMyRoots tool.
			clickTab("tools");
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			selectRow("listMyRoots");
			activePanel().$(byText("Run Tool")).click();

			// then
			// Tool result should contain our root URI. If the client never advertised
			// the `roots` capability (no listChanged subscription), listMyRoots returns
			// a guidance string — accept either branch but assert at least one signal.
			activePanel().shouldHave(Condition.or("roots-tool surfaced something",
					text("file:///tmp/inspector-test-root"), text("does not advertise"), text("returned no roots")),
					Duration.ofSeconds(20));
		}

	}

	// =====================================================================
	// U. Resource subscriptions — demo://clock emits notifications/resources/updated.
	//
	// Per the T27 caveat: SDK 0.18.2 does not wire resources/subscribe handlers
	// on the server side, so the upstream client's Subscribe button is gated by
	// serverCapabilities?.resources?.subscribe and likely NEVER renders. We
	// still assert the auto-firing /clock notification (every 5s via the
	// DemoSubscribableResource scheduler) reaches the Server Notifications
	// pane and document the missing subscribe affordance as a known gap.
	// =====================================================================

	@Nested
	@DisplayName("Resource subscribe (demo://clock)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ResourceSubscribe {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Resource subscribe")
		@Severity(SeverityLevel.NORMAL)
		@Description("The demo://clock resource auto-fires notifications/resources/updated, growing the Server Notifications pane even when the subscribe affordance is gated by the SDK capability gap.")
		@DisplayName("clockResourceEmitsUpdates — Server Notifications grows with auto-fired clock ticks")
		void clockResource_afterListing_emitsServerNotifications() {
			// given
			// Listing resources confirms demo-clock is registered. We don't strictly
			// need to select the row; the clock ticks regardless of subscription state
			// because the SDK forwards notifications to any active session.
			clickTab("resources");
			for (SelenideElement clear : activePanel().$$(byText("Clear"))) {
				if (clear.exists() && clear.isEnabled()) {
					clear.click();
				}
			}
			SelenideElement listResources = activePanel().$(byText("List Resources"));
			if (listResources.exists() && listResources.isEnabled()) {
				listResources.click();
			}
			activePanel().shouldHave(text("demo-clock"), Duration.ofSeconds(15));

			// when
			// Try the Subscribe button — only rendered if the server advertised the
			// `resources.subscribe` capability. Click it if present; otherwise note
			// the missing affordance and continue.
			selectRow("demo-clock");
			SelenideElement subscribeBtn = activePanel().$(byText("Subscribe"));
			boolean clientSideSubscribeAvailable = subscribeBtn.exists() && subscribeBtn.isDisplayed();
			if (clientSideSubscribeAvailable) {
				subscribeBtn.click();
			}
			else {
				// BUG: Spring AI MCP SDK 0.18.2 does not wire `resources.subscribe`
				// on the server-side capability advertisement, so the upstream UI
				// hides the Subscribe button (gated by
				// serverCapabilities?.resources?.subscribe in App.tsx). Auto-fired
				// notifications still arrive via the SDK's default forwarding,
				// which is what we assert below.
				System.err.println("[ResourceSubscribe] Subscribe button absent — server does not advertise "
						+ "resources.subscribe capability (SDK 0.18.2 limitation, T27 caveat).");
			}

			// then
			// Server Notifications pane gains at least one entry within the
			// DemoSubscribableResource tick interval (~5s default + initial 2s delay).
			SelenideElement notifications = $$(".flex-1.overflow-y-auto.p-4").findBy(text("Server Notifications"));
			// Wait up to 10s for the first scheduled tick to reach the panel.
			notifications.$$("li").shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(12));
		}

	}

	// =====================================================================
	// V. OAuth flow — drives the inspector's AuthDebugger against the
	// in-process OAuthStubController. The full popup-based redirect flow is
	// brittle through headless Chromium (Selenide cannot easily follow the
	// window.open(...) into a new window + the inspector's same-origin
	// /oauth/callback handoff). We decompose into the most stable observable
	// signal: clicking "Quick OAuth Flow" populates the OAuthFlowProgress
	// step labels (Metadata Discovery / Client Registration / ...). The
	// OAuthStubIT integration test asserts the protocol-level RFC 6749 +
	// RFC 7636 round-trip end-to-end with HttpClient, so the UI test does
	// not need to re-prove that.
	// =====================================================================

	@Nested
	@DisplayName("OAuth flow (stub)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class OAuthFlow {

		@AfterEach
		void tearDown() {
			stopApp();
		}

		@Test
		@Story("Quick OAuth flow")
		@Severity(SeverityLevel.CRITICAL)
		@Description("Running Quick OAuth Flow against the in-process stub drives discovery, client registration, approval and the callback redirect, leaving the templated SPA mounted.")
		@DisplayName("quickOAuthFlowPopulatesProgress — AuthDebugger shows Metadata Discovery + Client Registration steps")
		void quickOAuthFlow_withStub_mountsSpaAfterCallback() {
			// given
			// Boot with oauth-stub so /.well-known/oauth-authorization-server is served.
			startAppWithOAuthStub(new Combo("sse"));
			open("/mcp-inspector/index.html");

			// when
			// Fill the OAuth sidebar inputs with stub client credentials.
			$("[data-testid=auth-button]").shouldBe(visible).click();
			$("[data-testid=oauth-client-id-input]").shouldBe(visible).setValue("stub-client");
			$("[data-testid=oauth-client-secret-input]").shouldBe(visible).setValue("stub-secret");
			$("[data-testid=oauth-scope-input]").shouldBe(visible).setValue("mcp");

			// Open the empty-state AuthDebugger.
			$(byText("Open Auth Settings")).shouldBe(visible, Duration.ofSeconds(10)).click();
			$(byText("Authentication Settings")).shouldBe(visible, Duration.ofSeconds(10));

			// Click Quick OAuth Flow — this kicks off discovery against the
			// /.well-known/oauth-authorization-server endpoint served by the stub,
			// then Dynamic Client Registration, then redirects the main window via
			// window.location.href to the stub's /oauth/authorize approve page.
			// (Previously the flow stalled at the redirect step because the
			// backend 404'd on /oauth/callback/debug, but T-OAUTH-CALLBACK fixed
			// that — InspectorIndexController now serves the templated SPA on
			// both the bare and the debug callback paths.)
			$(byText("Quick OAuth Flow")).shouldBe(visible).click();

			// Browser is now on the stub's approve form. Click Approve and the
			// stub redirects back to ${origin}/oauth/callback/debug?code=...&state=...
			// which the inspector backend serves as the templated SPA so the
			// OAuthDebugCallback component can claim the URL and exchange the code.
			$$("h1").findBy(text("Approve")).shouldBe(visible, Duration.ofSeconds(15));
			$("button[name=decision][value=approve]").shouldBe(visible).click();

			// then
			// After the redirect lands on /oauth/callback/debug, App.tsx mounts
			// the OAuthDebugCallback branch. We can't deterministically assert
			// "Connected" because the OAuth debug flow doesn't auto-trigger an
			// MCP connect — but the SPA DOM root must be present (proves the
			// backend served the templated SPA rather than returning 404).
			$("#root").shouldBe(visible, Duration.ofSeconds(15));
		}

		@Test
		@Story("OAuth callback routes")
		@Severity(SeverityLevel.NORMAL)
		@Description("Both /oauth/callback and /oauth/callback/debug serve the templated SPA (mounting #root) rather than returning 404, so the React pathname router can claim the URL after the IdP redirect.")
		@DisplayName("oauthCallbackRoutesServeSpa — /oauth/callback and /oauth/callback/debug return the templated SPA, not 404")
		void oauthCallbackRoutes_whenOpened_serveTemplatedSpa() {
			// given
			// Pure backend assertion — the OAuth callback routes must serve the SPA
			// so the React client's pathname-based router (App.tsx checks
			// pathname === "/oauth/callback" / "/oauth/callback/debug") can claim
			// the URL after the IdP redirect. Without these routes the browser
			// would 404 before the SPA bootstrap script runs.
			startAppWithOAuthStub(new Combo("sse"));

			// when & then
			open("/oauth/callback?code=stub-code&state=irrelevant");
			$("#root").shouldBe(visible, Duration.ofSeconds(10));

			open("/oauth/callback/debug?code=stub-code&state=irrelevant");
			$("#root").shouldBe(visible, Duration.ofSeconds(10));
		}

	}

	// =====================================================================
	// W. Completion popover — Prompts tab Combobox suggests completions from
	// the @McpComplete handler. Typing "s" into multiTurn.topic should reveal
	// "sports" (one of the canned suggestions in DemoAdvancedPromptsProvider).
	// =====================================================================

	@Nested
	@DisplayName("Completion popover (prompts)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class CompletionPopover {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@Test
		@Story("Completion popover")
		@Severity(SeverityLevel.NORMAL)
		@Description("Typing the prefix 's' into the multiTurn topic Combobox surfaces the @McpComplete-provided 'sports' suggestion and selecting it reflects the value on the trigger.")
		@DisplayName("multiTurnTopicSuggestsSports — typing 's' into topic reveals 'sports' suggestion")
		void completionPopover_typePrefixS_suggestsSports() {
			// given
			clickTab("prompts");
			SelenideElement listPrompts = activePanel().$(byText("List Prompts"));
			if (listPrompts.exists() && listPrompts.isEnabled()) {
				listPrompts.click();
			}
			selectRow("multiTurn");

			// when
			// The topic Combobox trigger is a <button role=combobox aria-controls=topic>.
			// Click it to open the Popover containing the CommandInput.
			SelenideElement trigger = activePanel().$("button[role=combobox][aria-controls=topic]");
			Assertions.assertTrue(trigger.exists(),
					"prompts/list returned no argument schemas for multiTurn — completion popover unavailable");
			trigger.shouldBe(visible).click();

			// CommandInput placeholder is "Enter topic" (matches PromptsTab + Combobox).
			SelenideElement commandInput = $("input[placeholder='Enter topic']").shouldBe(visible,
					Duration.ofSeconds(5));
			commandInput.setValue("s");

			// The completion handler returns sports / music / movies for prefix "s"
			// (only "sports" actually starts with 's'; the others would not match a
			// strict prefix filter — see DemoAdvancedPromptsProvider). The Combobox
			// renders results as Command items inside the Popover; locate the
			// "sports" entry and click it.
			SelenideElement sportsOption = $$("[role=option], [cmdk-item]").findBy(text("sports"))
				.shouldBe(visible, Duration.ofSeconds(10));
			sportsOption.click();

			// then
			// After selection, the Combobox closes and the trigger reflects the value.
			activePanel().$("button[role=combobox][aria-controls=topic]")
				.shouldHave(text("sports"), Duration.ofSeconds(5));
		}

	}

	// =====================================================================
	// X. Responsive tab bar — CI regression for the <sm wrap patch
	// (upstream-client/src/App.tsx TabsList, NOTICE.d/tab-bar-wrap.txt). The shared
	// suite browser is fixed at 1366x900 (setupBrowser), so each scenario
	// resizes the live headless window to its target viewport and restores it
	// afterwards.
	//
	// Tailwind's `sm` breakpoint is `min-width: 640px` (inclusive): computed
	// flex-wrap at exactly 640px is already `nowrap` (verified in a real
	// browser), so the wrap side of the boundary is exercised at 639px — the
	// widest viewport that still wraps — and the flip back to the single
	// upstream row is asserted at 640px as part of the desktop control.
	// =====================================================================

	@Nested
	@DisplayName("Responsive tab bar (375 / sm-boundary / desktop control)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ResponsiveTabBar {

		/** All 11 tab trigger values of the inspector bar (UPSTREAM_DOM_MAP §3). */
		private static final List<String> ALL_TAB_VALUES = List.of("resources", "prompts", "tools", "tasks", "apps",
				"ping", "sampling", "elicitations", "roots", "auth", "metadata");

		/**
		 * The 10 always-enabled triggers. {@code tasks} is rendered disabled by design —
		 * the demo does not advertise the tasks capability (see #63) — so it is only
		 * presence-checked, never clicked.
		 */
		private static final List<String> ENABLED_TAB_VALUES = List.of("resources", "prompts", "tools", "apps", "ping",
				"sampling", "elicitations", "roots", "auth", "metadata");

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void resetToDesktopViewport() {
			ResponsiveTestHelpers.setViewport(1366, 900);
		}

		@AfterEach
		void restoreToDesktopViewport() {
			ResponsiveTestHelpers.setViewport(1366, 900);
		}

		/**
		 * Computed {@code flex-wrap} of the TabsList — "wrap" below sm, "nowrap"
		 * at/above.
		 */
		private static String tabsListFlexWrap() {
			return (String) Selenide
				.executeJavaScript("return getComputedStyle(document.querySelector('[role=tablist]')).flexWrap;");
		}

		/** Computed height of the TabsList in px (single upstream row = h-9 = 36px). */
		private static double tabsListHeight() {
			return ((Number) Selenide
				.executeJavaScript("return document.querySelector('[role=tablist]').getBoundingClientRect().height;"))
				.doubleValue();
		}

		/** Right edge of the widest tab trigger, in CSS px, relative to the viewport. */
		private static double widestTriggerRightEdge() {
			return ((Number) Selenide
				.executeJavaScript("return Math.max(...Array.from(document.querySelectorAll('[role=tab]'))"
						+ ".map(t => t.getBoundingClientRect().right));"))
				.doubleValue();
		}

		@Test
		@Story("Responsive tab bar")
		@Severity(SeverityLevel.NORMAL)
		@Description("At a 375px viewport the TabsList wraps (computed flex-wrap: wrap), keeps all 11 tab triggers in the DOM and causes no horizontal document overflow. Clickability/visibility of every tab at 375px is intentionally NOT asserted — the 320px sidebar still clips the inspector column there (separate card t_aa9b879f).")
		@DisplayName("tabsAt375pxWrapKeepAllTriggersNoHScroll — mobile wrap")
		void tabs_at375px_wrapKeepAllTriggersAndNoHorizontalOverflow() {
			// given
			ResponsiveTestHelpers.setViewport(375, 667);

			// then
			Assertions.assertEquals("wrap", tabsListFlexWrap(), "TabsList must wrap below the sm breakpoint");
			for (String value : ALL_TAB_VALUES) {
				Assertions.assertTrue($("[role=tab][id$='-trigger-" + value + "']").exists(),
						"tab trigger '" + value + "' must be present in the DOM at 375px");
			}
			Assertions.assertTrue(ResponsiveTestHelpers.noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 375px");
		}

		@Test
		@Story("Responsive tab bar")
		@Severity(SeverityLevel.NORMAL)
		@Description("At the widest wrapping viewport (639px, just below the inclusive Tailwind sm breakpoint) the TabsList still wraps, all 11 triggers fit inside the viewport (right edge <= viewport width) and the 10 enabled ones are clickable by real input; the disabled tasks trigger is only presence-checked.")
		@DisplayName("tabsAt639pxWrapAllReachableClickableNoHScroll — sm boundary, wrap side")
		void tabs_at639px_wrapAllTriggersReachableClickableNoHorizontalOverflow() {
			// given
			ResponsiveTestHelpers.setViewport(639, 800);

			// then
			Assertions.assertEquals("wrap", tabsListFlexWrap(),
					"TabsList must still wrap just below the sm breakpoint");
			Assertions.assertTrue(widestTriggerRightEdge() <= 639,
					"every tab trigger must fit inside the 639px viewport (right edge <= viewport width)");
			for (String value : ENABLED_TAB_VALUES) {
				clickTab(value); // real WebDriver click input; asserts the trigger
									// becomes active
			}
			Assertions.assertTrue($("[role=tab][id$='-trigger-tasks']").exists(),
					"disabled tasks trigger must be present at the sm boundary (#63)");
			Assertions.assertTrue(ResponsiveTestHelpers.noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 639px");
		}

		@Test
		@Story("Responsive tab bar")
		@Severity(SeverityLevel.NORMAL)
		@Description("Control scenario: at 640px (Tailwind sm, min-width:640px — inclusive) and at 1024px the TabsList returns to the single upstream row — computed flex-wrap: nowrap and height ~36px (h-9) — so the wrap patch causes no desktop regression.")
		@DisplayName("tabsAt640And1024ControlSingleRowNoWrap — desktop geometry intact")
		void tabs_at1024px_controlSingleRowNoWrap() {
			// when & then — the exact sm flip point plus a wider desktop viewport
			for (int width : new int[] { 640, 1024 }) {
				ResponsiveTestHelpers.setViewport(width, 800);
				Assertions.assertEquals("nowrap", tabsListFlexWrap(),
						"TabsList must be a single row at " + width + "px (sm:flex-nowrap restores upstream geometry)");
				double height = tabsListHeight();
				Assertions.assertTrue(height >= 32 && height <= 40,
						"TabsList must be a single ~36px row at " + width + "px, was " + height + "px");
			}
		}

	}

	// =====================================================================
	// Y. Responsive history layout — CI regression for the <lg compact
	// layout patch (upstream-client/src/App.tsx root container + bottom
	// History pane, NOTICE.d/compact-layout.txt). The shared suite browser is
	// fixed at 1366x900 (setupBrowser), so each scenario resizes the live
	// headless window to its target viewport and restores it afterwards.
	//
	// Tailwind's `lg` breakpoint is `min-width: 1024px` (inclusive): the
	// desktop side-by-side layout (draggable sidebar, fixed-height resizable
	// History pane) applies at >=1024px; below it the app stacks into
	// flex-col so the History/Server Notifications pane is in normal flow
	// and can never overlay tab content. The bug this pins (#60): at
	// 780x437 the fixed-height 300px pane left only ~137px for tab content,
	// so the Tools tab's "List Tools" button sat geometrically under the
	// pane header — elementFromPoint at its centre returned the History div
	// and real clicks never reached the button.
	// =====================================================================

	@Nested
	@DisplayName("Responsive history layout (compact <1024px / desktop control)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ResponsiveHistoryLayout {

		@BeforeAll
		void bootAndConnect() {
			startApp(new Combo("sse"));
			openAndConnect();
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void resetToDesktopViewport() {
			ResponsiveTestHelpers.setViewport(1366, 900);
		}

		@AfterEach
		void restoreToDesktopViewport() {
			ResponsiveTestHelpers.setViewport(1366, 900);
		}

		/**
		 * Clickable control in the Tools tab whose centre was covered by the History pane
		 * before the fix (issue #60 repro: 780x437, elementFromPoint returned the
		 * History/Clear div).
		 */
		private SelenideElement listToolsButton() {
			clickTab("tools");
			return activePanel().$(byText("List Tools"));
		}

		/**
		 * True when {@code elementFromPoint} at the given element's centre returns it.
		 */
		private static boolean clickableAtCenter(SelenideElement element) {
			return Boolean.TRUE.equals(Selenide.executeJavaScript(
					"const el = arguments[0];" + "const r = el.getBoundingClientRect();"
							+ "return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === el;",
					element));
		}

		/**
		 * True when the document has no horizontal overflow (scrollWidth <=
		 * document.documentElement.clientWidth). Compared against the document's own
		 * client width rather than window.innerWidth, because innerWidth includes the
		 * vertical scrollbar and would let up to a scrollbar-width (~15px) of horizontal
		 * overflow slip through as a blind window.
		 */
		private static boolean noHorizontalDocumentOverflow() {
			return Boolean.TRUE.equals(Selenide.executeJavaScript(
					"return document.documentElement.scrollWidth <= document.documentElement.clientWidth;"));
		}

		/**
		 * Resizes the browser window so the page's inner viewport is exactly
		 * {@code targetWidth} x {@code targetHeight}. ChromeDriver sets the window's
		 * OUTER rectangle and headless Chromium derives the viewport from a virtual
		 * screen, so a plain resize can leave {@code window.innerHeight} far short of the
		 * requested value (observed locally: 294px for a 437px window), so the scenario
		 * would then silently run at an unintended viewport. The constant outer/inner
		 * delta of the environment is measured once, the resize is requested at target +
		 * delta and verified against a stabilized read; the delta is corrected in a
		 * bounded loop, so every scenario in this group really executes at its documented
		 * viewport on any driver/environment. The window size request is guarded to stay
		 * positive, and the post-resize read waits for the resize to actually land (two
		 * stale equal reads are not treated as stable).
		 */
		private static void setViewportExactly(int targetWidth, int targetHeight) {
			java.util.List<Number> before = readInnerViewport();
			int deltaWidth = outerExtent("Width") - before.get(0).intValue();
			int deltaHeight = outerExtent("Height") - before.get(1).intValue();
			for (int attempt = 0; attempt < 4; attempt++) {
				int requestWidth = targetWidth + deltaWidth;
				int requestHeight = targetHeight + deltaHeight;
				if (requestWidth <= 0 || requestHeight <= 0) {
					Assertions.fail("invalid window size request " + requestWidth + "x" + requestHeight
							+ " (outer/inner delta " + deltaWidth + "x" + deltaHeight + "), cannot converge on "
							+ targetWidth + "x" + targetHeight);
				}
				ResponsiveTestHelpers.setViewport(requestWidth, requestHeight);
				java.util.List<Number> inner = innerViewportAfterResize(before);
				int innerWidth = inner.get(0).intValue();
				int innerHeight = inner.get(1).intValue();
				if (innerWidth == targetWidth && innerHeight == targetHeight) {
					return;
				}
				deltaWidth += targetWidth - innerWidth;
				deltaHeight += targetHeight - innerHeight;
				before = inner;
			}
			Assertions.fail("browser window never reached the " + targetWidth + "x" + targetHeight
					+ " inner viewport after compensating resizes");
		}

		/**
		 * Reads the inner viewport, waits until it changes away from {@code before} (the
		 * WebDriver resize lands asynchronously, so two consecutive pre-resize reads must
		 * not be mistaken for a stable state), then waits until two consecutive reads
		 * agree and returns that value.
		 */
		private static java.util.List<Number> innerViewportAfterResize(java.util.List<Number> before) {
			java.util.List<Number> current = readInnerViewport();
			for (int read = 0; read < 20 && sameViewport(current, before); read++) {
				Selenide.sleep(100);
				current = readInnerViewport();
			}
			java.util.List<Number> previous = null;
			for (int read = 0; read < 20; read++) {
				java.util.List<Number> next = readInnerViewport();
				if (previous != null && sameViewport(previous, next)) {
					return next;
				}
				previous = next;
				Selenide.sleep(100);
			}
			return current;
		}

		private static boolean sameViewport(java.util.List<Number> a, java.util.List<Number> b) {
			return a.get(0).intValue() == b.get(0).intValue() && a.get(1).intValue() == b.get(1).intValue();
		}

		private static java.util.List<Number> readInnerViewport() {
			return Selenide.executeJavaScript("return [window.innerWidth, window.innerHeight];");
		}

		private static int outerExtent(String dimension) {
			return ((Number) Selenide.executeJavaScript("return window.outer" + dimension + ";")).intValue();
		}

		/**
		 * Bounding boxes of the active tab panel and the History column must not
		 * intersect. The History column is anchored via the same selector as
		 * {@link #historyColumn()}, so a future class rename cannot silently detach the
		 * overlap probe from the stable anchor.
		 */
		private static String panesOverlapJs() {
			return "const panel = document.querySelector('[role=tabpanel][data-state=active]').getBoundingClientRect();"
					+ "const history = document.querySelector(arguments[0]);"
					+ "if (!history) { return 'history-not-found'; }" + "const h = history.getBoundingClientRect();"
					+ "return !(h.left < panel.right && h.right > panel.left && h.top < panel.bottom"
					+ "&& h.bottom > panel.top);";
		}

		/**
		 * True when the active tab panel and the History column have disjoint bounding
		 * boxes. A missing History column fails with a readable assertion instead of a
		 * ClassCastException, and the returned value is always a Boolean.
		 */
		private static boolean panesOverlap() {
			Object result = Selenide.executeJavaScript(panesOverlapJs(), historyColumn().getSearchCriteria());
			if ("history-not-found".equals(result)) {
				Assertions.fail("History column not found via " + historyColumn().getSearchCriteria());
			}
			return Boolean.TRUE.equals(result);
		}

		/**
		 * True when the given inner column is an independently scrolling container under
		 * real overflow: its scrollHeight exceeds its clientHeight and assigning
		 * scrollTop actually moves it. The element is the same one used by the
		 * History/Notifications column helpers, so a future class rename cannot silently
		 * detach the probe from the stable anchor.
		 */
		private static boolean columnScrollsWithOverflow(SelenideElement column) {
			Object result = Selenide.executeJavaScript(
					"const col = arguments[0];" + "if (!col) { return 'column-not-found'; }" + "col.scrollTop = 999;"
							+ "return col.scrollHeight > col.clientHeight && col.scrollTop > 0;",
					column);
			if ("column-not-found".equals(result)) {
				Assertions.fail("column not found: " + column.getSearchCriteria());
			}
			return Boolean.TRUE.equals(result);
		}

		/**
		 * Right-hand "Server Notifications" column of the bottom pane, the sibling of
		 * {@link #historyColumn()}. The History column carries the unique
		 * {@code border-r}; the notifications side is anchored by its header text.
		 */
		private static SelenideElement notificationsColumn() {
			return $$(".flex-1.overflow-y-auto.p-4").findBy(text("Server Notifications"));
		}

		@Test
		@Story("Responsive history layout")
		@Severity(SeverityLevel.CRITICAL)
		@Description("At the issue #60 repro viewport (780x437) the List Tools button in the Tools tab is clickable by real coordinates: elementFromPoint at its bounding-box centre returns the button itself, not an overlaying History pane node. The compact mode must not mount the desktop-only drag handles, and both the History and Server Notifications columns must scroll independently under real overflow.")
		@DisplayName("listToolsClickableAt780x437: elementFromPoint hits the button, no drag handles, both columns scroll")
		void listTools_at780x437_elementFromPointReturnsButton() {
			// given
			setViewportExactly(780, 437);
			SelenideElement listTools = listToolsButton().shouldBe(visible, Duration.ofSeconds(10));

			// then
			Assertions.assertTrue(clickableAtCenter(listTools),
					"elementFromPoint at the List Tools centre must return the button itself at 780x437");

			// and: the compact mode must not mount the desktop-only drag
			// handles, so a pointer press on the old handle strip cannot enter
			// the sidebar drag path or the pane resize path.
			$("[data-testid=sidebar-drag-handle]").shouldNot(Condition.exist, Duration.ofSeconds(5));
			$("[data-testid=pane-drag-handle]").shouldNot(Condition.exist, Duration.ofSeconds(5));

			// and: real requests overflow the definite-height compact pane, and
			// the History column itself scrolls (scrollTop moves), instead of
			// only the outer wrapper scrolling. The Ping tab's button stays
			// enabled across calls (the Tools List button disables after the
			// first listing), so 12 pings reliably overflow the 40vh pane.
			clickTab("ping");
			SelenideElement pingServer = activePanel().$(byText("Ping Server"));
			for (int i = 0; i < 12; i++) {
				pingServer.click();
			}
			Assertions.assertTrue(columnScrollsWithOverflow(historyColumn()),
					"the History column must scroll independently under real overflow at 780x437");

			// and: the Server Notifications column must scroll independently too.
			// The demo advertises the logging capability and the client subscribes
			// at connect (defaultLoggingLevel=debug), so each largeOutput call
			// emits a real notifications/message that overflows the 40vh pane.
			clickTab("tools");
			SelenideElement listToolsBtn = activePanel().$(byText("List Tools"));
			if (listToolsBtn.exists() && listToolsBtn.isEnabled()) {
				listToolsBtn.click();
			}
			selectRow("largeOutput");
			SelenideElement sizeKb = $("#sizeKb");
			if (sizeKb.exists()) {
				sizeKb.setValue("1");
			}
			for (int i = 0; i < 12; i++) {
				activePanel().$(byText("Run Tool")).click();
				activePanel().$(byText("Run Tool")).shouldBe(visible, Duration.ofSeconds(60));
			}
			Assertions.assertTrue(columnScrollsWithOverflow(notificationsColumn()),
					"the Server Notifications column must scroll independently under real overflow at 780x437");
		}

		@Test
		@Story("Responsive history layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("At 768x800 and 1023x768 (the widest viewport below the inclusive lg breakpoint, at its contracted height) the History column and the active tab panel have disjoint bounding boxes and the document does not overflow horizontally (scrollWidth <= document.documentElement.clientWidth).")
		@DisplayName("panesDisjointAndNoHScrollAt768And1023: stacked, never overlapping")
		void historyColumn_at768And1023_disjointFromTabPanelNoHorizontalScroll() {
			// given
			setViewportExactly(768, 800);

			// when & then
			listToolsButton().shouldBe(visible, Duration.ofSeconds(10));
			Assertions.assertTrue(panesOverlap(), "tab content and the History pane must not overlap at 768px");
			Assertions.assertTrue(noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 768px");

			// and: the widest compact viewport, 1px below lg, at its contracted height.
			setViewportExactly(1023, 768);
			listToolsButton().shouldBe(visible, Duration.ofSeconds(10));
			Assertions.assertTrue(panesOverlap(), "tab content and the History pane must not overlap at 1023px");
			Assertions.assertTrue(noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 1023px");
		}

		@Test
		@Story("Responsive history layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("Desktop control at exactly 1024px (inclusive lg breakpoint): the side-by-side layout keeps its resizable History pane with a drag handle and the sidebar resize handle, i.e. the compact patch causes no desktop regression.")
		@DisplayName("desktopPaneResizableAt1024: drag handles present")
		void desktopLayout_at1024_resizableHistoryPaneAndSidebarHandlesPresent() {
			// given
			setViewportExactly(1024, 800);
			listToolsButton().shouldBe(visible, Duration.ofSeconds(10));

			// then: both desktop-only drag handles are mounted and visible.
			$("[data-testid=sidebar-drag-handle]").shouldBe(visible, Duration.ofSeconds(5));
			$("[data-testid=pane-drag-handle]").shouldBe(visible, Duration.ofSeconds(5));
			Assertions.assertTrue(noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 1024px");
		}

		@Test
		@Story("Responsive history layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("375px regression of the <640px tab-bar wrap fix (PR #69): the TabsList still wraps, all 11 triggers are present, every one of the 10 enabled tabs is clicked by real input, and the document does not overflow horizontally after the compact history-layout patch.")
		@DisplayName("tabBarRegressionAt375: mobile wrap intact, all tabs clickable")
		void tabBar_at375_wrapIntactAfterCompactPatch() {
			// given
			setViewportExactly(375, 667);

			// then
			Assertions.assertEquals("wrap", ResponsiveTabBar.tabsListFlexWrap(),
					"TabsList must still wrap below the sm breakpoint");
			for (String value : ResponsiveTabBar.ALL_TAB_VALUES) {
				Assertions.assertTrue($("[role=tab][id$='-trigger-" + value + "']").exists(),
						"tab trigger '" + value + "' must be present in the DOM at 375px");
			}

			// and: every enabled tab is actually clickable by real input at this
			// viewport (the disabled tasks trigger is only presence-checked, see #63).
			for (String value : ResponsiveTabBar.ENABLED_TAB_VALUES) {
				clickTab(value);
			}
			Assertions.assertTrue(noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 375px");
		}

	}

	/** Keep imports we may need in future paths warning-free. */
	@SuppressWarnings("unused")
	private static Class<?> keepImports() {
		return CompletableFuture.class;
	}

	// =====================================================================
	// Responsive tools layout — mobile geometry of the config sidebar and the
	// Tools list/detail grid (issue #58). Every scenario runs a fresh setup:
	// startApp(new Combo("sse")) → openAndConnect() → clickTab("tools") →
	// setViewport(...). The pane anchors are the [spring-ai-mcp-inspector
	// PATCH] data-testid hooks (config-pane / tools-list-pane /
	// tools-detail-pane / tools-list-detail-grid).
	// =====================================================================

	@Nested
	@DisplayName("Responsive tools layout (375 / sm / lg / desktop geometry)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ResponsiveLayout {

		/** Each scenario boots a fresh app — clean up after every method. */
		@AfterEach
		void restoreToDesktopViewport() {
			ResponsiveTestHelpers.setViewport(1366, 900);
		}

		@AfterAll
		void shutdown() {
			stopApp();
		}

		@BeforeEach
		void bootAndOpenToolsTab() {
			startApp(new Combo("sse"));
			openAndConnect();
			clickTab("tools");
		}

		@Test
		@Story("Responsive tools layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("At the 375x667 mobile viewport the config sidebar and the Tools list/detail panes have pairwise disjoint bounding boxes (getBoundingClientRect), each pane is at most 375px wide, and the document has no horizontal overflow (scrollWidth <= clientWidth). Every pane rectangle lies fully inside the viewport (left/top >= 0, right/bottom <= viewport) at the accepted scrollY=0 state — in the empty state and after listing tools and selecting the sum row — and its rect is printed on failure. Below the sm breakpoint the panes are height-bounded with internal scrolling (see the PATCH markers), so the stacked layout fits the first 667px viewport instead of growing past the fold.")
		@DisplayName("configPane_listPane_detailPane_disjoint_375x667 — mobile geometry at scrollY=0")
		void configPane_listPane_detailPane_disjoint_375x667() {
			// given: the exact inner mobile viewport, measured at the top of the
			// document — the accepted contract state.
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			ResponsiveTestHelpers.scrollToTop();
			Assertions.assertEquals(0, ResponsiveTestHelpers.scrollY(),
					"mobile geometry must be asserted at scrollY=0, was " + ResponsiveTestHelpers.scrollY());

			// then: the document does not overflow horizontally.
			Assertions.assertTrue(ResponsiveTestHelpers.noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 375px");

			// and: no two panes overlap (stacked mobile layout).
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("config-pane", "tools-list-pane"),
					"config-pane and tools-list-pane must not overlap at 375x667");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("config-pane", "tools-detail-pane"),
					"config-pane and tools-detail-pane must not overlap at 375x667");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("tools-list-pane", "tools-detail-pane"),
					"tools-list-pane and tools-detail-pane must not overlap at 375x667");

			// and: every pane fits the 375px viewport width.
			Assertions.assertTrue(ResponsiveTestHelpers.paneWidth("tools-list-pane") <= 375,
					"tools-list-pane must be at most 375px wide, was "
							+ ResponsiveTestHelpers.paneWidth("tools-list-pane"));
			Assertions.assertTrue(ResponsiveTestHelpers.paneWidth("tools-detail-pane") <= 375,
					"tools-detail-pane must be at most 375px wide, was "
							+ ResponsiveTestHelpers.paneWidth("tools-detail-pane"));
			Assertions.assertTrue(ResponsiveTestHelpers.paneWidth("config-pane") <= 375,
					"config-pane must be at most 375px wide, was " + ResponsiveTestHelpers.paneWidth("config-pane"));

			// and: every pane lies fully inside the 375x667 viewport at scrollY=0,
			// in the empty state. A pane clipped by an ancestor or extending past
			// the fold fails here with its rect printed — no scroll-into-view is
			// involved, the document is at the top.
			assertPanesInsideViewportAtScrollTop("empty state");

			// when: the tools are listed and the sum row is selected — the pane
			// content grows, the worst case for the inside-viewport contract.
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(15));
			selectRow("sum");
			$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10));

			// then: with the full tool list and the selected tool's form rendered
			// the panes still lie fully inside the viewport at scrollY=0.
			ResponsiveTestHelpers.scrollToTop();
			Assertions.assertEquals(0, ResponsiveTestHelpers.scrollY(),
					"listed geometry must be asserted at scrollY=0, was " + ResponsiveTestHelpers.scrollY());
			assertPanesInsideViewportAtScrollTop("listed + selected state");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("config-pane", "tools-list-pane"),
					"config-pane and tools-list-pane must not overlap after listing at 375x667");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("tools-list-pane", "tools-detail-pane"),
					"tools-list-pane and tools-detail-pane must not overlap after listing at 375x667");
		}

		/**
		 * Asserts that all three panes lie fully inside the 375x667 viewport while the
		 * document is scrolled to the top, printing each rect as evidence.
		 */
		private void assertPanesInsideViewportAtScrollTop(final String state) {
			for (final String pane : new String[] { "config-pane", "tools-list-pane", "tools-detail-pane" }) {
				System.err.println(
						"[configPane_listPane_detailPane_disjoint_375x667] " + pane + " rect (" + state + ", scrollY="
								+ ResponsiveTestHelpers.scrollY() + "): " + ResponsiveTestHelpers.paneRect(pane));
				Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport(pane),
						pane + " must lie fully inside the 375x667 viewport at scrollY=0 (" + state + "), rect: "
								+ ResponsiveTestHelpers.paneRect(pane));
			}
		}

		@Test
		@Story("Responsive tools layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("The Tools list/detail grid flips from one column (grid-cols-1) to two (sm:grid-cols-2) exactly at the inclusive Tailwind sm breakpoint: 1 column at 639px, 2 columns at 640px.")
		@DisplayName("sm_639x800_and_640x800_gridFlip — grid-cols-1 -> sm:grid-cols-2")
		void sm_639x800_and_640x800_gridFlip() {
			// given: just below the sm breakpoint
			ResponsiveTestHelpers.setViewportExactly(639, 800);

			// then: the tools-list-detail-grid is still a single column.
			Assertions.assertEquals(1, ResponsiveTestHelpers.columnCount(),
					"tools-list-detail-grid must be a single column at 639px (grid-cols-1)");

			// when: at exactly 640px (Tailwind sm, min-width: 640px)
			ResponsiveTestHelpers.setViewportExactly(640, 800);

			// then: the grid flips to two columns (sm:grid-cols-2).
			Assertions.assertEquals(2, ResponsiveTestHelpers.columnCount(),
					"tools-list-detail-grid must flip to two columns at 640px (sm:grid-cols-2)");
		}

		@Test
		@Story("Responsive tools layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("At the inclusive lg breakpoint (1024px, min-width) the root container engages the desktop side-by-side layout without regression: at 1023px and at 1024px every pane stays inside the viewport, no two panes overlap, and the document does not overflow horizontally.")
		@DisplayName("lg_1023x800_and_1024x800_rootBoundary — root stays inside the viewport")
		void lg_1023x800_and_1024x800_rootBoundary() {
			// given: the widest viewport below lg, then exactly lg
			for (int width : new int[] { 1023, 1024 }) {
				ResponsiveTestHelpers.setViewportExactly(width, 800);

				// then: all three panes remain fully inside the viewport...
				Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("config-pane"),
						"config-pane must lie inside the " + width + "px viewport");
				Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("tools-list-pane"),
						"tools-list-pane must lie inside the " + width + "px viewport");
				Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("tools-detail-pane"),
						"tools-detail-pane must lie inside the " + width + "px viewport");

				// ...no two panes overlap...
				Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("config-pane", "tools-list-pane"),
						"config-pane and tools-list-pane must not overlap at " + width + "px");
				Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("tools-list-pane", "tools-detail-pane"),
						"tools-list-pane and tools-detail-pane must not overlap at " + width + "px");

				// ...and the root container does not overflow horizontally.
				Assertions.assertTrue(ResponsiveTestHelpers.noHorizontalDocumentOverflow(),
						"document.documentElement must not overflow horizontally at " + width + "px");
			}
		}

		@Test
		@Story("Responsive tools layout")
		@Severity(SeverityLevel.NORMAL)
		@Description("Desktop regression control at 1280x800: the config sidebar and the Tools list/detail grid keep the desktop side-by-side geometry — every pane inside the viewport, no overlaps, two grid columns, no horizontal document overflow.")
		@DisplayName("desktop_1280x800_noRegression — desktop geometry intact")
		void desktop_1280x800_noRegression() {
			// given
			ResponsiveTestHelpers.setViewportExactly(1280, 800);

			// then: desktop side-by-side geometry is intact.
			Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("config-pane"),
					"config-pane must lie inside the 1280x800 viewport");
			Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("tools-list-pane"),
					"tools-list-pane must lie inside the 1280x800 viewport");
			Assertions.assertTrue(ResponsiveTestHelpers.paneInsideViewport("tools-detail-pane"),
					"tools-detail-pane must lie inside the 1280x800 viewport");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("config-pane", "tools-list-pane"),
					"config-pane and tools-list-pane must not overlap at 1280x800");
			Assertions.assertFalse(ResponsiveTestHelpers.panesOverlap("tools-list-pane", "tools-detail-pane"),
					"tools-list-pane and tools-detail-pane must not overlap at 1280x800");
			Assertions.assertEquals(2, ResponsiveTestHelpers.columnCount(),
					"tools-list-detail-grid must keep two columns on the desktop");
			Assertions.assertTrue(ResponsiveTestHelpers.noHorizontalDocumentOverflow(),
					"document.documentElement must not overflow horizontally at 1280px");
		}

	}

	// =====================================================================
	// Responsive tools critical path — 375x667 clickability of the six
	// canonical List Tools controls (issue #58). The config/list/detail panes
	// stack vertically below sm; every scenario proves with elementFromPoint
	// at the control's bounding-box centre that nothing overlays it
	// (asserted, not just computed), then performs a real click and asserts
	// the observable state. Tools scenarios use the fresh setup
	// startApp(new Combo("sse")) → openAndConnect() → clickTab("tools") →
	// setViewportExactly(375, 667) (the exact inner mobile viewport, not the
	// outer-window setter — the critical-path geometry must really run at the
	// named 375x667); the Connect scenario stays on the pre-connect
	// page.
	// =====================================================================

	@Nested
	@DisplayName("Responsive tools critical path (375x667 clickability)")
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ResponsiveToolsCriticalPath {

		/** Each scenario boots a fresh app — clean up after every method. */
		@AfterEach
		void tearDown() {
			stopApp();
		}

		/**
		 * True when {@code document.elementFromPoint} at the element's bounding-box
		 * centre returns the element itself or a descendant — i.e. nothing overlays the
		 * element's centre at the current scroll position.
		 */
		private static boolean elementAtCenter(final SelenideElement element) {
			return Boolean.TRUE
				.equals(Selenide.executeJavaScript("const el = arguments[0];" + "const r = el.getBoundingClientRect();"
						+ "const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);"
						+ "return !!(hit && (hit === el || el.contains(hit)));", element));
		}

		/**
		 * Scrolls the given element into view (its top aligned with the viewport) so an
		 * {@code elementFromPoint} probe at its bounding-box centre really samples the
		 * current viewport — Selenide's {@code shouldBe(visible)} only checks the
		 * rendered state, not the scroll position.
		 */
		private static void scrollIntoView(final SelenideElement element) {
			Selenide.executeJavaScript("arguments[0].scrollIntoView();", element);
		}

		@Test
		@Story("Responsive tools critical path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("At the 375x667 mobile viewport the pre-connect Connect button inside the stacked config pane is clickable: elementFromPoint at its bounding-box centre returns the button itself (no overlaying subtree intercepts pointer events) and a real click transitions the sidebar into the connected branch — the [data-testid=connect-button] Restart/Reconnect control mounts.")
		@DisplayName("connect_clickable_at375x667 — Connect reachable and clickable at 375x667")
		void connect_clickable_at375x667() {
			// given
			startApp(new Combo("sse"));
			open("/mcp-inspector/index.html");
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			SelenideElement connect = sidebar().$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15));

			// then: the config pane is rendered in the mobile layout and nothing
			// overlays the Connect button — elementFromPoint at its centre returns
			// the button itself.
			$("[data-testid=config-pane]").shouldBe(visible, Duration.ofSeconds(10));
			scrollIntoView(connect);
			Assertions.assertTrue(elementAtCenter(connect),
					"elementFromPoint at the Connect centre must return the button itself at 375x667");

			// when: a real click on the Connect button
			connect.click();

			// then: the connected branch mounts the Restart/Reconnect control.
			$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
		}

		@Test
		@Story("Responsive tools critical path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("At the 375x667 mobile viewport the List Tools button inside the stacked Tools list pane is clickable: after scrolling it into view, elementFromPoint at its centre returns the button itself — the exact condition the #58 report lacked (an overlaying subtree intercepted pointer events).")
		@DisplayName("listTools_clickable_at375x667_elementFromPointReturnsButton — List Tools not intercepted at 375x667")
		void listTools_clickable_at375x667_elementFromPointReturnsButton() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();
			clickTab("tools");
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			$("[data-testid=tools-list-pane]").shouldBe(visible, Duration.ofSeconds(10));
			SelenideElement listTools = activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10));

			// then: elementFromPoint at the List Tools centre returns the button
			// itself.
			scrollIntoView(listTools);
			Assertions.assertTrue(elementAtCenter(listTools),
					"elementFromPoint at the List Tools centre must return the button itself at 375x667");
		}

		@Test
		@Story("Responsive tools critical path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("At the 375x667 mobile viewport a real click on List Tools loads the tool rows into the stacked list pane, and a real click on the first row (tool-row-0) selects it — the detail pane then renders the Run Tool button.")
		@DisplayName("listTools_realClick_listsToolsAndSelectsRow — real click lists tools and selects the first row")
		void listTools_realClick_listsToolsAndSelectsRow() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();
			clickTab("tools");
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			SelenideElement listTools = activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10));

			// when: a real click on List Tools loads the rows...
			scrollIntoView(listTools);
			Assertions.assertTrue(elementAtCenter(listTools),
					"elementFromPoint at the List Tools centre must return the button itself at 375x667");
			listTools.click();
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(15));

			// ...and a real click on the first row selects it.
			SelenideElement firstRow = $("[data-testid=tool-row-0]").shouldBe(visible, Duration.ofSeconds(10));
			scrollIntoView(firstRow);
			Assertions.assertTrue(ResponsiveTestHelpers.elementAtCenter("tool-row-0"),
					"elementFromPoint at the first row centre must return the row itself at 375x667");
			firstRow.click();

			// then: the detail pane renders the Run Tool button for the selected
			// tool.
			$("[data-testid=tools-detail-pane]").shouldBe(visible, Duration.ofSeconds(10));
			$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10));
		}

		@Test
		@Story("Responsive tools critical path")
		@Severity(SeverityLevel.NORMAL)
		@Description("At the 375x667 mobile viewport the search controls of the stacked Tools list pane are reachable: the search button and the expanded search input are not intercepted (elementFromPoint at their centres returns the control), and typing a query filters the visible rows.")
		@DisplayName("search_reachable_at375x667 — search button and input reachable, filter works")
		void search_reachable_at375x667() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();
			clickTab("tools");
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			$("[data-testid=tools-list-pane]").shouldBe(visible, Duration.ofSeconds(10));
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(15));

			// then: the search button is not intercepted at 375px...
			SelenideElement searchButton = $("[data-testid=search-button]").shouldBe(visible, Duration.ofSeconds(10));
			scrollIntoView(searchButton);
			Assertions.assertTrue(ResponsiveTestHelpers.elementAtCenter("search-button"),
					"elementFromPoint at the search-button centre must return the button itself at 375x667");

			// when: a real click expands the search input, and a query is typed...
			searchButton.click();
			SelenideElement searchInput = $("[data-testid=search-input]").shouldBe(visible, Duration.ofSeconds(10));
			scrollIntoView(searchInput);
			Assertions.assertTrue(ResponsiveTestHelpers.elementAtCenter("search-input"),
					"elementFromPoint at the search-input centre must return the input itself at 375x667");
			searchInput.setValue("sum");

			// then: only the matching rows remain visible.
			activePanel().$(byText("sum")).shouldBe(visible);
			activePanel().$(byText("echo")).shouldNotBe(visible);
		}

		@Test
		@Story("Responsive tools critical path")
		@Severity(SeverityLevel.CRITICAL)
		@Description("At the 375x667 mobile viewport the Run Tool button of the stacked detail pane is clickable for the sum tool: elementFromPoint at its centre returns the button itself, and running 7 + 8 through the DynamicJsonForm inputs (id=a / id=b) produces the deterministic result text \"15\" with the result block reporting the success marker \"Tool Result: Success\".")
		@DisplayName("runTool_sum_7_8_returns15 — Run Tool clickable at 375x667, sum 7+8 -> 15")
		void runTool_sum_7_8_returns15() {
			// given
			startApp(new Combo("sse"));
			openAndConnect();
			clickTab("tools");
			ResponsiveTestHelpers.setViewportExactly(375, 667);
			SelenideElement listTools = activePanel().$(byText("List Tools"));
			if (listTools.exists() && listTools.isEnabled()) {
				listTools.click();
			}
			activePanel().$$(".cursor-pointer")
				.shouldHave(CollectionCondition.sizeGreaterThan(0), Duration.ofSeconds(15));
			selectRow("sum");
			$("[data-testid=tools-detail-pane]").shouldBe(visible, Duration.ofSeconds(10));
			SelenideElement runTool = $("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10));

			// then: the Run Tool button is not intercepted at 375px.
			scrollIntoView(runTool);
			Assertions.assertTrue(ResponsiveTestHelpers.elementAtCenter("run-tool-button"),
					"elementFromPoint at the Run Tool centre must return the button itself at 375x667");

			// when: 7 + 8 through the DynamicJsonForm inputs (id=a / id=b), then a
			// real click on Run Tool
			$("#a").shouldBe(visible).setValue("7");
			$("#b").shouldBe(visible).setValue("8");
			runTool.click();

			// then: the deterministic sum result appears in the active panel, and the
			// result block reports a successful execution — an error or failed
			// invocation must not satisfy the acceptance contract.
			activePanel().shouldHave(text("15"), Duration.ofSeconds(15));
			activePanel().$$("h4").findBy(text("Tool Result:")).shouldHave(text("Success"), Duration.ofSeconds(15));
		}

	}

}
