/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.demo.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared preconditions for the browser E2E suites.
 *
 * <p>
 * Ships in the {@code -demo-app} test-jar, which both stack demo modules already consume,
 * so every Chrome gate in the reactor routes through one method.
 */
public final class E2ePreconditions {

	private E2ePreconditions() {
	}

	/**
	 * Gate a browser E2E on Chrome being resolvable.
	 *
	 * <p>
	 * On a developer machine a missing browser is a skip: not everyone keeps Chrome
	 * installed, and the rest of the reactor still builds. In CI it is a failure. CI
	 * installs Chrome explicitly, so "not found" there means the install step broke or
	 * moved, and silently skipping would turn the E2E job into a green no-op.
	 * @param binary the resolved Chrome binary path, or {@code null}/blank when none was
	 * found
	 */
	public static void requireChromeOrSkip(String binary) {
		requireChromeOrSkip(binary, System.getenv("CI") != null);
	}

	/**
	 * Same gate with the CI flag passed in, so the branch is testable without touching
	 * the process environment.
	 * @param binary the resolved Chrome binary path, or {@code null}/blank when none was
	 * found
	 * @param inCi whether this run is a CI run
	 */
	static void requireChromeOrSkip(String binary, boolean inCi) {
		boolean present = binary != null && !binary.isBlank();
		if (!present && inCi) {
			Assertions.fail("Chrome binary not found in CI");
		}
		Assumptions.assumeTrue(present, "Chrome binary not found; e2e skipped");
	}

}
