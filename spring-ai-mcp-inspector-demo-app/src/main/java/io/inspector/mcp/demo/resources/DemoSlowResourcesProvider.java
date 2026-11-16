/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.resources;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * Slow and fast greeting resources backing the Resources-pane race E2E (issue #344).
 *
 * <p>
 * {@code demo://slow-greeting} delays its {@code resources/read} response by about six
 * seconds server-side; the read runs on the SDK's boundedElastic scheduler, so the delay
 * never blocks the transport and a later read can overtake it.
 * {@code demo://fast-greeting} answers immediately. Reading both in quick succession from
 * the browser reproduces the stale-preview race: the fast reply must win the pane, and
 * the late slow reply must not overwrite it.
 *
 * <p>
 * The window is six seconds on purpose: on a loaded two-core machine the browser can take
 * over two seconds to put the second read on the wire, so a two-second window makes the
 * interleaving inconclusive regardless of the stack. Six seconds clears the observed
 * emission latency with a wide margin (see {@code ResourceRacePaneUiIT}).
 */
@Component
public class DemoSlowResourcesProvider {

	/**
	 * Wire URI of the delayed resource.
	 */
	public static final String SLOW_URI = "demo://slow-greeting";

	/**
	 * Display name of the delayed resource (shown in the Resources pane list).
	 */
	public static final String SLOW_NAME = "demo-slow-greeting";

	/**
	 * Body served by the delayed resource; a unique marker for E2E assertions.
	 */
	public static final String SLOW_CONTENT = "Slow greeting from MCP demo (delayed resources/read)";

	/**
	 * Wire URI of the immediate resource.
	 */
	public static final String FAST_URI = "demo://fast-greeting";

	/**
	 * Display name of the immediate resource (shown in the Resources pane list).
	 */
	public static final String FAST_NAME = "demo-fast-greeting";

	/**
	 * Body served by the immediate resource; a unique marker for E2E assertions.
	 */
	public static final String FAST_CONTENT = "Fast greeting from MCP demo (immediate resources/read)";

	/**
	 * Server-side delay of {@link #slowGreeting()}. Six seconds keeps the race window
	 * wider than the browser emission latency of the second read under CPU contention
	 * (over two seconds on a loaded two-core runner), so the interleaving the E2E asserts
	 * stays observable there.
	 */
	static final long DELAY_MILLIS = 6000L;

	/**
	 * Delayed greeting resource available under {@code demo://slow-greeting}.
	 *
	 * <p>
	 * The delay is a plain blocking sleep: the annotation layer wraps sync handlers in
	 * {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}, so this never parks
	 * the transport thread and concurrent reads stay concurrent.
	 */
	@McpResource(uri = SLOW_URI, name = SLOW_NAME,
			description = "Greeting served after a ~6 second delay, for resources/read race demos")
	public String slowGreeting() {
		try {
			Thread.sleep(DELAY_MILLIS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("slowGreeting interrupted", ex);
		}
		return SLOW_CONTENT;
	}

	/**
	 * Immediate greeting resource available under {@code demo://fast-greeting}, the
	 * overtaking counterpart of {@link #slowGreeting()}.
	 */
	@McpResource(uri = FAST_URI, name = FAST_NAME,
			description = "Immediate greeting, pairs with the slow one for race demos")
	public String fastGreeting() {
		return FAST_CONTENT;
	}

}
