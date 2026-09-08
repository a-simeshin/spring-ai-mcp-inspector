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
import java.net.InetAddress;
import java.net.ServerSocket;
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

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;

/**
 * End-to-end Selenide tests for the connect-failure path: pointing the inspector at an
 * unreachable MCP server must surface a {@code role=alert} with the machine-readable
 * reason and a Retry button instead of silently flipping the sidebar to "Disconnected".
 *
 * <p>
 * The behaviour under test is the stack of PR #71 (backend returns the structured
 * {@code MCP_CONNECT_FAILED} contract from {@code POST /mcp-inspector-api/mcp}) and PR
 * #70 (the vendored client's fetch wrapper parses that contract and the sidebar renders
 * the alert). Both fixes live on separate branches and are merged into the branch this
 * test ships on - the test is the regression lock for the whole path.
 *
 * <p>
 * Browser / boot helpers mirror {@link InspectorUiIT} verbatim: version-pinned
 * WebDriverManager, {@link E2ePreconditions#requireChromeOrSkip(String)} (skip locally,
 * hard failure in CI), and command-line {@code --server.port=0} so the demo never
 * collides with {@code 8080}. The transport is explicitly switched to Streamable HTTP
 * because that is the proxy branch carrying the structured-error fetch wrapper; the
 * SSE/STDIO branches surface failures through different plumbing.
 *
 * <p>
 * The unreachable target is a freshly-allocated closed loopback port (same trick as
 * {@code WebMvcAutoConfigurationIT.postMcp_unreachableUpstream_returnsStructured502}):
 * the very first connect attempt is refused, and no other process can claim the port
 * while the test runs.
 */
