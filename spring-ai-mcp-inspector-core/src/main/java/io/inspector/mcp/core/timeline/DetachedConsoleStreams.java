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

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import org.slf4j.LoggerFactory;

/**
 * Repoints Logback's console appenders at the raw standard streams before
 * {@link SystemErrOutSink} wraps {@code System.out}/{@code System.err}.
 *
 * <p>
 * Without this, a log line reaches the timeline twice: once through the
 * {@link TimelineAppender} on the root logger, and once because the ConsoleAppender
 * writes through the already-wrapped {@code System.out}, which the sink captures as a
 * second APP_LOG event (or, at best, creates an append-from-within-append reentry).
 * {@code ConsoleAppender} does not hold {@code System.out} itself: at
 * {@code ConsoleAppender.start()} it stores the lazy {@code ConsoleTarget} delegate,
 * whose {@code write} re-reads the current {@code System.out} static on every call. A
 * {@code System.out == currentStream} identity check therefore never matches a console
 * appender - it has to be recognised by type (and by the delegate it holds), then
 * repointed at a stream bound directly to {@link FileDescriptor#out} /
 * {@link FileDescriptor#err}, which detaches console output from whatever
 * {@code System.out} later becomes.
 *
 * <p>
 * The original streams are remembered and can be restored on {@link #restore()}, which
 * keeps the change reversible for tests and for sink shutdown.
 *
 * @author Artem Simeshin
 */
public final class DetachedConsoleStreams {

	/**
	 * Streams bound to the raw standard descriptors. Process-scoped by design — like
	 * {@code System.out} itself they are never closed, because closing {@code fd 1}/
	 * {@code fd 2} would take the console down for the whole JVM.
	 */
	private static final OutputStream RAW_STDOUT = new FileOutputStream(FileDescriptor.out);

	private static final OutputStream RAW_STDERR = new FileOutputStream(FileDescriptor.err);

	private final Map<OutputStreamAppender<?>, OutputStream> previous = new IdentityHashMap<>();

	private DetachedConsoleStreams(final Map<OutputStreamAppender<?>, OutputStream> previous) {
		this.previous.putAll(previous);
	}

	/**
	 * Rebinds every {@link OutputStreamAppender} currently attached to any logger in the
	 * given context: appenders targeting the current {@code System.out} go to the raw
	 * stdout descriptor, appenders targeting the current {@code System.err} go to the raw
	 * stderr descriptor. Appenders on other streams (files, sockets) are left untouched.
	 * @return a handle that restores the previous streams, never {@code null}
	 */
	public static DetachedConsoleStreams detach() {
		final Map<OutputStreamAppender<?>, OutputStream> saved = new IdentityHashMap<>();
		final OutputStream consoleOut = System.out;
		final OutputStream consoleErr = System.err;
		final Object factory;
		try {
			factory = LoggerFactory.getILoggerFactory();
		}
		catch (final Throwable ex) {
			return new DetachedConsoleStreams(saved);
		}
		if (!(factory instanceof final LoggerContext context)) {
			return new DetachedConsoleStreams(saved);
		}
		for (final ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
			final var it = logger.iteratorForAppenders();
			while (it.hasNext()) {
				repoint(it.next(), consoleOut, consoleErr, saved);
			}
		}
		return new DetachedConsoleStreams(saved);
	}

	/**
	 * Restores every appender previously repointed by {@link #detach()}.
	 */
	public void restore() {
		for (final Map.Entry<OutputStreamAppender<?>, OutputStream> entry : this.previous.entrySet()) {
			try {
				setOutputStreamField(entry.getKey(), entry.getValue());
			}
			catch (final ReflectiveOperationException ex) {
				// best effort: a failed restore leaves console output on the raw stream
			}
		}
		this.previous.clear();
	}

	private static void repoint(final Appender<?> appender, final OutputStream consoleOut,
			final OutputStream consoleErr, final Map<OutputStreamAppender<?>, OutputStream> saved) {
		if (!(appender instanceof final OutputStreamAppender<?> streamAppender)) {
			return;
		}
		final OutputStream current = streamAppender.getOutputStream();
		// A ConsoleAppender's stored stream is the lazy ConsoleTarget delegate, never
		// the identity of System.out/err it will resolve at write time: match it by the
		// target it points at, and re-point through the public setter so the class
		// contract (encoder re-init included) stays intact.
		if (streamAppender instanceof final ConsoleAppender<?> console) {
			repointConsole(console, saved);
			return;
		}
		if (current == consoleOut) {
			saved.put(streamAppender, current);
			try {
				setOutputStreamField(streamAppender, RAW_STDOUT);
			}
			catch (final ReflectiveOperationException ex) {
				saved.remove(streamAppender);
			}
		}
		else if (current == consoleErr) {
			saved.put(streamAppender, current);
			try {
				setOutputStreamField(streamAppender, RAW_STDERR);
			}
			catch (final ReflectiveOperationException ex) {
				saved.remove(streamAppender);
			}
		}
	}

	/**
	 * Repoints a console appender whose stored stream is one of the lazy
	 * {@link ConsoleTarget} delegates (the only shape {@code ConsoleAppender.start()}
	 * produces without JANSI). The delegate re-reads the live {@code System.out} /
	 * {@code System.err} static on every write, so once the sink installs its capture
	 * wrapper the delegate would route console output through it and duplicate every
	 * logged line. Appenders on any other stream (JANSI-wrapped, or a stream set
	 * explicitly) are left alone.
	 * @param console the console appender to repoint
	 * @param saved map remembering each repointed appender's previous stream
	 */
	private static void repointConsole(final ConsoleAppender<?> console,
			final Map<OutputStreamAppender<?>, OutputStream> saved) {
		final OutputStream current = console.getOutputStream();
		final OutputStream replacement;
		if (current == ConsoleTarget.SystemOut.getStream()) {
			replacement = RAW_STDOUT;
		}
		else if (current == ConsoleTarget.SystemErr.getStream()) {
			replacement = RAW_STDERR;
		}
		else {
			return;
		}
		saved.put(console, current);
		try {
			setOutputStreamField(console, replacement);
		}
		catch (final ReflectiveOperationException ex) {
			saved.remove(console);
		}
	}

	/**
	 * Writes {@link OutputStreamAppender#setOutputStream}'s backing field directly: the
	 * public setter re-initialises the encoder, which duplicates the header on every
	 * call. The field itself is the only state the append path reads.
	 * @param appender the appender to repoint
	 * @param value the stream to bind
	 */
	private static void setOutputStreamField(final OutputStreamAppender<?> appender, final OutputStream value)
			throws ReflectiveOperationException {
		final Field field = OutputStreamAppender.class.getDeclaredField("outputStream");
		field.setAccessible(true);
		field.set(appender, value);
	}

}
