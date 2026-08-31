/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.core.timeline;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SystemErrOutSink}.
 */
class SystemErrOutSinkTests {

	private final List<TimelineEvent> captured = new ArrayList<>();

	private final TimelineService timelineService = mock(TimelineService.class);

	private PrintStream originalOut;

	private PrintStream originalErr;

	@BeforeEach
	void setUp() {
		this.originalOut = System.out;
		this.originalErr = System.err;
		willAnswer((invocation) -> {
			this.captured.add(invocation.getArgument(0));
			return null;
		}).given(this.timelineService).append(any());
	}

	@AfterEach
	void tearDown() {
		System.setOut(this.originalOut);
		System.setErr(this.originalErr);
	}

	@Test
	void capturesStdoutLine() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			System.out.println("hello stdout");
		}
		assertThat(this.captured).hasSize(1);
		assertThat(this.captured.get(0).message()).isEqualTo("hello stdout");
		assertThat(this.captured.get(0).logLevel()).isEqualTo("INFO");
		assertThat(this.captured.get(0).loggerName()).isEqualTo("System.out");
	}

	@Test
	void capturesStderrLine() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			System.err.println("hello stderr");
		}
		assertThat(this.captured).hasSize(1);
		assertThat(this.captured.get(0).message()).isEqualTo("hello stderr");
		assertThat(this.captured.get(0).logLevel()).isEqualTo("WARN");
		assertThat(this.captured.get(0).loggerName()).isEqualTo("System.err");
	}

	@Test
	void closeIsIdempotent() {
		final SystemErrOutSink sink = new SystemErrOutSink(this.timelineService);
		sink.close();
		sink.close();
		assertThat(sink.isClosed()).isTrue();
	}

	@Test
	void doesNotEmitEmptyLine() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			System.out.println();
		}
		assertThat(this.captured).isEmpty();
	}

	@Test
	void doesNotEmitAfterClose() {
		final SystemErrOutSink sink = new SystemErrOutSink(this.timelineService);
		sink.close();
		System.out.println("after close");
		assertThat(this.captured).isEmpty();
	}

	@Test
	void capturesWriteByteWithNewline() {
		final byte[] data = "line1\n".getBytes();
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			System.out.write(data[0]);
			System.out.write(data[1]);
			System.out.write(data[2]);
			System.out.write(data[3]);
			System.out.write(data[4]);
			System.out.write(data[5]);
		}
		assertThat(this.captured).hasSize(1);
		assertThat(this.captured.get(0).message()).isEqualTo("line1");
	}

	@Test
	void capturesWriteByteArray() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			System.out.write("multi\n".getBytes(), 0, 6);
		}
		assertThat(this.captured).hasSize(1);
		assertThat(this.captured.get(0).message()).isEqualTo("multi");
	}

	@Test
	void capturesMultiLine() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			final byte[] data = "line1\nline2\n".getBytes();
			System.out.write(data, 0, data.length);
		}
		assertThat(this.captured).hasSize(2);
		assertThat(this.captured.get(0).message()).isEqualTo("line1");
		assertThat(this.captured.get(1).message()).isEqualTo("line2");
	}

	@Test
	void capturesWriteWithoutNewlineOnClose() {
		try (SystemErrOutSink sink = new SystemErrOutSink(this.timelineService)) {
			final byte[] data = "no-newline".getBytes();
			System.out.write(data, 0, data.length);
		}
		// Without a newline, the line buffer is never flushed
		assertThat(this.captured).isEmpty();
	}

	@Test
	void timelineServiceFailureDoesNotPropagate() {
		// given — a timeline service that throws
		final TimelineService failing = mock(TimelineService.class);
		willThrow(new RuntimeException("timeline unavailable")).given(failing).append(any());
		try (SystemErrOutSink sink = new SystemErrOutSink(failing)) {
			// when — the sink must not throw
			System.out.println("hello");
		}
		// then — no exception propagated, capture didn't happen
		assertThat(this.captured).isEmpty();
	}

}
