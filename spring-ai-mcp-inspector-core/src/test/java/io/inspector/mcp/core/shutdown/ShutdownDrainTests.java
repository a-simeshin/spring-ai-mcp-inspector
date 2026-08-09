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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of {@link ShutdownDrain} is that shutdown cost stops depending on how
 * many sessions are open or how badly any one of them is stuck.
 *
 * <p>
 * Serially, four inspector tabs whose {@code McpSyncClient.closeGracefully()} wedges cost
 * 4 &times; the SDK's hard-coded 10s on the shutdown thread — worse than the fixed
 * graceful timeout this library set out to remove. The tests below wedge closes forever
 * and pin the two properties that make that safe: the drain returns inside its budget,
 * and the threads it leaves behind are daemons so they cannot hold the JVM open.
 */
@Epic("MCP Inspector Core")
@Feature("Shutdown drain")
class ShutdownDrainTests {

	@Test
	@DisplayName("returns inside its budget however many tasks are wedged")
	@Story("Bounded teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Drains four tasks that block forever and asserts the call returned close to its 500ms "
			+ "budget rather than to the sum of the tasks' waits")
	void drain_whenEveryTaskIsWedged_returnsInsideBudget() {
		final CountDownLatch started = new CountDownLatch(4);
		final List<Runnable> wedged = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			wedged.add(() -> {
				started.countDown();
				block();
			});
		}

		final long startedAt = System.nanoTime();
		ShutdownDrain.drain("wedged task", Duration.ofMillis(500), wedged);
		final long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(elapsedMs).as("drain took %d ms against a 500ms budget", elapsedMs).isLessThan(3_000L);
		assertThat(started.getCount()).as("every task actually started").isZero();
	}

	@Test
	@DisplayName("abandoned tasks run on daemon threads, so the JVM can still exit")
	@Story("Bounded teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("A task left running past the deadline must not keep a test JVM (or an application) alive; "
			+ "asserts the thread the drain gave it is a daemon")
	void drain_whenTaskOutlivesBudget_leavesOnlyDaemonThreads() throws Exception {
		final CountDownLatch running = new CountDownLatch(1);
		final List<Thread> threads = new ArrayList<>();

		ShutdownDrain.drain("wedged task", Duration.ofMillis(200), List.of(() -> {
			synchronized (threads) {
				threads.add(Thread.currentThread());
			}
			running.countDown();
			block();
		}));

		assertThat(running.await(5, TimeUnit.SECONDS)).as("task started").isTrue();
		synchronized (threads) {
			assertThat(threads).hasSize(1);
			assertThat(threads.get(0).isDaemon()).as("drain worker %s is a daemon", threads.get(0).getName()).isTrue();
		}
	}

	@Test
	@DisplayName("one failing task does not stop the others")
	@Story("Bounded teardown")
	@Severity(SeverityLevel.NORMAL)
	@Description("A task that throws is logged and swallowed; the remaining tasks still run, because a "
			+ "failure escaping a ContextClosedEvent listener would abort the rest of context close")
	void drain_whenOneTaskThrows_stillRunsTheRest() {
		final AtomicInteger completed = new AtomicInteger();

		ShutdownDrain.drain("task", Duration.ofSeconds(5), List.of(() -> {
			throw new IllegalStateException("boom");
		}, completed::incrementAndGet, completed::incrementAndGet));

		assertThat(completed).hasValue(2);
	}

	@Test
	@DisplayName("an empty batch costs nothing")
	@Story("Bounded teardown")
	@Severity(SeverityLevel.MINOR)
	@Description("No tasks means no thread pool is created at all — the common case for an application "
			+ "with no open inspector sessions")
	void drain_whenNoTasks_returnsImmediately() {
		final long startedAt = System.nanoTime();

		ShutdownDrain.drain("task", Duration.ofSeconds(5), List.of());

		assertThat((System.nanoTime() - startedAt) / 1_000_000L).isLessThan(1_000L);
	}

	/** Blocks until interrupted — stands in for a wedged {@code closeGracefully()}. */
	private static void block() {
		try {
			new CountDownLatch(1).await();
		}
		catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
