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

package io.inspector.mcp.core.protocol;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.protocol.ProtocolRevision.CompatibilityResult;
import io.inspector.mcp.core.protocol.ProtocolRevision.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ProtocolRevision}. */
@Epic("MCP Inspector Core")
@Feature("Protocol revision compatibility")
class ProtocolRevisionTests {

	private static final String REV_2025_11_25 = "2025-11-25";

	private static final String REV_2026_07_28 = "2026-07-28";

	@Nested
	@DisplayName("Downgrade: client requests newer revision than server negotiated")
	class Downgrade {

		@Test
		@Story("2026-07-28 requested, 2025-11-25 negotiated")
		@Description("A downgrade across the 2026-07-28 boundary lists all four removed methods")
		void check_requestedNewerThanNegotiated_reportsDowngradeWithAllFourRemovedMethods() {
			final CompatibilityResult result = ProtocolRevision.check(REV_2026_07_28, REV_2025_11_25);

			assertThat(result.severity()).isEqualTo(Severity.DOWNGRADE);
			assertThat(result.affectedMethods()).containsExactlyInAnyOrder("ping", "logging/setLevel",
					"resources/subscribe", "resources/unsubscribe");
			assertThat(result.summary()).contains("2026-07-28").contains("2025-11-25").contains("MethodNotFound");
		}

	}

	@Nested
	@DisplayName("Upgrade-safe: client requests older revision than server negotiated")
	class NotADowngrade {

		@Test
		@Story("2025-11-25 requested, 2026-07-28 negotiated")
		@Description("The reverse pair is compatible: the newer server satisfies the older client")
		void check_requestedOlderThanNegotiated_reportsOkWithNoAffectedMethods() {
			final CompatibilityResult result = ProtocolRevision.check(REV_2025_11_25, REV_2026_07_28);

			assertThat(result.severity()).isEqualTo(Severity.OK);
			assertThat(result.affectedMethods()).isEmpty();
			assertThat(result.summary()).isNotBlank();
		}

	}

	@Nested
	@DisplayName("Matching revisions")
	class Matching {

		@Test
		@Story("Same known revision on both sides")
		@Description("Identical requested and negotiated revisions are reported as ok")
		void check_sameRevisionOnBothSides_reportsOk() {
			final CompatibilityResult result = ProtocolRevision.check(REV_2026_07_28, REV_2026_07_28);
			assertThat(result.severity()).isEqualTo(Severity.OK);
			assertThat(result.affectedMethods()).isEmpty();

			final CompatibilityResult older = ProtocolRevision.check(REV_2025_11_25, REV_2025_11_25);
			assertThat(older.severity()).isEqualTo(Severity.OK);
			assertThat(older.affectedMethods()).isEmpty();
		}

	}

	@Nested
	@DisplayName("Unknown revisions")
	class Unknown {

		@Test
		@Story("Unknown requested revision")
		@Description("An unrecognised requested revision yields unknown severity with an explanation")
		void check_unknownRequestedVersion_reportsUnknown() {
			final CompatibilityResult result = ProtocolRevision.check("2099-01-01", REV_2025_11_25);

			assertThat(result.severity()).isEqualTo(Severity.UNKNOWN);
			assertThat(result.affectedMethods()).isEmpty();
			assertThat(result.summary()).contains("2099-01-01");
		}

		@Test
		@Story("Unknown negotiated revision")
		@Description("An unrecognised negotiated revision yields unknown severity with an explanation")
		void check_unknownNegotiatedVersion_reportsUnknown() {
			final CompatibilityResult result = ProtocolRevision.check(REV_2026_07_28, "not-a-date");

			assertThat(result.severity()).isEqualTo(Severity.UNKNOWN);
			assertThat(result.affectedMethods()).isEmpty();
			assertThat(result.summary()).contains("not-a-date");
		}

		@Test
		@Story("Both revisions unknown")
		@Description("Two unrecognised revisions yield unknown severity mentioning both")
		void check_bothVersionsUnknown_reportsUnknown() {
			final CompatibilityResult result = ProtocolRevision.check("2024-01-01", "2023-06-01");

			assertThat(result.severity()).isEqualTo(Severity.UNKNOWN);
			assertThat(result.summary()).contains("2024-01-01").contains("2023-06-01");
		}

		@Test
		@Story("Null revisions")
		@Description("Null inputs do not throw and are reported as unknown")
		void check_nullVersions_reportUnknownWithoutThrowing() {
			assertThat(ProtocolRevision.check(null, REV_2025_11_25).severity()).isEqualTo(Severity.UNKNOWN);
			assertThat(ProtocolRevision.check(REV_2025_11_25, null).severity()).isEqualTo(Severity.UNKNOWN);
			assertThat(ProtocolRevision.check(null, null).severity()).isEqualTo(Severity.UNKNOWN);
		}

	}

}