@Epic("MCP Inspector UI")
@Feature("Connect failure alert E2E")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectFailureIT {

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown - mirrors InspectorUiIT.
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

	/**
	 * Resolves the absolute path of a Chrome browser binary with the same priority list
	 * as {@code InspectorUiIT#detectChromeBinary()}: {@code webdriver.chrome.binary}
	 * property → {@code CHROME_BINARY} env → macOS production Chrome → Puppeteer cache
	 * scan → Linux fallbacks.
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
				matches.sort(ConnectFailureIT::compareVersionDirs);
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
			// with the version line. Match the full four-segment version rather than the
			// first digit run (which may be a pid/timestamp fragment like "231106.") and
			// return its major part.
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
	 * Boots {@link DemoApplication} on a random port. The demo's own MCP server protocol
	 * is irrelevant to this suite: the inspector UI connects to the unreachable target
	 * through the proxy, so only the servlet stack and the bootstrap seed matter.
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
		return $(".bg-card.border-r");
	}

	/** Sidebar Connect button (first-time; has no testid - match by visible text). */
	private static SelenideElement connectButton() {
		return sidebar().$(byText("Connect"));
	}

	/**
	 * The connect-failure alert mounted by the Sidebar (PR #70). We scope by the
	 * {@code [data-testid=retry-connect-button]} instead of {@code [role=alert]} because
	 * the auth-profiles panel (issue #54) also renders a {@code role=alert} with "Profile
	 * name is required" on initial mount, and the test must not confuse the two.
	 */
	private static SelenideElement alert() {
		return $("[data-testid=retry-connect-button]").parent();
	}

	/**
	 * Sets the value of a React-controlled {@code <input>} reliably by invoking the
	 * native HTMLInputElement setter and then dispatching a synthetic {@code input} event
	 * - same helper as {@code InspectorUiIT#setReactInputValue}. Selenide's stock
	 * {@code setValue} interacts poorly with {@code Sidebar.tsx}'s
	 * {@code sseUrl ? <Tooltip>...</Tooltip> : <Input/>} ternary that swaps the DOM node
	 * whenever the controlled value flips between empty and non-empty.
	 */
	private static void setReactInputValue(String cssSelector, String value) {
		org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) com.codeborne.selenide.WebDriverRunner
			.getWebDriver();
		js.executeScript("var el = document.querySelector(arguments[0]);" + "if (!el) { return; }"
				+ "var setter = Object.getOwnPropertyDescriptor(" + "  window.HTMLInputElement.prototype, 'value').set;"
				+ "setter.call(el, arguments[1]);" + "el.dispatchEvent(new Event('input', { bubbles: true }));"
				+ "el.dispatchEvent(new Event('change', { bubbles: true }));", cssSelector, value);
	}

	/**
	 * Opens the inspector page and arms it against an unreachable server: switches the
	 * transport to Streamable HTTP (the proxy branch with the structured-error fetch
	 * wrapper) and points the URL input at a freshly-closed loopback port, so the very
	 * first connect attempt is refused.
	 */
	private void openWithUnreachableServer() {
		// A loopback port that nothing listens on - the very first connect attempt is
		// refused (same trick as WebMvcAutoConfigurationIT).
		final int deadPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			deadPort = socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException("failed to allocate a closed loopback port", e);
		}

		open("/mcp-inspector/index.html");
		// Sidebar must finish its first render before we touch anything.
		connectButton().shouldBe(visible, Duration.ofSeconds(15));

		// Streamable HTTP is the proxy branch whose fetch wrapper parses the structured
		// MCP_CONNECT_FAILED contract (PR #70) - SSE/STDIO surface failures differently.
		$("#transport-type-select").shouldBe(visible).click();
		$$("[role=option]").findBy(text("Streamable HTTP")).shouldBe(visible).click();
		$("#sse-url-input").shouldBe(visible);

		String deadUrl = "http://127.0.0.1:" + deadPort + "/mcp";
		setReactInputValue("#sse-url-input", deadUrl);
	}

	/**
	 * Opens the inspector page and arms it against a host name that cannot resolve:
	 * switches the transport to Streamable HTTP and points the URL input at an
	 * {@code .invalid} TLD host (RFC 2606 guarantees it never resolves), so the proxy's
	 * connect attempt fails with an UnknownHostException that the MCP SDK wraps in a
	 * ConnectException - the scenario that classify() must still map to {@code dns} /
	 * "Cannot resolve host".
	 */
	private void openWithUnresolvableHost() {
		open("/mcp-inspector/index.html");
		connectButton().shouldBe(visible, Duration.ofSeconds(15));

		$("#transport-type-select").shouldBe(visible).click();
		$$("[role=option]").findBy(text("Streamable HTTP")).shouldBe(visible).click();
		$("#sse-url-input").shouldBe(visible);

		// RFC 2606 reserves .invalid - the name is guaranteed to never resolve.
		final String unresolvableUrl = "http://this-host-does-not-exist.invalid:9999/mcp";
		setReactInputValue("#sse-url-input", unresolvableUrl);
	}

	// =====================================================================
	// Connect-failure scenarios.
	// =====================================================================

	@Test
	@Story("Connect failure")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Connecting to an unreachable MCP server surfaces a role=alert with the failure reason and a Retry button.")
	@DisplayName("connectToUnreachableServer - alert with reason and Retry instead of silent Disconnected")
	void connectToUnreachableServer_showsAlertWithReasonAndRetry() {
		// given
		startApp();
		openWithUnreachableServer();

		// when - the Connect click POSTs initialize through the proxy, which answers
		// 502 MCP_CONNECT_FAILED / connection_refused (PR #71).
		connectButton().click();

		// then - the sidebar renders the failure as role=alert: heading, human-readable
		// reason carrying the machine-readable code, and a Retry button (PR #70).
		alert().shouldBe(visible, Duration.ofSeconds(30));
		alert().shouldHave(text("Failed to connect to the MCP server"));
		alert().shouldHave(text("Connection refused"));
		$("[data-testid=retry-connect-button]").shouldBe(visible).shouldHave(text("Retry"));
		// The pre-connect Connect button stays available too.
		connectButton().shouldBe(visible);
	}

	@Test
	@Story("Connect failure")
	@Severity(SeverityLevel.NORMAL)
	@Description("Clicking Retry re-runs connect: the re-post fails the same way and the alert still names the reason.")
	@DisplayName("retryReposts: Retry re-posts and the alert still names the reason")
	void retry_repostsAndShowsAlertAgain() {
		// given - the same unreachable-server setup, alert already visible.
		startApp();
		openWithUnreachableServer();
		connectButton().click();
		alert().shouldBe(visible, Duration.ofSeconds(30));
		alert().shouldHave(text("Connection refused"));

		// when - Retry invokes connect() again.
		$("[data-testid=retry-connect-button]").shouldBe(visible).click();

		// then - the re-post fails the same way and the alert still names the same
		// reason, with Retry still available for another attempt.
		//
		// The in-flight gap is deliberately NOT asserted. connect() clears the
		// previous error synchronously, so the alert does unmount while the new POST
		// is on the wire, but that POST hits a closed loopback port: ECONNREFUSED
		// comes back in milliseconds and the alert is already re-rendered before
		// Selenide can poll for its absence. Waiting for a gap that has usually
		// closed by the first poll made this scenario flaky (observed on PR #83,
		// demo E2E webmvc). That the click really re-invokes connect() is covered
		// without a race by the Sidebar.connectFailureAlert unit test.
		alert().shouldBe(visible, Duration.ofSeconds(30));
		alert().shouldHave(text("Connection refused"));
		$("[data-testid=retry-connect-button]").shouldBe(visible);
	}

	@Test
	@Story("Connect failure")
	@Severity(SeverityLevel.NORMAL)
	@Description("Connecting to a host name that cannot resolve surfaces an alert with the dns reason: 'Cannot resolve host'.")
	@DisplayName("connectToUnresolvableHost - alert with DNS reason 'Cannot resolve host'")
	void connectToUnresolvableHost_showsAlertWithDnsReason() {
		// given - RFC 2606 .invalid TLD never resolves; the SDK wraps the
		// UnknownHostException in ConnectException, so classify() must descend
		// into the cause chain to classify as dns, not connection_refused.
		startApp();
		openWithUnresolvableHost();

		// when
		connectButton().click();

		// then - the sidebar renders the failure as role=alert with the DNS
		// reason: humanReadableReason("dns") = "Cannot resolve host".
		alert().shouldBe(visible, Duration.ofSeconds(30));
		alert().shouldHave(text("Failed to connect to the MCP server"));
		alert().shouldHave(text("Cannot resolve host"));
		$("[data-testid=retry-connect-button]").shouldBe(visible).shouldHave(text("Retry"));
	}

}
