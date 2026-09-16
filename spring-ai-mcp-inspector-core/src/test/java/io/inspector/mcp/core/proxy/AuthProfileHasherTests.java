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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthProfileHasher}.
 *
 * @author Artem Simeshin
 */
class AuthProfileHasherTests {

	@Test
	void anonymous_fingerprintIsAnonymous() {
		assertThat(AuthProfileHasher.fingerprint(null, null)).isEqualTo(AuthProfileHasher.ANONYMOUS);
		assertThat(AuthProfileHasher.fingerprint(null, Map.of())).isEqualTo(AuthProfileHasher.ANONYMOUS);
		assertThat(AuthProfileHasher.fingerprint("", Map.of())).isEqualTo(AuthProfileHasher.ANONYMOUS);
	}

	@Test
	void sameAuth_producesSameFingerprint() {
		final String fp1 = AuthProfileHasher.fingerprint("Bearer tok123", null);
		final String fp2 = AuthProfileHasher.fingerprint("Bearer tok123", null);
		assertThat(fp1).isEqualTo(fp2);
		assertThat(fp1).isNotEqualTo(AuthProfileHasher.ANONYMOUS);
	}

	@Test
	void differentAuth_producesDifferentFingerprint() {
		final String fp1 = AuthProfileHasher.fingerprint("Bearer tok123", null);
		final String fp2 = AuthProfileHasher.fingerprint("Bearer tok456", null);
		assertThat(fp1).isNotEqualTo(fp2);
	}

	@Test
	void customHeaders_produceDifferentFingerprint() {
		final String fp1 = AuthProfileHasher.fingerprint(null, Map.of("X-Api-Key", "key1"));
		final String fp2 = AuthProfileHasher.fingerprint(null, Map.of("X-Api-Key", "key2"));
		assertThat(fp1).isNotEqualTo(fp2);
		assertThat(fp1).isNotEqualTo(AuthProfileHasher.ANONYMOUS);
	}

	@Test
	void customHeaderOrder_doesNotAffectFingerprint() {
		final String fp1 = AuthProfileHasher.fingerprint(null, Map.of("X-Api-Key", "k1", "X-Other", "o1"));
		final String fp2 = AuthProfileHasher.fingerprint(null, Map.of("X-Other", "o1", "X-Api-Key", "k1"));
		assertThat(fp1).isEqualTo(fp2);
	}

	@Test
	void authAndCustomHeaders_combinedFingerprint() {
		final String fp1 = AuthProfileHasher.fingerprint("Bearer tok", Map.of("X-Api-Key", "k1"));
		final String fp2 = AuthProfileHasher.fingerprint("Bearer tok", Map.of("X-Api-Key", "k1"));
		final String fp3 = AuthProfileHasher.fingerprint("Bearer tok", Map.of("X-Api-Key", "k2"));
		assertThat(fp1).isEqualTo(fp2);
		assertThat(fp1).isNotEqualTo(fp3);
	}

	@Test
	void fingerprintIsHexString() {
		final String fp = AuthProfileHasher.fingerprint("Bearer tok123", null);
		assertThat(fp).matches("[0-9a-f]{64}");
	}

}
