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

package io.inspector.mcp.core.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Wraps a {@link McpClientTransport} and captures the <em>raw</em> initialize result
 * frame as it passes through {@link McpClientTransport#unmarshalFrom(Object, TypeRef)}.
 *
 * <p>
 * <strong>Why a transport wrapper and not the typed initialize result:</strong> per
 * SEP-2133 an MCP server declares protocol extensions in
 * {@code result.capabilities.extensions}. The MCP Java SDK (2.0.x) typed
 * {@link McpSchema.ServerCapabilities} record has no {@code extensions} component, so a
 * canonical declaration is silently dropped when the raw frame is converted into
 * {@link McpSchema.InitializeResult}. The session calls {@code unmarshalFrom} with the
 * raw, untyped result map just before that conversion, which is the last place the
 * canonical wire shape is still intact, so the wrapper snapshots it there.
 *
 * <p>
 * The snapshot is a best-effort side channel: it never alters, rejects or fails the
 * underlying exchange. A frame that is not a map simply leaves the capture empty, and
 * detection then reports "not supported" rather than breaking the inspector connection.
 *
 * <p>
 * The wrapper delegates every {@code McpTransport} operation to the wrapped instance and
 * exposes the captured frame through {@link #rawInitializeResult()} after
 * {@code initialize()} completed (the SDK resolves the initialize response before any
 * later request, so by the time {@code McpSyncClient#initialize()} returns the snapshot
 * is populated).
 *
 * @author Artem Simeshin
 */
public final class InitializeResultCapturingTransport implements McpClientTransport {

	private final McpClientTransport delegate;

	private final AtomicReference<Map<String, Object>> rawInitializeResult = new AtomicReference<>();

	/**
	 * Wraps the given transport.
	 * @param delegate the transport to wrap; every operation is delegated to it
	 */
	public InitializeResultCapturingTransport(final McpClientTransport delegate) {
		this.delegate = delegate;
	}

	/**
	 * Returns the captured raw initialize result frame.
	 * @return the untyped {@code result} object of the initialize response, or
	 * {@code null} when no initialize response passed through (yet)
	 */
	public Map<String, Object> rawInitializeResult() {
		return this.rawInitializeResult.get();
	}

	/**
	 * Wraps the given transport unless somebody is observing the capture.
	 * @param delegate the transport to potentially wrap
	 * @param capture the observer to hand the wrapper to; {@code null} (or an already
	 * satisfied reference) skips the wrapping entirely
	 * @return the wrapper when observing, otherwise {@code delegate} unchanged
	 */
	public static McpClientTransport wrapIfObserved(final McpClientTransport delegate,
			final AtomicReference<InitializeResultCapturingTransport> capture) {
		if (capture == null) {
			return delegate;
		}
		final InitializeResultCapturingTransport wrapped = new InitializeResultCapturingTransport(delegate);
		capture.set(wrapped);
		return wrapped;
	}

	@Override
	public Mono<Void> connect(final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		return this.delegate.connect(handler);
	}

	@Override
	public void setExceptionHandler(final Consumer<Throwable> handler) {
		this.delegate.setExceptionHandler(handler);
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
		return this.delegate.sendMessage(message);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T unmarshalFrom(final Object data, final TypeRef<T> typeRef) {
		if (isInitializeResultTypeRef(typeRef) && data instanceof Map<?, ?> raw) {
			this.rawInitializeResult.compareAndSet(null, (Map<String, Object>) raw);
		}
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	@Override
	public String toString() {
		return "InitializeResultCapturingTransport[" + this.delegate + "]";
	}

	/**
	 * Identifies the initialize-response unmarshal call. The SDK sends its well-known
	 * {@code TypeRef} constant for the initialize exchange
	 * ({@link McpAsyncClient#INITIALIZE_RESULT_TYPE_REF}); a structural comparison on the
	 * captured generic type is checked as well so a future SDK rebuild of the constant
	 * keeps the capture working.
	 * @param typeRef the type reference the session is unmarshaling into
	 * @return {@code true} when this call carries the initialize result
	 */
	private static boolean isInitializeResultTypeRef(final TypeRef<?> typeRef) {
		if (typeRef == McpAsyncClient.INITIALIZE_RESULT_TYPE_REF) {
			return true;
		}
		return McpSchema.InitializeResult.class.getName().equals(typeRef.getType().getTypeName());
	}

}
