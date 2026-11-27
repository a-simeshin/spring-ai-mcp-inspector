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

import com.codeborne.selenide.CollectionCondition;
import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.ConfigurableApplicationContext;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selectors.byText;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.open;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser E2E for elicitation expiry (issue #298), run once per web stack (this class
 * ships in the demo-app test-jar and both stack modules pick it up through Failsafe's
 * {@code dependenciesToScan}).
 *
 * <p>
 * The legacy elicitation card could outlive the tool call that asked for it: the server
 * gives up after the SDK's 20 second elicitation timeout, the {@code tools/call} settles
 * with an error, and a late Submit then silently closed the card while the typed answer
 * was lost. The regression asserted here, and where:
 * <ul>
 * <li>after the server-side timeout the card flips to an expired state: an "Expired"
 * badge ({@code elicitation-expired-badge}) and an explicit banner
 * ({@code elicitation-expired});</li>
 * <li>the Tools tab shows the underlying {@code tools/call} failure ("elicitation request
 * failed"), proving the card's expiry correlates with a settled call;</li>
 * <li>a late Submit does NOT close the card: it stays visible with badge and banner,
 * instead of the old silent close;</li>
 * <li>the timely path is unchanged: answering before the timeout still delivers the
 * answer and the tool returns it.</li>
 * </ul>
 *
 * <p>
 * SSE is used as the transport deliberately, same recipe as
 * {@code InputRequiredRoundTripUiIT}: the streamable-http proxy handshake is still
 * unreliable from the upstream client, and the expiry logic under test is
 * transport-agnostic.
 */
@Epic("MCP Inspector UI")
@Feature("Elicitation expiry (issue #298)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElicitationExpiredUiIT {

	/**
	 * Must exceed the SDK's server-side elicitation timeout (20 seconds) with room to
	 * spare: the badge only appears once the failed {@code tools/call} reaches the UI.
	 */
	private static final Duration BUDGET = Duration.ofSeconds(40);

	private ConfigurableApplicationContext app;

	// ---------------------------------------------------------------------
	// Browser setup / teardown (same recipe as InputRequiredRoundTripUiIT).
	// ---------------------------------------------------------------------

	@BeforeAll
	static void setupBrowser() {
		String binary = detectChromeBinary();
		E2ePreconditions.requireChromeOrSkip(binary);

		WebDriverManager wdm = WebDriverManager.chromedriver();
		String majorVersion = detectMajorVersion(binary);
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
		if (userHome != null) {
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
		return null;
	}

	/**
	 * Resolves the browser major version for driver resolution: first from the binary
	 * path (versioned cache layouts), then from the binary itself ({@code --version}
	 * output). WebDriverManager without a version resolves the latest driver, which
	 * mismatches an installed-but-older Chromium and breaks session creation.
	 */
	private static String detectMajorVersion(String binaryPath) {
		String fromPath = parseMajorVersionFromPath(binaryPath);
		if (fromPath != null) {
			return fromPath;
		}
		if (binaryPath == null) {
			return null;
		}
		try {
			Process probe = new ProcessBuilder(binaryPath, "--version").start();
			boolean finished = probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
			String output = finished
					? new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
			probe.destroyForcibly();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.").matcher(output);
			if (m.find()) {
				return m.group(1);
			}
		}
		catch (Exception ignored) {
			/* fall through: driver resolution proceeds without a pin */
		}
		return null;
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

	// ---------------------------------------------------------------------
	// Tests.
	// ---------------------------------------------------------------------

	@Test
	@Order(1)
	@DisplayName("expiredCardBlocksLateSubmit: after the server timeout the card expires and a late Submit keeps it open")
	@Story("Elicitation expiry on late answer")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Runs askUser from the Tools tab and does not answer. After the server-side "
			+ "20 second elicitation timeout the tools/call settles with an error; the card must "
			+ "flip to expired (badge plus banner), the Tools tab must show the underlying failure, "
			+ "and a late Submit must keep the card open instead of the old silent close.")
	void expiredCardBlocksLateSubmit() {
		// given: boot, connect, invoke askUser, never answer
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();
		fireAskUser("What is your favorite color?");

		clickTab("elicitations");
		$("[data-testid=elicitation-request]").shouldBe(visible, BUDGET);

		// when: the server times out (SDK default 20s) and the failed call reaches the UI
		$("[data-testid=elicitation-expired-badge]").shouldHave(text("Expired"), BUDGET);
		$("[data-testid=elicitation-expired]").shouldBe(visible, BUDGET);

		// then: the Tools tab surfaces the underlying tools/call failure
		clickTab("tools");
		activePanel().shouldHave(text("elicitation request failed"), BUDGET);

		// when: the user answers too late (the fields are pointer-locked, so the value
		// goes in through the React setter used by every input in this suite)
		clickTab("elicitations");
		SelenideElement lateCard = $("[data-testid=elicitation-request]").shouldBe(visible, BUDGET);
		SelenideElement lateField = lateCard.$$("input[type=text]").first().shouldBe(visible);
		setReactInputValue(lateField, "blue but late");
		assertThat(lateField.getAttribute("value")).as("late answer typed").isEqualTo("blue but late");
		lateCard.$(byText("Submit")).click();

		// then: no silent close: the card, its badge and its banner are still there
		$("[data-testid=elicitation-request]").shouldBe(visible, Duration.ofSeconds(3));
		$("[data-testid=elicitation-expired-badge]").shouldBe(visible, Duration.ofSeconds(3));
		$("[data-testid=elicitation-expired]").shouldBe(visible, Duration.ofSeconds(3));
	}

	@Test
	@Order(2)
	@DisplayName("timelyAnswerStillDelivered: answering before the timeout returns the answer as before")
	@Story("Elicitation timely answer (no regression)")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Runs askUser and answers 'cerulean' before the server timeout. On the servlet "
			+ "stack the card must resolve and disappear and the tool must return the answer, "
			+ "proving the expiry tracking does not disturb the normal flow. The reactive stack "
			+ "cannot resolve this path (its demo askUser settles immediately, see the reactive "
			+ "branch below), so it instead asserts that the fast-settling call expires the card.")
	void timelyAnswerStillDelivered() {
		// given: boot, connect, invoke askUser
		app = ProxyAppHarness.start("SSE", false, null);
		Configuration.baseUrl = "http://localhost:" + ProxyAppHarness.port(app);
		openAndConnect();
		fireAskUser("What is your favorite color?");

		if ("webflux".equals(ProxyAppHarness.stack())) {
			// The reactive-stack demo cannot deliver the timely path: its
			// askUser runs the sync createElicitation on a netty event loop,
			// the SDK rejects the blocking call, and the tools/call settles
			// with an error almost immediately instead of waiting for the
			// answer (demo limitation, tracked separately). That fast settle
			// still exercises the expiry wiring: whatever order the card and
			// the settle arrive in, the card must show the expired state and
			// the Tools tab must surface the failure.
			clickTab("elicitations");
			$("[data-testid=elicitation-request]").shouldBe(visible, BUDGET);
			$("[data-testid=elicitation-expired-badge]").shouldHave(text("Expired"), BUDGET);
			clickTab("tools");
			activePanel().shouldHave(text("elicitation request failed"), BUDGET);
			return;
		}

		// when: answer well before the server timeout
		clickTab("elicitations");
		SelenideElement card = $("[data-testid=elicitation-request]").shouldBe(visible, BUDGET);
		$$("[data-testid=elicitation-expired-badge]").shouldBe(CollectionCondition.empty);
		SelenideElement field = card.$$("input[type=text]").first().shouldBe(visible);
		setReactInputValue(field, "cerulean");
		card.$(byText("Submit")).click();

		// then: the card is resolved and removed, and the tool returns the answer
		$("[data-testid=elicitation-request]").shouldNotBe(visible, BUDGET);
		clickTab("tools");
		activePanel().shouldHave(text("cerulean"), BUDGET);
	}

	// ---------------------------------------------------------------------
	// DOM helpers (kept in step with InspectorUiIT / InputRequiredRoundTripUiIT).
	// ---------------------------------------------------------------------

	private static SelenideElement activePanel() {
		return $("[role=tabpanel][data-state=active]");
	}

	private static void openAndConnect() {
		open("/mcp-inspector/index.html");
		$(".bg-card.border-r").$(byText("Connect")).shouldBe(visible, Duration.ofSeconds(15)).click();
		$("[data-testid=connect-button]").shouldBe(visible, Duration.ofSeconds(30));
	}

	private static void fireAskUser(String question) {
		clickTab("tools");
		SelenideElement listTools = activePanel().$(byText("List Tools"));
		if (listTools.exists() && listTools.isEnabled()) {
			listTools.click();
		}
		activePanel().$$(".cursor-pointer .truncate")
			.findBy(exactText("askUser"))
			.shouldBe(visible, Duration.ofSeconds(10))
			.click();
		$("#question").shouldBe(visible, Duration.ofSeconds(10)).setValue(question);
		$("[data-testid=run-tool-button]").shouldBe(visible, Duration.ofSeconds(10)).click();
	}

	private static void clickTab(String value) {
		$("[role=tablist]").shouldBe(visible, Duration.ofSeconds(15));
		$("[role=tab][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(10)).click();
		$("[role=tab][data-state=active][id$='-trigger-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
		$("[role=tabpanel][data-state=active][id$='-content-" + value + "']").shouldBe(visible, Duration.ofSeconds(5));
	}

	/**
	 * Sets a React-controlled input's value via the native setter plus a synthetic input
	 * event, so React's onChange observes it (Selenide's stock setValue loses characters
	 * on conditional re-renders; same recipe as InspectorUiIT#setReactInputValue). Goes
	 * through the element itself because expired cards wrap the form in a
	 * pointer-events-none container that swallows real clicks.
	 */
	private static void setReactInputValue(SelenideElement element, String value) {
		JavascriptExecutor js = (JavascriptExecutor) WebDriverRunner.getWebDriver();
		js.executeScript("var el = arguments[0];" + "var setter = Object.getOwnPropertyDescriptor("
				+ "  window.HTMLInputElement.prototype, 'value').set;" + "setter.call(el, arguments[1]);"
				+ "el.dispatchEvent(new Event('input', { bubbles: true }));"
				+ "el.dispatchEvent(new Event('change', { bubbles: true }));", element, value);
	}

}
