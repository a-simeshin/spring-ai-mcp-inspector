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

package io.inspector.mcp.core.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LegacySessionAccessWarner}: the legacy-session WARN is emitted
 * exactly once per session, no matter how many times the session is touched.
 */
@Epic("MCP Inspector Core")
@Feature("LegacySessionAccessWarner")
class LegacySessionAccessWarnerTests {

	private ProxySession session;

	private Logger warnerLogger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		final McpClientTransport transport = mock(McpClientTransport.class);
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
		this.session = new ProxySession("s-legacy", transport, browserToTarget, targetToBrowser);

		this.warnerLogger = (Logger) LoggerFactory.getLogger(LegacySessionAccessWarner.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.warnerLogger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		this.warnerLogger.detachAppender(this.appender);
	}

	private long legacyWarnCount() {
		return this.appender.list.stream()
			.filter((e) -> e.getFormattedMessage() != null
					&& e.getFormattedMessage().contains("legacy session without owner binding"))
			.count();
	}

	@Test
	@Story("Once-per-session guard")
	@Severity(SeverityLevel.CRITICAL)
	@Description("warnOnce() emits the legacy WARN exactly once for the same session, even under concurrent repeated access")
	void warnOnce_sameSession_logsExactlyOnce() throws Exception {
		// when - the same session is warned about from several threads at once
		final int callers = 8;
		final ExecutorService executor = Executors.newFixedThreadPool(callers);
		final CountDownLatch start = new CountDownLatch(1);
		final List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < callers; i++) {
			futures.add(executor.submit(() -> {
				try {
					assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				}
				catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				LegacySessionAccessWarner.warnOnce(this.session);
				LegacySessionAccessWarner.warnOnce(this.session);
			}));
		}
		start.countDown();
		try {
			for (final Future<?> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		// then - exactly one WARN was emitted for the session
		assertThat(legacyWarnCount()).isEqualTo(1);
	}

	@Test
	@Story("Once-per-session guard")
	@Severity(SeverityLevel.NORMAL)
	@Description("warnOnce() treats distinct sessions independently")
	void warnOnce_distinctSessions_logIndependently() {
		// given
		final McpClientTransport transport = mock(McpClientTransport.class);
		final ProxySession other = new ProxySession("s-legacy-2", transport,
				Sinks.many().unicast().onBackpressureBuffer(), Sinks.many().replay().limit(8));

		// when
		LegacySessionAccessWarner.warnOnce(this.session);
		LegacySessionAccessWarner.warnOnce(other);

		// then
		assertThat(legacyWarnCount()).isEqualTo(2);
	}

}
