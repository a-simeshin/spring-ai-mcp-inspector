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

import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SharedHttpClientBuilder}: every builder method returns the builder for
 * chaining (absorbing its argument) and {@code build()} always returns the one shared
 * client instance.
 */
class SharedHttpClientBuilderTests {

	@Test
	@DisplayName("build() returns the shared client instance")
	void buildReturnsSharedClient() {
		final HttpClient shared = HttpClient.newBuilder().build();
		final SharedHttpClientBuilder builder = new SharedHttpClientBuilder(shared);
		assertThat(builder.build()).isSameAs(shared);
		assertThat(builder.sharedClient()).isSameAs(shared);
	}

	@Test
	@DisplayName("every mutating method returns the same builder and build() is unaffected")
	void mutatingMethodsChainAndAreAbsorbed() throws Exception {
		final HttpClient shared = HttpClient.newBuilder().build();
		final SharedHttpClientBuilder builder = new SharedHttpClientBuilder(shared);
		assertThat(builder.cookieHandler(CookieHandler.getDefault())).isSameAs(builder);
		assertThat(builder.connectTimeout(Duration.ofSeconds(1))).isSameAs(builder);
		assertThat(builder.sslContext(SSLContext.getDefault())).isSameAs(builder);
		assertThat(builder.sslParameters(new SSLParameters())).isSameAs(builder);
		assertThat(builder.executor(Runnable::run)).isSameAs(builder);
		assertThat(builder.followRedirects(HttpClient.Redirect.NEVER)).isSameAs(builder);
		assertThat(builder.version(HttpClient.Version.HTTP_2)).isSameAs(builder);
		assertThat(builder.priority(1)).isSameAs(builder);
		assertThat(builder.proxy(ProxySelector.of(null))).isSameAs(builder);
		assertThat(builder.authenticator(null)).isSameAs(builder);
		// The absorbed settings must not change the built client: still the shared one,
		// still on the configuration its owner chose.
		assertThat(builder.build()).isSameAs(shared);
	}

	@Test
	@DisplayName("the SDK build() chain ending in the shared builder returns the shared client")
	void sdkStyleChainReturnsSharedClient() throws Exception {
		final HttpClient shared = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.executor(Runnable::run)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		// Exactly what the SDK's Builder.build() does to the builder it was handed.
		final HttpClient built = new SharedHttpClientBuilder(shared).connectTimeout(Duration.ofSeconds(10)).build();
		assertThat(built).isSameAs(shared);
	}

}
