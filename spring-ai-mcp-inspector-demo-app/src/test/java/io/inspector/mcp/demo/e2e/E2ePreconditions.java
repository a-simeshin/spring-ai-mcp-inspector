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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared preconditions for the browser E2E suites.
 *
 * <p>
 * Lives in {@code demo-app} and ships in this module's test-jar, so {@code demo-webmvc}
 * and {@code demo-webflux} both see it on their test classpath without a module of its
 * own.
 */
public final class E2ePreconditions {

	private E2ePreconditions() {
	}

	/**
	 * Gate a browser E2E suite on Chrome being installed.
	 *
	 * <p>
	 * On a developer machine a missing browser is a legitimate reason to skip. In CI it
	 * is not: the workflow installs Chrome and exports {@code CHROME_BINARY}, so a
	 * missing binary means the browser step broke and the whole suite would otherwise
	 * self-disable behind a green build. There it fails loudly instead.
	 * @param binary the detected Chrome binary path, or {@code null} / blank when none
	 * was found
	 */
	public static void requireChromeOrSkip(String binary) {
		boolean present = binary != null && !binary.isBlank();
		if (!present && System.getenv("CI") != null) {
			Assertions.fail("Chrome binary not found in CI");
		}
		Assumptions.assumeTrue(present, "Chrome binary not found; e2e skipped");
	}

}
