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

package io.inspector.mcp.core.shutdown;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a batch of close tasks under one wall-clock deadline.
 *
 * <p>
 * Every session teardown the inspector performs on {@code ContextClosedEvent} blocks:
 * {@code McpSyncClient.closeGracefully()} waits up to the SDK's hard-coded 10s, and the
 * SDK exposes no way to shorten it. Draining N sessions serially therefore costs up to
 * N&nbsp;&times;&nbsp;10s on the shutdown thread with nothing bounding N — four wedged
 * inspector tabs already exceed a typical 30s termination grace period. Trading the
 * original bug (one fixed graceful timeout) for an unbounded wait would be a worse bug,
 * so the tasks run in parallel against a single deadline instead.
 *
 * <p>
 * {@link ExecutorService#invokeAll(Collection, long, TimeUnit)} is the whole mechanism:
 * it returns at the deadline and cancels whatever has not finished. Wall clock is
 * therefore bounded by {@code budget} regardless of how many tasks there are, or how
 * badly any one of them is wedged. Worker threads are daemons and the pool is
 * {@link ExecutorService#shutdownNow() shutdownNow}-ed on the way out, so an abandoned
 * task can never hold a JVM open past its deadline.
 *
 * <p>
 * Public only because both starter modules call it — internal plumbing, not API.
 *
 * @author Artem Simeshin
 */
public final class ShutdownDrain {

	private static final Logger LOG = LoggerFactory.getLogger(ShutdownDrain.class);

	private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

	private ShutdownDrain() {
	}

	/**
	 * Runs every task in parallel and returns once they have all finished or
	 * {@code budget} has elapsed, whichever comes first.
	 *
	 * <p>
	 * Never throws: a task that fails is logged and the rest still run. The calling
	 * thread's interrupt flag is restored if the wait is interrupted.
	 * @param what short label for log messages, e.g. {@code "inspector session"}
	 * @param budget upper bound on the total wall-clock time spent here
	 * @param tasks the close tasks; {@code null} or empty returns immediately
	 */
	public static void drain(final String what, final Duration budget, final Collection<Runnable> tasks) {
		if (tasks == null || tasks.isEmpty()) {
			return;
		}
		final List<Callable<Void>> callables = tasks.stream().map(ShutdownDrain::toCallable).toList();
		final ExecutorService pool = Executors.newCachedThreadPool(daemonFactory());
		try {
			pool.invokeAll(callables, budget.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (final RuntimeException ex) {
			LOG.warn("Failed to drain {}s on shutdown", what, ex);
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static Callable<Void> toCallable(final Runnable task) {
		return () -> {
			try {
				task.run();
			}
			catch (final Throwable ex) {
				// An exception escaping here would only be reported through a Future
				// nobody reads, and must never abort the rest of the drain.
				LOG.warn("Shutdown task failed", ex);
			}
			return null;
		};
	}

	private static ThreadFactory daemonFactory() {
		return (runnable) -> {
			final Thread thread = new Thread(runnable, "inspector-shutdown-" + THREAD_SEQ.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

}
