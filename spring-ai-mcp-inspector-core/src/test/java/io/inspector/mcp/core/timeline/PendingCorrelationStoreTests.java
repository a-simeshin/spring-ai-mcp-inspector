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

package io.inspector.mcp.core.timeline;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PendingCorrelationStore}.
 *
 * @author Artem Simeshin
 */
class PendingCorrelationStoreTests {

	@Test
	@DisplayName("stores and removes a pending correlation")
	void storeAndRemove() {
		final PendingCorrelationStore<String> store = new PendingCorrelationStore<>(10);
		store.store("k", new PendingCorrelationStore.PendingCorrelation("c1", Instant.now()));
		assertThat(store.size()).isEqualTo(1);
		final PendingCorrelationStore.PendingCorrelation removed = store.remove("k");
		assertThat(removed.correlationId()).isEqualTo("c1");
		assertThat(store.size()).isZero();
		assertThat(store.remove("k")).isNull();
	}

	@Test
	@DisplayName("remove of an unknown key returns null")
	void removeUnknownReturnsNull() {
		final PendingCorrelationStore<String> store = new PendingCorrelationStore<>(10);
		assertThat(store.remove("missing")).isNull();
		assertThat(store.size()).isZero();
	}

	@Test
	@DisplayName("storing the same key replaces the entry")
	void sameKeyReplaces() {
		final PendingCorrelationStore<String> store = new PendingCorrelationStore<>(10);
		store.store("k", new PendingCorrelationStore.PendingCorrelation("c1", Instant.now()));
		store.store("k", new PendingCorrelationStore.PendingCorrelation("c2", Instant.now()));
		assertThat(store.size()).isEqualTo(1);
		assertThat(store.remove("k").correlationId()).isEqualTo("c2");
	}

	@Test
	@DisplayName("evicts the eldest entry beyond capacity")
	void evictsEldestBeyondCapacity() {
		final PendingCorrelationStore<String> store = new PendingCorrelationStore<>(2);
		store.store("a", new PendingCorrelationStore.PendingCorrelation("c-a", Instant.now()));
		store.store("b", new PendingCorrelationStore.PendingCorrelation("c-b", Instant.now()));
		store.store("c", new PendingCorrelationStore.PendingCorrelation("c-c", Instant.now()));
		assertThat(store.size()).isEqualTo(2);
		assertThat(store.remove("a")).isNull();
		assertThat(store.remove("b").correlationId()).isEqualTo("c-b");
		assertThat(store.remove("c").correlationId()).isEqualTo("c-c");
	}

	@Test
	@DisplayName("elapsed time since creation is non-negative")
	void elapsedNonNegative() {
		final PendingCorrelationStore.PendingCorrelation pending = new PendingCorrelationStore.PendingCorrelation("c1",
				Instant.now());
		assertThat(pending.elapsed()).isGreaterThanOrEqualTo(0L);
	}

}
