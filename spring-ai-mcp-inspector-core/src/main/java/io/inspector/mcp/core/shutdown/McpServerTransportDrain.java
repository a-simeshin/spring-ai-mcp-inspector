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
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

import io.inspector.mcp.core.config.McpInspectorProperties;

/**
 * Closes the host application's own MCP server transport provider at the start of context
 * close.
 *
 * <h2>Why this exists</h2>
 *
 * <p>
 * A live MCP session pins an inbound {@code GET /mcp} (or {@code GET /sse}) response open
 * on the server side. That response is completed only when spring-ai's transport provider
 * closes the session's stream, and spring-ai registers the provider as a plain
 * {@code @Bean} with no lifecycle and no {@code destroyMethod}. Spring therefore infers
 * {@link McpServerTransportProviderBase#close()} as the destroy method — and that default
 * is literally {@code closeGracefully().subscribe()}, fire-and-forget, run during
 * {@code destroyBeans()}. Bean destruction is the <em>last</em> step of
 * {@code AbstractApplicationContext.doClose()}, long after
 * {@code WebServerGracefulShutdownLifecycle} (phase 2147482623) has already finished
 * waiting for in-flight requests. So the stream that keeps the graceful phase busy is
 * released only after the whole {@code spring.lifecycle.timeout-per-shutdown-phase} has
 * been paid. Same ordering bug this library fixed for its own streams in #28, one library
 * over.
 *
 * <p>
 * Draining the provider here — on {@link ContextClosedEvent}, the first step of
 * {@code doClose()} — releases those streams before the wait starts. Measured on this
 * repo's demo app with an inspector session open and spring-ai's client-side
 * {@code DELETE /mcp} round trip disabled ({@code disallow-delete=true}, which is what
 * exposes the hole): context close 17.1s &rarr; 28ms on webmvc, 17.1s &rarr; 7ms on
 * webflux. With {@code protocol=SSE}, where no {@code DELETE} exists at all: 17.0s &rarr;
 * single-digit ms on both stacks.
 *
 * <h2>Cost, and the way out of it</h2>
 *
 * <p>
 * This closes sessions earlier than spring-ai would, so an MCP tool call still streaming
 * its response when {@code SIGTERM} arrives gets cut instead of being given the graceful
 * window. For the case that actually occurs — an idle listening stream, which never
 * drains on its own and can only ever burn the timeout — the trade is clearly right, so
 * it is on by default. Applications with legitimately long-running tool calls turn it off
 * with {@code spring.ai.mcp.inspector.shutdown.close-mcp-server-transports=false}.
 *
 * <p>
 * <h2>When to delete this class</h2>
 *
 * <p>
 * The moment spring-ai does either of the following it becomes a no-op that still costs a
 * bean, and should go:
 *
 * <ul>
 * <li>registers the transport provider as a {@code SmartLifecycle} whose phase is above
 * {@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE} (2147482623), so it
 * stops before the graceful wait instead of after it; or</li>
 * <li>drains the provider itself on {@code ContextClosedEvent}.</li>
 * </ul>
 *
 * <p>
 * Tracked for removal by
 * <a href="https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/33">issue 33</a>.
 * A workaround for another project's bug, with no issue tracking its removal, becomes
 * permanent by default.
 *
 * @author Artem Simeshin
 */
public class McpServerTransportDrain
		implements ApplicationListener<ContextClosedEvent>, ApplicationContextAware, Ordered {

	/**
	 * Upper bound on the wait for the provider to release its sessions. Matches
	 * {@code ProxySession}'s upstream close budget rather than introducing a second
	 * number; measured cost across webmvc/webflux &times; SSE/STREAMABLE is 0-7ms, so
	 * this is a safety net, not a tuning knob.
	 */
	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

	private static final Logger LOG = LoggerFactory.getLogger(McpServerTransportDrain.class);

	private final ObjectProvider<McpServerTransportProviderBase> providers;

	private final McpInspectorProperties properties;

	private final AtomicBoolean drained = new AtomicBoolean(false);

	/** The context this bean belongs to; {@code null} when built outside a container. */
	private ApplicationContext applicationContext;

	/**
	 * Creates a drain for the given providers.
	 * @param providers the MCP server transport providers in this context, if any
	 * @param properties the inspector properties, read at event time so that a
	 * programmatic change to {@code shutdown.close-mcp-server-transports} takes effect;
	 * {@code null} means "enabled", the binding default
	 */
	public McpServerTransportDrain(final ObjectProvider<McpServerTransportProviderBase> providers,
			final McpInspectorProperties properties) {
		this.providers = providers;
		this.properties = properties;
	}

	/**
	 * Records the owning context so {@link #onApplicationEvent(ContextClosedEvent)} can
	 * tell this context's close from a child's.
	 * @param applicationContext the context this bean was created in
	 */
	@Override
	public void setApplicationContext(final ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Runs after the inspector's own session drains.
	 *
	 * <p>
	 * Settled by measurement rather than preference, because draining the provider first
	 * would tear down the server side of a proxy session's upstream leg while the
	 * browser-facing SSE stream is still subscribed — and {@code GracefulShutdownIT}
	 * asserts that stream "ended cleanly rather than being cut". Both orderings were run
	 * against the whole class: both stayed green and the class wall time was the same to
	 * within run-to-run noise. With no measured reason to prefer the riskier order, the
	 * provider goes last, so the inspector completes its own sinks before anything
	 * external disturbs them.
	 * @return {@link Ordered#LOWEST_PRECEDENCE}
	 */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public void onApplicationEvent(final ContextClosedEvent event) {
		// publishEvent forwards to the parent, so a management child context closing
		// delivers this event to a parent that is still serving. Draining then would
		// kill the host's live MCP sessions and, because of the latch below, never let
		// them come back.
		if (this.applicationContext != null && event.getApplicationContext() != this.applicationContext) {
			return;
		}
		if (this.properties != null && !this.properties.getShutdown().isCloseMcpServerTransports()) {
			return;
		}
		// The same event can still reach us twice (a child that shares this listener).
		// The providers' own closeGracefully() clears state on success and would very
		// likely tolerate it, but "very likely" is not a reason to find out during
		// shutdown.
		if (!this.drained.compareAndSet(false, true)) {
			return;
		}
		this.providers.orderedStream().forEach(this::close);
	}

	private void close(final McpServerTransportProviderBase provider) {
		try {
			// closeGracefully() is cold — block() is both the subscription and the bound.
			// A bare subscribe() would race the graceful phase it is meant to beat.
			provider.closeGracefully().block(CLOSE_TIMEOUT);
		}
		catch (final Throwable ex) {
			// Throwable, not RuntimeException: context close can be driven from a reactor
			// thread, where block() throws IllegalStateException, and an exception
			// escaping
			// a ContextClosedEvent listener aborts the rest of context close.
			LOG.warn("Failed to close MCP server transport {} on shutdown", provider.getClass().getSimpleName(), ex);
		}
	}

}
