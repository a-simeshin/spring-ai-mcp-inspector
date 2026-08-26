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

package io.inspector.mcp.core.introspect.model;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link WarningCode}. */
@Epic("MCP Inspector Core")
@Feature("Introspection warning codes")
class WarningCodeTests {

	@Nested
	@DisplayName("defaultSeverity")
	class DefaultSeverity {

		@Test
		@Story("Every code defaults to warning")
		@Severity(SeverityLevel.CRITICAL)
		@Description("all five codes resolve to severity \"warning\" via defaultSeverity(); \"error\" stays reserved")
		void defaultSeverity_allCodes_areWarning() {
			// when & then
			assertThat(WarningCode.values()).extracting(WarningCode::defaultSeverity).containsOnly("warning");
		}

	}

	@Nested
	@DisplayName("codes")
	class Codes {

		@Test
		@Story("The five warning codes exist")
		@Severity(SeverityLevel.CRITICAL)
		@Description("EXTERNAL_REF, UNRESOLVED_REF, UNION_TYPE, OUTSIDE_COMPONENT_SCAN and NO_MCP_ELEMENTS are exposed")
		void codes_enum_containsAllContractCodes() {
			// when & then
			assertThat(WarningCode.values()).containsExactly(WarningCode.EXTERNAL_REF, WarningCode.UNRESOLVED_REF,
					WarningCode.UNION_TYPE, WarningCode.OUTSIDE_COMPONENT_SCAN, WarningCode.NO_MCP_ELEMENTS);
		}

	}

}
