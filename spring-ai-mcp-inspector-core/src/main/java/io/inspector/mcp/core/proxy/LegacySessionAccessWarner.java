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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the "legacy session without owner binding" WARN exactly once per session.
 * Sessions bound to an owner never reach here; unbound (legacy, pre-auth-profile)
 * sessions stay accessible to every caller, but the warning must not flood the log on
 * every request that touches the same session.
 *
 * @author Artem Simeshin
 */
public final class LegacySessionAccessWarner {

	private static final Logger LOG = LoggerFactory.getLogger(LegacySessionAccessWarner.class);

	private LegacySessionAccessWarner() {
	}

	/**
	 * Logs the legacy-session WARN unless this session already logged it. The
	 * once-per-session marker lives on the {@link ProxySession} itself, so the guard
	 * holds across every controller and handler that touches the session.
	 * @param session the accessed legacy session (never {@code null})
	 */
	public static void warnOnce(final ProxySession session) {
		if (session.markLegacyAccessWarned()) {
			LOG.warn("proxy[{}] legacy session without owner binding accessed (sessionId={})", session.sessionId(),
					session.sessionId());
		}
	}

}
