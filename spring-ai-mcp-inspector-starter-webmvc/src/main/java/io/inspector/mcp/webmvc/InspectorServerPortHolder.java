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

package io.inspector.mcp.webmvc;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

/**
 * Captures the actual port the embedded servlet container picked at startup so the
 * loopback inspector client can target it regardless of whether {@code server.port} was
 * {@code 0} (random) or a fixed value.
 *
 * @author Artem Simeshin
 */
public class InspectorServerPortHolder {

	private volatile int port = -1;

	@EventListener
	public void onWebServerInitialized(final WebServerInitializedEvent event) {
		this.port = event.getWebServer().getPort();
	}

	/**
	 * Returns the captured port. Falls back to {@code 8080} if the embedded server has
	 * not yet fired the initialization event.
	 * @return the captured server port, or {@code 8080} if not yet initialized
	 */
	public int port() {
		final int snapshot = this.port;
		return (snapshot > 0) ? snapshot : 8080;
	}

	/**
	 * Test-only setter.
	 * @param port the port to store
	 */
	void setPort(final int port) {
		this.port = port;
	}

}
