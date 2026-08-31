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

public final class DetachedConsoleStreams {

	private final Map<OutputStreamAppender<?>, OutputStream> previous = new IdentityHashMap<>();

	private DetachedConsoleStreams(final Map<OutputStreamAppender<?>, OutputStream> previous) {
		this.previous.putAll(previous);
	}

	/**
	 * Rebinds every {@link OutputStreamAppender} currently attached to any logger in the
	 * given context: appenders targeting the current {@code System.out} go to the same
	 * stream that was there before interception, appenders targeting the current
	 * {@code System.err} go to the same stream. Appenders on other streams (files,
	 * sockets) are left untouched.
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
			repointConsole(console, consoleOut, consoleErr, saved);
			return;
		}
		if (current == consoleOut) {
			saved.put(streamAppender, current);
			try {
				setOutputStreamField(streamAppender, consoleOut);
			}
			catch (final ReflectiveOperationException ex) {
				saved.remove(streamAppender);
			}
		}
		else if (current == consoleErr) {
			saved.put(streamAppender, current);
			try {
				setOutputStreamField(streamAppender, consoleErr);
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
	 * @param consoleOut the original stdout stream captured before interception
	 * @param consoleErr the original stderr stream captured before interception
	 * @param saved map remembering each repointed appender's previous stream
	 */
	private static void repointConsole(final ConsoleAppender<?> console, final OutputStream consoleOut,
			final OutputStream consoleErr, final Map<OutputStreamAppender<?>, OutputStream> saved) {
		final OutputStream current = console.getOutputStream();
		final OutputStream replacement;
		if (current == ConsoleTarget.SystemOut.getStream()) {
			replacement = consoleOut;
		}
		else if (current == ConsoleTarget.SystemErr.getStream()) {
			replacement = consoleErr;
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
