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
package io.inspector.mcp.demo.stress;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.inspector.mcp.demo.proxy.ProxyAppHarness;
import io.inspector.mcp.demo.tools.SlowEchoToolConfiguration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct stress on the demo MCP server itself (no proxy): concurrent {@code slowEcho}
 * {@code tools/call}s on one session must never execute on the reactive stack's Netty
 * event loops (issue #377). A tool body parked on a loop blocks the thread that also
 * drives the transport, capping overlap at the loop count instead of the worker pool.
 *
 * <p>
 * The non-blocking-thread witness ({@code Schedulers#isInNonBlockingThread()}) is
 * deterministic on any hardware: it is zero when the SDK offloads sync tool bodies to
 * {@code boundedElastic} (the default, and what {@code immediateExecution(true)}
 * suppresses), and equals {@link #CALLS} when a customizer turns immediate execution on
 * for the reactive stack. It therefore detects the issue #377 defect even on a many-core
 * machine, where the loop count alone could absorb the whole burst.
 */
@Epic("Demo MCP Server")
@Feature("Concurrent slowEcho on the demo server")
class DirectConcurrentToolCallsIT {

	private static final int CALLS = 10;

	/** Per-call ceiling: above one 2s sleep plus generous session setup headroom. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private ConfigurableApplicationContext app;

	@AfterEach
	void stopApp() {
		if (this.app != null) {
			try {
				this.app.close();
			}
			catch (Exception ignored) {
				/* best-effort */
			}
			this.app = null;
		}
	}

	@Test
	@DisplayName("10 concurrent slowEcho calls never run on the Netty event loop")
	@Story("Demo tool threading")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Boots the demo app (STREAMABLE), opens one direct /mcp session and fires 10 concurrent "
			+ "2s slowEcho calls. The server-side witness must report zero executions on a "
			+ "reactor-http-nio event-loop thread and at least 2 overlapping executions (issue #377)")
	void tenConcurrentSlowEchoes_direct_neverBlockTheEventLoop() throws Exception {
		// given
		this.app = ProxyAppHarness.start("STREAMABLE", false, null);
		int port = ProxyAppHarness.port(this.app);
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build())
			.requestTimeout(REQUEST_TIMEOUT)
			.initializationTimeout(Duration.ofSeconds(10))
			.build()) {
			client.initialize();
			// The burst below is the only measured window: reset the witness after
			// session setup so its counters carry nothing from setup traffic.
			SlowEchoToolConfiguration.resetInFlightWitness();

			ExecutorService pool = Executors.newFixedThreadPool(CALLS);
			try {
				List<CompletableFuture<CallToolResult>> futures = new ArrayList<>(CALLS);

				// when
				for (int i = 0; i < CALLS; i++) {
					final int idx = i;
					futures.add(CompletableFuture.supplyAsync(
							() -> client.callTool(new CallToolRequest("slowEcho", Map.of("text", "echo-" + idx))),
							pool));
				}
				CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(35, TimeUnit.SECONDS);

				System.err.printf("[DIAG] %s maxInFlight=%d loopExec=%d%n", ProxyAppHarness.stack(),
						SlowEchoToolConfiguration.maxInFlightWitness(),
						SlowEchoToolConfiguration.loopThreadExecutionsWitness());
				// then
				assertThat(SlowEchoToolConfiguration.loopThreadExecutionsWitness())
					.as("slowEcho executions on a non-blocking (event-loop) thread on %s - a "
							+ "blocking tool body on the loop is the issue #377 defect", ProxyAppHarness.stack())
					.isZero();
				assertThat(SlowEchoToolConfiguration.maxInFlightWitness())
					.as("server-side max in-flight slowEcho on %s - a serialized session never exceeds 1",
							ProxyAppHarness.stack())
					.isGreaterThanOrEqualTo(2);
				for (int i = 0; i < CALLS; i++) {
					assertThat(futures.get(i).join().isError())
						.as("slowEcho[%d] must succeed on %s", i, ProxyAppHarness.stack())
						.isNotEqualTo(Boolean.TRUE);
				}
			}
			finally {
				pool.shutdownNow();
				pool.awaitTermination(2, TimeUnit.SECONDS);
			}
		}
	}

}
