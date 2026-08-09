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

import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins <em>when</em> {@link ProxySessionRegistry} tears its sessions down, not merely
 * that it can.
 *
 * <p>
 * Boot's {@code WebServerGracefulShutdownLifecycle} blocks context close until every
 * in-flight request finishes or {@code spring.lifecycle.timeout-per-shutdown-phase}
 * expires. A proxy session holds an SSE request open, so the registry has to be drained
 * <em>before</em> that phase runs — which means on {@code ContextClosedEvent}, the very
 * first step of {@code AbstractApplicationContext.doClose()}. Bean destruction
 * ({@code @PreDestroy}, {@code DisposableBean}, {@code destroyMethod}) happens after
 * every lifecycle phase and is therefore too late.
 *
 * <p>
 * The probe below stops one phase above the web-server graceful phase. Lifecycle beans
 * are stopped in descending phase order, so the probe observes exactly the registry state
 * Boot's graceful shutdown would see.
 */
@Epic("MCP Inspector Core")
@Feature("Proxy session registry")
class ProxySessionRegistryShutdownOrderingTests {

	@Test
	@DisplayName("sessions are closed before the web-server graceful-shutdown phase")
	@Story("Shutdown ordering")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Closes a context holding one proxy session and asserts a SmartLifecycle probe sitting "
			+ "just above WebServerGracefulShutdownLifecycle already sees an empty registry — proving the "
			+ "teardown runs on ContextClosedEvent and not during bean destruction")
	void contextClose_drainsRegistryBeforeGracefulShutdownPhase() {
		final AtomicInteger sizeSeenByProbe = new AtomicInteger(-1);
		final GenericApplicationContext context = new GenericApplicationContext();
		// The registry listens via @EventListener rather than
		// ApplicationListener<ContextClosedEvent> so that subclasses keep their one
		// permitted parameterisation of that interface. Annotated listeners need
		// EventListenerMethodProcessor, which every Spring Boot context registers and a
		// bare GenericApplicationContext does not.
		AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
		context.registerBean(ProxySessionRegistry.class);
		context.registerBean(GracefulPhaseProbe.class,
				() -> new GracefulPhaseProbe(context.getBean(ProxySessionRegistry.class), sizeSeenByProbe));
		context.refresh();

		final ProxySessionRegistry registry = context.getBean(ProxySessionRegistry.class);
		final McpClientTransport transport = mock(McpClientTransport.class);
		given(transport.closeGracefully()).willReturn(Mono.empty());
		final Sinks.Many<JsonNode> browserToTarget = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JsonNode> targetToBrowser = Sinks.many().replay().limit(8);
		registry.put(new ProxySession("s1", transport, browserToTarget, targetToBrowser));
		assertThat(registry.size()).isOne();

		context.close();

		assertThat(sizeSeenByProbe).as("registry size observed at the graceful-shutdown phase").hasValue(0);
		assertThat(registry.size()).isZero();
		verify(transport).closeGracefully();
		StepVerifier.create(targetToBrowser.asFlux()).verifyComplete();
	}

	/**
	 * Stops one phase above {@code WebServerGracefulShutdownLifecycle}. Because
	 * {@code DefaultLifecycleProcessor} stops beans in descending phase order, this runs
	 * immediately before Boot would start waiting for in-flight requests.
	 */
	private static final class GracefulPhaseProbe implements SmartLifecycle {

		private final ProxySessionRegistry registry;

		private final AtomicInteger observed;

		private volatile boolean running;

		GracefulPhaseProbe(final ProxySessionRegistry registry, final AtomicInteger observed) {
			this.registry = registry;
			this.observed = observed;
		}

		@Override
		public int getPhase() {
			return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1;
		}

		@Override
		public void start() {
			this.running = true;
		}

		@Override
		public void stop() {
			this.observed.set(this.registry.size());
			this.running = false;
		}

		@Override
		public boolean isRunning() {
			return this.running;
		}

	}

}
