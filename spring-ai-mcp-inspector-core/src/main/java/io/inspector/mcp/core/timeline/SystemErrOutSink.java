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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures {@link System#out} and {@link System#err} output that bypasses Logback (e.g.
 * stderr from streamable HTTP, direct {@code System.out.println} calls) and forwards it
 * to the {@link TimelineService} as {@link TimelineEventType#APP_LOG} events.
 *
 * <p>
 * The sink works by replacing the standard output streams with wrappers that capture each
 * line of output and emit a timeline event. Original streams are restored on
 * {@link #close()}.
 *
 * <p>
 * <strong>Thread safety:</strong> the underlying stream replacement is safe; individual
 * line capture is best-effort under concurrent writes.
 *
 * @author Artem Simeshin
 */
public final class SystemErrOutSink implements AutoCloseable {

	/** Logger name used for System.out events. */
	public static final String STDOUT_LOGGER = "System.out";

	/** Logger name used for System.err events. */
	public static final String STDERR_LOGGER = "System.err";

	private final TimelineService timelineService;

	private final PrintStream originalOut;

	private final PrintStream originalErr;

	private final DetachedConsoleStreams detachedStreams;

	private volatile boolean closed;

	/**
	 * Creates a sink that captures {@code System.out} and {@code System.err}. Logback
	 * console appenders are detached to the raw standard-stream descriptors first, so
	 * logged lines reach the timeline exactly once (via {@link TimelineAppender}) and
	 * only genuine direct-to-console writes show up as sink-captured APP_LOG events.
	 * @param timelineService the target timeline service
	 */
	public SystemErrOutSink(final TimelineService timelineService) {
		this.timelineService = timelineService;
		this.originalOut = System.out;
		this.originalErr = System.err;
		this.detachedStreams = DetachedConsoleStreams.detach();
		System.setOut(new CaptureStream(this.originalOut, STDOUT_LOGGER, "INFO"));
		System.setErr(new CaptureStream(this.originalErr, STDERR_LOGGER, "WARN"));
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		System.setOut(this.originalOut);
		System.setErr(this.originalErr);
		this.detachedStreams.restore();
	}

	/**
	 * Returns {@code true} if the sink has been closed.
	 * @return close state
	 */
	public boolean isClosed() {
		return this.closed;
	}

	private final class CaptureStream extends PrintStream {

		private final PrintStream original;

		private final String loggerName;

		private final String level;

		private final StringBuilder lineBuffer;

		CaptureStream(final PrintStream original, final String loggerName, final String level) {
			super(new ByteArrayOutputStream(0), true, StandardCharsets.UTF_8);
			this.original = original;
			this.loggerName = loggerName;
			this.level = level;
			this.lineBuffer = new StringBuilder();
		}

		@Override
		public void write(final int b) {
			this.original.write(b);
			synchronized (this.lineBuffer) {
				if (b == '\n') {
					flushLine();
				}
				else {
					this.lineBuffer.append((char) b);
				}
			}
		}

		@Override
		public void write(final byte[] buf, final int off, final int len) {
			this.original.write(buf, off, len);
			synchronized (this.lineBuffer) {
				this.lineBuffer.append(new String(buf, off, len, StandardCharsets.UTF_8));
				processBuffer();
			}
		}

		@Override
		public void println(final String x) {
			this.original.println(x);
			synchronized (this.lineBuffer) {
				this.lineBuffer.append(x);
				flushLine();
			}
		}

		private void processBuffer() {
			String remaining = this.lineBuffer.toString();
			int idx;
			while ((idx = remaining.indexOf('\n')) >= 0) {
				final String line = remaining.substring(0, idx);
				emitLine(line);
				remaining = remaining.substring(idx + 1);
			}
			this.lineBuffer.setLength(0);
			this.lineBuffer.append(remaining);
		}

		private void flushLine() {
			if (this.lineBuffer.length() > 0) {
				emitLine(this.lineBuffer.toString());
				this.lineBuffer.setLength(0);
			}
		}

		private void emitLine(final String line) {
			if (!SystemErrOutSink.this.closed && !line.isEmpty()) {
				// Capture failures must never propagate into ordinary logging: a
				// throwing TimelineService used to make System.out.println throw too.
				// The original output above is already flushed at this point.
				try {
					final String safeLevel = (this.level != null) ? this.level : "";
					final String safeLogger = (this.loggerName != null) ? this.loggerName : "";
					SystemErrOutSink.this.timelineService.append(TimelineEvent.createLogEvent(null, safeLevel,
							safeLogger, Thread.currentThread().getName(), line, null));
				}
				catch (final RuntimeException ex) {
					// Best-effort: a failing timeline must not take the console down.
				}
			}
		}

	}

}
