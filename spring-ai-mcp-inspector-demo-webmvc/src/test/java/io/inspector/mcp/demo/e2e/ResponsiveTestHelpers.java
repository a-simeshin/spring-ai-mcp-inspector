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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.Dimension;

/**
 * Geometry helpers shared by the responsive-layout {@code @Nested} groups of
 * {@link InspectorUiIT}. Every probe runs JavaScript in the live page via
 * {@link Selenide#executeJavaScript(String, Object...)} and never waits: callers pair
 * these with Selenide conditions (e.g. a pane {@code shouldBe(visible)} first), so the
 * page is settled before a geometry read.
 *
 * <p>
 * The viewport setters were moved here verbatim from the {@code ResponsiveTabBar} group
 * so every responsive group resizes the shared headless window through one place.
 */
final class ResponsiveTestHelpers {

	private ResponsiveTestHelpers() {
	}

	/**
	 * Resizes the browser window to the requested outer size. ChromeDriver sizes the
	 * window's OUTER rectangle, so {@code window.innerWidth/innerHeight} can differ from
	 * the requested values by a constant environment delta — see
	 * {@link #setViewportExactly(int, int)} for the compensated variant.
	 */
	static void setViewport(final int width, final int height) {
		WebDriverRunner.getWebDriver().manage().window().setSize(new Dimension(width, height));
	}

	/**
	 * Resizes the browser window so the page's inner viewport is exactly
	 * {@code targetWidth} x {@code targetHeight}. ChromeDriver sets the window's OUTER
	 * rectangle and headless Chromium derives the viewport from a virtual screen, so a
	 * plain resize can leave {@code window.innerHeight} far short of the requested value
	 * (observed locally: 294px for a 437px window), so the scenario would then silently
	 * run at an unintended viewport. The constant outer/inner delta of the environment is
	 * measured once, the resize is requested at target + delta and verified against a
	 * stabilized read; the delta is corrected in a bounded loop, so every scenario really
	 * executes at its documented viewport on any driver/environment. The window size
	 * request is guarded to stay positive, and the post-resize read waits for the resize
	 * to actually land (two stale equal reads are not treated as stable).
	 */
	static void setViewportExactly(final int targetWidth, final int targetHeight) {
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
			setViewport(requestWidth, requestHeight);
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
	 * WebDriver resize lands asynchronously, so two consecutive pre-resize reads must not
	 * be mistaken for a stable state), then waits until two consecutive reads agree and
	 * returns that value.
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
	 * True when the document has no horizontal overflow (scrollWidth <= clientWidth).
	 * Compared against the document's own client width rather than
	 * {@code window.innerWidth}, because innerWidth includes the vertical scrollbar and
	 * would let up to a scrollbar-width (~15px) of horizontal overflow slip through as a
	 * blind window.
	 */
	static boolean noHorizontalDocumentOverflow() {
		return Boolean.TRUE.equals(Selenide
			.executeJavaScript("return document.documentElement.scrollWidth <= document.documentElement.clientWidth;"));
	}

	/**
	 * True when the bounding boxes of the two {@code data-testid} elements intersect. A
	 * missing element fails with a readable assertion instead of a
	 * {@code ClassCastException} or a silent {@code null} result.
	 */
	static boolean panesOverlap(final String a, final String b) {
		Object result = Selenide.executeJavaScript("const a = document.querySelector('[data-testid=\\'" + a + "\\']');"
				+ "const b = document.querySelector('[data-testid=\\'" + b + "\\']');"
				+ "if (!a || !b) { return 'pane-not-found'; }" + "const ra = a.getBoundingClientRect();"
				+ "const rb = b.getBoundingClientRect();"
				+ "return !(ra.right <= rb.left || rb.right <= ra.left || ra.bottom <= rb.top"
				+ "|| rb.bottom <= ra.top);");
		if ("pane-not-found".equals(result)) {
			Assertions.fail("pane not found for overlap probe: " + a + " / " + b);
		}
		return Boolean.TRUE.equals(result);
	}

