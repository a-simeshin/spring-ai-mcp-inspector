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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StatelessSessionKey}.
 *
 * @author Artem Simeshin
 */
class StatelessSessionKeyTests {

	@Test
	void equalsAndHashCode_areValueBased() {
		final StatelessSessionKey a = new StatelessSessionKey("http://localhost:8080/mcp", "abc123");
		final StatelessSessionKey b = new StatelessSessionKey("http://localhost:8080/mcp", "abc123");
		final StatelessSessionKey c = new StatelessSessionKey("http://localhost:8080/mcp", "def456");

		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());
		assertThat(a).isNotEqualTo(c);
	}

	@Test
	void differentUrls_produceDifferentKeys() {
		final StatelessSessionKey a = new StatelessSessionKey("http://localhost:8080/mcp", "abc123");
		final StatelessSessionKey b = new StatelessSessionKey("http://localhost:9090/mcp", "abc123");

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void differentFingerprints_produceDifferentKeys() {
		final StatelessSessionKey a = new StatelessSessionKey("http://localhost:8080/mcp", "abc123");
		final StatelessSessionKey b = new StatelessSessionKey("http://localhost:8080/mcp", "def456");

		assertThat(a).isNotEqualTo(b);
	}

	@Test
	void nullServerUrl_throws() {
		assertThatThrownBy(() -> new StatelessSessionKey(null, "abc")).isInstanceOf(NullPointerException.class);
	}

	@Test
	void nullFingerprint_throws() {
		assertThatThrownBy(() -> new StatelessSessionKey("http://localhost:8080/mcp", null))
			.isInstanceOf(NullPointerException.class);
	}

}
