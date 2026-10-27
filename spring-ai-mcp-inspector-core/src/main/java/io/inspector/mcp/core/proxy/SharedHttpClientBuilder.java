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

import java.net.http.HttpClient;

/**
 * An {@link HttpClient.Builder} that always returns the same shared {@link HttpClient}
 * instance from {@link #build()}.
 *
 * <p>
 * The MCP SDK's transport builders accept an {@code HttpClient.Builder}, never a finished
 * client, and give no accessor to the client they built - so a wrapper that needs to
 * issue its own probe request on the same client (same selector thread, same connection
 * pool, same HTTP version) has no supported way to obtain it. Handing the SDK this
 * builder closes that gap: the transport's own
 * {@code clientBuilder(...).connectTimeout(...).build()} chain ends in {@link #build()},
 * which returns the pre-built shared client instead of allocating a new one.
 *
 * <p>
 * Every mutating method on the builder interface returns {@code this} for chaining and
 * intentionally ignores its argument: the shared client is configured once, by its owner,
 * before any transport sees this builder. The one call the SDK always makes,
 * {@code connectTimeout(...)}, is likewise absorbed.
 *
 * <p>
 * This keeps the probe on the delegate's client lifecycle: no second selector thread per
 * proxy session (measured on this codebase: a dedicated probe client per session doubled
 * the surviving {@code SelectorManager} thread count and the threads outlived
 * {@code closeGracefully()}).
 *
 * @author Artem Simeshin
 */
final class SharedHttpClientBuilder implements HttpClient.Builder {

	private final HttpClient shared;

	SharedHttpClientBuilder(final HttpClient shared) {
		this.shared = shared;
	}

	/**
	 * The client handed to every caller.
	 * @return the shared client this builder wraps
	 */
	HttpClient sharedClient() {
		return this.shared;
	}

	@Override
	public HttpClient.Builder cookieHandler(final java.net.CookieHandler cookieHandler) {
		return this;
	}

	@Override
	public HttpClient.Builder connectTimeout(final java.time.Duration duration) {
		return this;
	}

	@Override
	public HttpClient.Builder sslContext(final javax.net.ssl.SSLContext sslContext) {
		return this;
	}

	@Override
	public HttpClient.Builder sslParameters(final javax.net.ssl.SSLParameters sslParameters) {
		return this;
	}

	@Override
	public HttpClient.Builder executor(final java.util.concurrent.Executor executor) {
		return this;
	}

	@Override
	public HttpClient.Builder followRedirects(final HttpClient.Redirect policy) {
		return this;
	}

	@Override
	public HttpClient.Builder version(final HttpClient.Version version) {
		return this;
	}

	@Override
	public HttpClient.Builder priority(final int priority) {
		return this;
	}

	@Override
	public HttpClient.Builder proxy(final java.net.ProxySelector proxySelector) {
		return this;
	}

	@Override
	public HttpClient.Builder authenticator(final java.net.Authenticator authenticator) {
		return this;
	}

	@Override
	public HttpClient build() {
		return this.shared;
	}

}
