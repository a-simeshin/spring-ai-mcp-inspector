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

package io.inspector.mcp.webflux.it;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Test seam: records the calling thread name in {@link #send} and delegates to a real
 * {@link HttpClient}. Used by thread-evidence offload tests.
 */
public class ThreadEvidenceHttpClient extends HttpClient {

	private final HttpClient delegate;

	private final AtomicReference<String> recordedThreadName = new AtomicReference<>();

	private final AtomicInteger requestCount = new AtomicInteger();

	public ThreadEvidenceHttpClient(final HttpClient delegate) {
		this.delegate = delegate;
	}

	@Override
	public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
			throws IOException, InterruptedException {
		this.recordedThreadName.set(Thread.currentThread().getName());
		this.requestCount.incrementAndGet();
		return this.delegate.send(request, handler);
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
			final HttpResponse.BodyHandler<T> handler) {
		return this.delegate.sendAsync(request, handler);
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
			final HttpResponse.BodyHandler<T> handler, final PushPromiseHandler<T> pushPromiseHandler) {
		return this.delegate.sendAsync(request, handler, pushPromiseHandler);
	}

	@Override
	public Optional<CookieHandler> cookieHandler() {
		return this.delegate.cookieHandler();
	}

	@Override
	public Optional<Duration> connectTimeout() {
		return this.delegate.connectTimeout();
	}

	@Override
	public Redirect followRedirects() {
		return this.delegate.followRedirects();
	}

	@Override
	public Optional<ProxySelector> proxy() {
		return this.delegate.proxy();
	}

	@Override
	public SSLContext sslContext() {
		return this.delegate.sslContext();
	}

	@Override
	public SSLParameters sslParameters() {
		return this.delegate.sslParameters();
	}

	@Override
	public Optional<Authenticator> authenticator() {
		return this.delegate.authenticator();
	}

	@Override
	public Version version() {
		return this.delegate.version();
	}

	@Override
	public Optional<Executor> executor() {
		return this.delegate.executor();
	}

	public String recordedThreadName() {
		return this.recordedThreadName.get();
	}

	public int requestCount() {
		return this.requestCount.get();
	}

}
