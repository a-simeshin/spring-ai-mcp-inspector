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
import java.util.Arrays;
import java.util.List;

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.inspector.mcp.demo.e2e.E2ePreconditions;
import io.inspector.mcp.demo.proxy.ProxyAppHarness;
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
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser E2E for the Tools-link half of the cache-hit pane guard (issue #344, scenario
 * from the PR #345 review), run once per web stack (this class ships in the demo-app
 * test-jar and both stack modules pick it up through Failsafe's
 * {@code dependenciesToScan}).
 *
 * <p>
 * A resource link inside a tool result calls {@code App.readResource} for the link's uri.
 * When that uri is already cached and a different row stays selected in the Resources
 * pane, the cache-hit pane commit used to overwrite the pane with the link target,
 * leaving pane and selection diverged. The guard in the cache-hit branch of
 * {@code readResource} (App.tsx) commits the cached payload to the pane only when the
 * link target is the live selection; the cached payload still renders inline in the Tools
 * tab, where {@code ResourceLinkView} reads the same per-uri map.
 *
 * <p>
 * This IT reproduces that scenario through the real proxy: cache two resource rows, keep
 * the second selected, run the {@code multiContent} demo tool whose result carries a
 * resource link to the first row's uri, click the link, and assert the Resources pane
 * still shows the selected row's payload while the link expansion renders the cached
 * greeting inline. The link click is a cache hit, and the read is re-issued anyway
 * (stale-while-revalidate): the wire must show the cached greeting read a second time,
 * and the fresh reply refreshes the cache without touching the pane.
 *
 * <p>
 * SSE transport deliberately, same reason as {@link InputRequiredRoundTripUiIT}: the
 * round-trip under test is transport-agnostic and the streamable-http handshake is still
 * unreliable from the upstream client.
 */
@Epic("MCP Inspector UI")
@Feature("Resources pane cache-hit guard, Tools-link half")
class ToolsLinkCacheHitPaneUiIT {

	private static final Duration BUDGET = Duration.ofSeconds(30);

	private static final Duration TIMELINE_POLL_BUDGET = Duration.ofSeconds(10);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

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
		if (app != null) {
			app.close();
			app = null;
		}
		Selenide.closeWindow();
	}

	@Test
	@Story("Cache-hit pane guard, Tools link")
	@Severity(SeverityLevel.CRITICAL)
	@Description("With demo-readme selected in the Resources pane, clicking the demo://greeting "
			+ "resource link in the multiContent tool result must not overwrite the pane: the "
			+ "cache-hit pane commit is suppressed for a uri that is not the live selection, the "
			+ "link expansion renders the cached greeting inline in the Tools tab, and the click "
			+ "re-issues resources/read for the cached uri (stale-while-revalidate) without " + "touching the pane.")
	@DisplayName("toolsLinkOnCachedUriKeepsSelectedRowPane: a cached Tools-link click leaves the selected row's pane untouched")
	void toolsLinkOnCachedUriKeepsSelectedRowPane() throws Exception {
		// given: boot, connect
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();

		// when: cache two resource rows and keep the second selected
		clickTab("resources");
		listResourcesIfEnabled();
		selectRow("demo-greeting");
		activePanel().shouldHave(text("Hello from MCP demo"), BUDGET);
		selectRow("demo-readme");
		activePanel().shouldHave(text("text/markdown"), BUDGET);

		// and: run the tool whose result carries a resource link to the cached
		// greeting
		clickTab("tools");
		// tools/list is not automatic after connect; List Tools is idempotent once
		// the list is non-empty (InspectorUiIT, same recipe).
		activePanel().$(byText("List Tools")).shouldBe(visible, Duration.ofSeconds(10)).click();
		// multiContent has no title annotation, so the row label is the wire name.
		selectRow("multiContent");
		$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10)).click();
		SelenideElement greetingLink = activePanel().$("[aria-label='Expand resource demo://greeting']")
			.shouldBe(visible, BUDGET);

		// when: click the Tools link on the cached uri; the expansion renders the
		// cached payload inline in the Tools tab
		greetingLink.click();
		activePanel().shouldHave(text("Hello from MCP demo"), BUDGET);

		// then: back on Resources, the pane still shows the selected row's payload.
		// This is the guard under test: without it, the cache-hit commit overwrites
		// the pane with demo://greeting while demo-readme stays selected.
		clickTab("resources");
		activePanel().shouldHave(text("text/markdown"), BUDGET);
		activePanel().shouldNotHave(text("Hello from MCP demo"), BUDGET);

		// and: the click re-validated the cached greeting on the wire: the two
		// selection reads plus the stale-while-revalidate re-read
		assertResourceReads("demo://greeting", "demo://readme.md", "demo://greeting");

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
	 * substring matching resolved "echo" to "slowEcho" in InspectorUiIT).
	 */
	private static void selectRow(String name) {
		activePanel().$$(".cursor-pointer .truncate")
			.findBy(exactText(name))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
	}

	/**
	 * Lists resources once: ListPane disables the button once the list is populated, and
	 * a fresh session starts empty, so the click is safe either way (same recipe as
	 * InspectorUiIT's Resources setup).
	 */
	private static void listResourcesIfEnabled() {
		SelenideElement listResources = activePanel().$(byText("List Resources"));
		if (listResources.exists() && listResources.isEnabled()) {
			listResources.click();
		}
	}

	/**
	 * Issue #212 regression class: error paths must surface as clean UI errors, never as
	 * raw JSON-RPC toasts.
	 */
	private static void assertNoRawToast() {
		$$("[data-sonner-toast]").shouldBe(CollectionCondition.empty);
	}

	/**
	 * The proxy timeline must show exactly the {@code resources/read} calls for the given
	 * uris. A cache hit no longer suppresses the wire call: the cached uri is re-read
	 * (stale-while-revalidate), and the fresh reply refreshes the cache without touching
	 * the pane. Polled with a short deadline: the re-read lands asynchronously, a moment
	 * after the pane assertions.
	 */
	private void assertResourceReads(String... expectedUris) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(Configuration.baseUrl + "/mcp-inspector/api/timeline?limit=500"))
			.GET()
			.build();
		List<String> expectedSorted = new ArrayList<>(Arrays.asList(expectedUris));
		expectedSorted.sort(String::compareTo);
		List<String> readUris = new ArrayList<>();
		long deadline = System.currentTimeMillis() + TIMELINE_POLL_BUDGET.toMillis();
		while (true) {
			readUris.clear();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as("timeline API status").isEqualTo(200);

			JsonNode events = MAPPER.readTree(response.body());
			assertThat(events.isArray()).as("timeline API returns an array").isTrue();

			for (JsonNode event : events) {
				if (!"MCP_JSONRPC_REQUEST".equals(event.path("type").asText())) {
					continue;
				}
				JsonNode payload = event.path("payload");
				if (!"resources/read".equals(payload.path("method").asText())) {
					continue;
				}
				readUris.add(payload.path("params").path("uri").asText());
			}
			List<String> actualSorted = new ArrayList<>(readUris);
			actualSorted.sort(String::compareTo);
			if (actualSorted.equals(expectedSorted) || System.currentTimeMillis() >= deadline) {
				break;
			}
			Thread.sleep(250);
		}
		assertThat(readUris)
			.as("resources/read calls on the wire (the cache hit re-validates the cached uri, stale-while-revalidate)")
			.containsExactlyInAnyOrder(expectedUris);
	}

	// ---------------------------------------------------------------------
	// Chrome discovery (kept in step with InspectorUiSmokeIT).
	// ---------------------------------------------------------------------

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

}