	/**
	 * True when the given pane's bounding box lies fully inside the inner viewport:
	 * left/top >= 0 and right/bottom <= {@code window.innerWidth/innerHeight}.
	 */
	static boolean paneInsideViewport(final String testid) {
		Object result = Selenide.executeJavaScript("const el = document.querySelector('[data-testid=\\'" + testid
				+ "\\']');" + "if (!el) { return 'pane-not-found'; }" + "const r = el.getBoundingClientRect();"
				+ "return r.left >= 0 && r.top >= 0 && r.right <= window.innerWidth"
				+ "&& r.bottom <= window.innerHeight;");
		if ("pane-not-found".equals(result)) {
			Assertions.fail("pane not found for viewport probe: " + testid);
		}
		return Boolean.TRUE.equals(result);
	}

	/**
	 * Width of the given pane in CSS px ({@code getBoundingClientRect().width}).
	 */
	static double paneWidth(final String testid) {
		Object result = Selenide.executeJavaScript("const el = document.querySelector('[data-testid=\\'" + testid
				+ "\\']');" + "if (!el) { return 'pane-not-found'; }" + "return el.getBoundingClientRect().width;");
		if ("pane-not-found".equals(result)) {
			Assertions.fail("pane not found for width probe: " + testid);
		}
		return ((Number) result).doubleValue();
	}

	/**
	 * True when {@code document.elementFromPoint} at the pane's bounding-box centre
	 * returns the pane itself or one of its descendants — i.e. nothing overlays the
	 * pane's centre, so a real click at that point reaches the pane.
	 */
	static boolean elementAtCenter(final String testid) {
		Object result = Selenide.executeJavaScript("const el = document.querySelector('[data-testid=\\'" + testid
				+ "\\']');" + "if (!el) { return 'pane-not-found'; }" + "const r = el.getBoundingClientRect();"
				+ "const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);"
				+ "return !!(hit && (hit === el || el.contains(hit)));");
		if ("pane-not-found".equals(result)) {
			Assertions.fail("pane not found for elementFromPoint probe: " + testid);
		}
		return Boolean.TRUE.equals(result);
	}

	/**
	 * Number of columns of the Tools list/detail grid, read from the computed
	 * {@code gridTemplateColumns} of {@code [data-testid="tools-list-detail-grid"]} — one
	 * column below the Tailwind {@code sm} breakpoint ({@code grid-cols-1}), two at/above
	 * it ({@code sm:grid-cols-2}).
	 */
	static int columnCount() {
		Object result = Selenide
			.executeJavaScript("const el = document.querySelector('[data-testid=\\'tools-list-detail-grid\\']');"
					+ "if (!el) { return 'grid-not-found'; }" + "const cols = getComputedStyle(el).gridTemplateColumns;"
					+ "if (cols === 'none') { return 0; }" + "return cols.split(' ').length;");
		if ("grid-not-found".equals(result)) {
			Assertions.fail("tools-list-detail-grid not found for columnCount probe");
		}
		return ((Number) result).intValue();
	}

	/**
	 * Bounding box of the given pane as a JSON string ({@code {left,top,right,bottom,
	 * width,height}}) — for readable assertion messages when geometry differs.
	 */
	static String paneRect(final String testid) {
		Object result = Selenide.executeJavaScript("const el = document.querySelector('[data-testid=\\'" + testid
				+ "\\']');" + "if (!el) { return 'pane-not-found'; }" + "const r = el.getBoundingClientRect();"
				+ "return JSON.stringify({left: r.left, top: r.top, right: r.right, bottom: r.bottom,"
				+ "width: r.width, height: r.height});");
		if ("pane-not-found".equals(result)) {
			Assertions.fail("pane not found for rect probe: " + testid);
		}
		return (String) result;
	}

}
