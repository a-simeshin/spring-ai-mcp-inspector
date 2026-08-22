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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the one thing {@link E2ePreconditions} decides: a missing browser is a skip on a
 * developer host and a failure in CI. Without this, a regression turning the CI branch
 * back into a skip would be invisible until the E2E jobs had been silently green for
 * weeks.
 */
class E2ePreconditionsTests {

	@Test
	@DisplayName("a missing Chrome fails the run in CI")
	void requireChrome_whenBinaryMissingInCi_fails() {
		assertThatThrownBy(() -> E2ePreconditions.requireChromeOrSkip(null, true))
			.isInstanceOf(AssertionFailedError.class)
			.hasMessageContaining("Chrome binary not found in CI");
	}

	@Test
	@DisplayName("a blank Chrome path counts as missing")
	void requireChrome_whenBinaryBlankInCi_fails() {
		assertThatThrownBy(() -> E2ePreconditions.requireChromeOrSkip("   ", true))
			.isInstanceOf(AssertionFailedError.class);
	}

	@Test
	@DisplayName("a missing Chrome only skips outside CI")
	void requireChrome_whenBinaryMissingOutsideCi_skips() {
		assertThatThrownBy(() -> E2ePreconditions.requireChromeOrSkip(null, false))
			.isInstanceOf(TestAbortedException.class);
	}

	@Test
	@DisplayName("a resolved Chrome passes the gate in both environments")
	void requireChrome_whenBinaryPresent_passes() {
		assertThatCode(() -> E2ePreconditions.requireChromeOrSkip("/usr/bin/chrome", true)).doesNotThrowAnyException();
		assertThatCode(() -> E2ePreconditions.requireChromeOrSkip("/usr/bin/chrome", false)).doesNotThrowAnyException();
	}

}
