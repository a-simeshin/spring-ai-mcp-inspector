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

import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.Ordered;
import reactor.core.publisher.Mono;

import io.inspector.mcp.core.config.McpInspectorProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link McpServerTransportDrain}.
 *
 * <p>
 * The behaviour that matters is narrow but easy to get wrong: {@code closeGracefully()}
 * is a cold {@code Mono}, so the drain has to subscribe (and wait) rather than fire and
 * forget; a duplicate {@code ContextClosedEvent} from a management child context must not
 * re-run it; and nothing it does may throw, because an exception escaping a
 * {@code ContextClosedEvent} listener aborts the rest of context close.
 */
@Epic("MCP Inspector Core")
@Feature("Shutdown drain")
class McpServerTransportDrainTests {

	private static final ContextClosedEvent EVENT = new ContextClosedEvent(new StaticApplicationContext());

	@Test
	@DisplayName("subscribes to the provider's cold closeGracefully() Mono")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("closeGracefully() does nothing until subscribed; the drain must block on it, not merely "
			+ "call it, or the graceful phase starts before the session streams are released")
	void onContextClosed_subscribesToCloseGracefully() {
		final AtomicInteger subscriptions = new AtomicInteger();
		final McpServerTransportProviderBase provider = mock(McpServerTransportProviderBase.class);
		given(provider.closeGracefully())
			.willReturn(Mono.<Void>empty().doOnSubscribe((subscription) -> subscriptions.incrementAndGet()));

		drainOf(provider).onApplicationEvent(EVENT);

		assertThat(subscriptions).as("closeGracefully() subscriptions").hasValue(1);
	}

	@Test
	@DisplayName("a republished ContextClosedEvent drains only once")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.NORMAL)
	@Description("An actuator management child context republishes ContextClosedEvent to its parent, so the "
			+ "listener fires twice; the second pass is skipped rather than relying on the provider being "
			+ "idempotent")
	void onContextClosed_whenEventRepublished_drainsOnce() {
		final AtomicInteger subscriptions = new AtomicInteger();
		final McpServerTransportProviderBase provider = mock(McpServerTransportProviderBase.class);
		given(provider.closeGracefully())
			.willReturn(Mono.<Void>empty().doOnSubscribe((subscription) -> subscriptions.incrementAndGet()));
		final McpServerTransportDrain drain = drainOf(provider);

		drain.onApplicationEvent(EVENT);
		drain.onApplicationEvent(EVENT);

		assertThat(subscriptions).hasValue(1);
	}

	@Test
	@DisplayName("a provider that blows up does not abort context close")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.CRITICAL)
	@Description("Catches Throwable, not just RuntimeException: context close can be driven from a reactor "
			+ "thread where block() throws IllegalStateException, and an escaping exception would skip every "
			+ "later step of doClose()")
	void onContextClosed_whenProviderThrows_swallowsAndContinues() {
		final McpServerTransportProviderBase failing = mock(McpServerTransportProviderBase.class);
		given(failing.closeGracefully()).willThrow(new IllegalStateException("block() on a non-blocking thread"));

		assertThatCode(() -> drainOf(failing).onApplicationEvent(EVENT)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("no MCP server in the context is a no-op")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.NORMAL)
	@Description("The inspector may point only at a remote target, or the host may set "
			+ "spring.ai.mcp.server.enabled=false; with no provider bean the listener does nothing")
	void onContextClosed_whenNoProviders_doesNothing() {
		assertThatCode(() -> drainOf().onApplicationEvent(EVENT)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("runs after the inspector's own session drains")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.NORMAL)
	@Description("Ordering was settled by measurement: draining the provider last keeps the browser-facing "
			+ "proxy SSE stream completing cleanly instead of being cut mid-flight")
	void getOrder_isLowestPrecedence() {
		assertThat(drainOf().getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	@DisplayName("a child context closing leaves the running parent's transport alone")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.BLOCKER)
	@Description("publishEvent forwards every event to the parent, so an actuator management child context "
			+ "closing delivers ContextClosedEvent to a parent that is still serving; draining then would cut "
			+ "the host's live MCP sessions and the one-shot latch would never let them come back")
	void onContextClosed_whenAChildContextCloses_doesNotDrain() {
		final AtomicInteger subscriptions = new AtomicInteger();
		final McpServerTransportProviderBase provider = mock(McpServerTransportProviderBase.class);
		given(provider.closeGracefully())
			.willReturn(Mono.<Void>empty().doOnSubscribe((subscription) -> subscriptions.incrementAndGet()));
		final StaticApplicationContext parent = new StaticApplicationContext();
		final StaticApplicationContext child = new StaticApplicationContext(parent);
		final McpServerTransportDrain drain = drainOf(provider);
		drain.setApplicationContext(parent);

		drain.onApplicationEvent(new ContextClosedEvent(child));

		assertThat(subscriptions).as("closeGracefully() subscriptions after the CHILD closed").hasValue(0);

		// ... and the parent's own close still works afterwards: the skip must not latch.
		drain.onApplicationEvent(new ContextClosedEvent(parent));
		assertThat(subscriptions).as("closeGracefully() subscriptions after the PARENT closed").hasValue(1);
	}

	@Test
	@DisplayName("close-mcp-server-transports=false disables the drain")
	@Story("Server transport teardown")
	@Severity(SeverityLevel.NORMAL)
	@Description("The switch is read from the bound properties at event time, so the accessor is the single "
			+ "source of truth rather than a field nothing consults")
	void onContextClosed_whenDisabledByProperties_doesNotDrain() {
		final AtomicInteger subscriptions = new AtomicInteger();
		final McpServerTransportProviderBase provider = mock(McpServerTransportProviderBase.class);
		given(provider.closeGracefully())
			.willReturn(Mono.<Void>empty().doOnSubscribe((subscription) -> subscriptions.incrementAndGet()));
		final McpInspectorProperties properties = new McpInspectorProperties();
		properties.getShutdown().setCloseMcpServerTransports(false);

		new McpServerTransportDrain(providerOf(provider), properties).onApplicationEvent(EVENT);

		assertThat(subscriptions).as("closeGracefully() subscriptions").hasValue(0);
	}

	private static McpServerTransportDrain drainOf(final McpServerTransportProviderBase... providers) {
		return new McpServerTransportDrain(providerOf(providers), new McpInspectorProperties());
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<McpServerTransportProviderBase> providerOf(
			final McpServerTransportProviderBase... providers) {
		final ObjectProvider<McpServerTransportProviderBase> objectProvider = mock(ObjectProvider.class);
		given(objectProvider.orderedStream()).willAnswer((invocation) -> java.util.Arrays.stream(providers));
		return objectProvider;
	}

}
