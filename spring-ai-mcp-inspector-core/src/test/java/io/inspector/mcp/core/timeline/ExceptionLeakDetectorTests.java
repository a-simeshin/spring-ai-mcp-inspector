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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExceptionLeakDetector}: the S1/S2/S3 tiers, the N() normalization
 * steps, the match window boundaries, and the query-time enrichment contract. The E11000
 * sample is the representative Mongo duplicate-key text from spring-ai#7219 the spec
 * calls out.
 *
 * @author Artem Simeshin
 */
@Epic("Exception leak detector")
@Feature("Backend matching engine")
@Story("Tool result error text is matched against windowed APP_LOG exceptions")
class ExceptionLeakDetectorTests {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	private static final String E11000 = "E11000 duplicate key error collection: db.app index: tenant_name_idx dup key: { name: \"acme\" }";

	private static final ExceptionLeakDetector DETECTOR = new ExceptionLeakDetector();

	private static TimelineEvent logEvent(final String id, final Instant timestamp, final String level,
			final String message, final String throwable) {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("logLevel", level);
		payload.put("loggerName", "com.example.Demo");
		payload.put("threadName", "main");
		payload.put("message", message);
		if (throwable != null) {
			payload.put("throwable", throwable);
		}
		return new TimelineEvent(id, null, null, TimelineEventType.APP_LOG, timestamp, payload);
	}

	private static ObjectNode responsePayload(final String... textBlocks) {
		final ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("jsonrpc", "2.0");
		payload.put("id", 1);
		final ObjectNode result = payload.putObject("result");
		result.put("isError", true);
		final ArrayNode content = result.putArray("content");
		for (final String text : textBlocks) {
			final ObjectNode block = content.addObject();
			block.put("type", "text");
			block.put("text", text);
		}
		return payload;
	}

	private static TimelineEvent responseEvent(final String id, final Instant timestamp, final String... textBlocks) {
		return new TimelineEvent(id, null, null, TimelineEventType.MCP_JSONRPC_RESPONSE, timestamp,
				responsePayload(textBlocks));
	}

	@Nested
	@DisplayName("S1 EXACT")
	class ExactTier {

		@Test
		void t1E11000SampleExactVerbatim() {
			final TimelineEvent log = logEvent("log-1", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)\n" + "\tat org.example.Baz.qux(Baz.java:20)");
			final TimelineEvent response = responseEvent("resp-1", T, E11000 + "\n" + E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches).containsOnlyKeys("resp-1");
			final LeakMatch match = matches.get("resp-1");
			assertThat(match.matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
			assertThat(match.verbatim()).isTrue();
			assertThat(match.logEventId()).isEqualTo("log-1");
			assertThat(match.logTimestamp()).isEqualTo(T.minusSeconds(1));
			assertThat(match.logMessage()).isEqualTo("insert failed");
			assertThat(match.logThrowableClass()).isEqualTo("org.springframework.dao.DuplicateKeyException");
			assertThat(match.multipleCandidates()).isFalse();
			assertThat(match.droppedCandidates()).isZero();
		}

		@Test
		void t2TwoLineUpstreamConcatShape() {
			final TimelineEvent log = logEvent("log-2", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DataIntegrityViolationException: insert failed\n"
							+ "\tat org.example.Foo.bar(Foo.java:10)\n"
							+ "Caused by: com.mongodb.MongoBulkWriteException: " + E11000 + "\n"
							+ "\tat com.mongodb.internal.Foo.run(Foo.java:99)\n"
							+ "Caused by: java.lang.IllegalStateException: deepest cause is kept");
			final TimelineEvent response = responseEvent("resp-2", T, "insert failed\ndeepest cause is kept");

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-2").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
			assertThat(matches.get("resp-2").verbatim()).isTrue();
			assertThat(matches.get("resp-2").logThrowableClass())
				.isEqualTo("org.springframework.dao.DataIntegrityViolationException");
		}

		@Test
		void t12MultiBlockContentIsJoinedWithNewlines() {
			final TimelineEvent log = logEvent("log-12", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)");
			final TimelineEvent response = responseEvent("resp-12", T, E11000, E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-12").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
		}

		@Test
		void t9NestedCausedByChainMatchesDeepestCause() {
			final TimelineEvent log = logEvent("log-9", T.minusSeconds(1), "WARN", "wrapper",
					"com.example.ServiceException: top message\n\tat a.B(B.java:1)\n"
							+ "Caused by: java.lang.IllegalStateException: root message\n\tat c.D(D.java:2)\n"
							+ "Caused by: java.lang.UnsupportedOperationException: deepest wins\n\tat e.F(F.java:3)");
			final TimelineEvent response = responseEvent("resp-9", T, "top message\ndeepest wins");

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-9").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
			assertThat(matches.get("resp-9").logThrowableClass()).isEqualTo("com.example.ServiceException");
		}

		@Test
		void t14JpaJdbcChainDepthThreeMatchesDeepestCause() {
			final TimelineEvent log = logEvent("log-14", T.minusSeconds(1), "ERROR", "commit failed",
					"jakarta.persistence.RollbackException: commit failed\n\tat a.B(B.java:1)\n"
							+ "Caused by: jakarta.persistence.PersistenceException: inconsistent constraint state\n\tat c.D(D.java:2)\n"
							+ "Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint");
			final TimelineEvent response = responseEvent("resp-14", T,
					"commit failed\nERROR: duplicate key value violates unique constraint");

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches).containsKey("resp-14");
			assertThat(matches.get("resp-14").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
			assertThat(matches.get("resp-14").logThrowableClass()).isEqualTo("jakarta.persistence.RollbackException");
		}

		@Test
		void e6NpeWithoutMessageKeepsClassNameAsMessagePart() {
			final TimelineEvent log = logEvent("log-e6", T.minusSeconds(1), "ERROR", "boom",
					"java.lang.NullPointerException: \n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-e6", T,
					"java.lang.NullPointerException\njava.lang.NullPointerException");

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-e6").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
		}

		@Test
		void verbatimIsFalseWhenTimestampsHadToBeStripped() {
			final TimelineEvent log = logEvent("log-ts", T.minusSeconds(1), "ERROR", "boom",
					"java.lang.IllegalStateException: 12:00:01 boom");
			final TimelineEvent response = responseEvent("resp-ts", T, "12:00:01 boom\n12:00:01 boom");

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-ts").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
			assertThat(matches.get("resp-ts").verbatim()).isFalse();
		}

	}

	@Nested
	@DisplayName("S2 NORMALIZED")
	class NormalizedTier {

		@Test
		void s2MatchesCaseAndJsonEscapedNewlineDifferences() {
			final TimelineEvent log = logEvent("log-s2", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)");
			// JSON-escaped \n instead of a literal line break, and mixed case
			final TimelineEvent response = responseEvent("resp-s2", T,
					("e11000 " + E11000.substring(7) + "\\n" + E11000).replace("acme", "ACME"));

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-s2").matchType()).isEqualTo(LeakMatch.MatchType.NORMALIZED);
			assertThat(matches.get("resp-s2").verbatim()).isFalse();
		}

		@Test
		void s2MatchesWhitespacePaddedLogMessage() {
			// The unrelated throwable only qualifies the log as a candidate (spec E8);
			// the padded MESSAGE still drives the match
			final TimelineEvent log = logEvent("log-pad", T.minusSeconds(1), "WARN", "failed:   " + E11000 + "  ",
					"java.lang.IllegalStateException: background refresh failed\n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-pad", T, "failed: " + E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			// Step 1 collapses the padding already, so this is EXACT, and the tier
			// never falls through to whitespace-insensitive matching.
			assertThat(matches.get("resp-pad").matchType()).isEqualTo(LeakMatch.MatchType.EXACT);
		}

	}

	@Nested
	@DisplayName("S3 PARTIAL")
	class PartialTier {

		@Test
		void t3TruncatedTo60CharsMatchesPartial() {
			final TimelineEvent log = logEvent("log-3", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)");
			final String truncated = E11000.substring(0, 60);
			final TimelineEvent response = responseEvent("resp-3", T, truncated);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-3").matchType()).isEqualTo(LeakMatch.MatchType.PARTIAL);
			assertThat(matches.get("resp-3").verbatim()).isFalse();
		}

		@Test
		void t3bShortFragmentBelow24CharFloorDoesNotMatch() {
			final TimelineEvent log = logEvent("log-3b1", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)");
			final TimelineEvent response = responseEvent("resp-3b1", T, "E11000 dup");

			assertThat(DETECTOR.detect(List.of(response, log))).isEmpty();
		}

		@Test
		void t3bFragmentBelow50PercentDoesNotMatch() {
			final TimelineEvent log = logEvent("log-3b2", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000
							+ "\n\tat org.example.Foo.bar(Foo.java:10)");
			// 24 chars of a ~100 char line: above the char floor, below 50 percent
			final TimelineEvent response = responseEvent("resp-3b2", T, E11000.substring(0, 24));

			assertThat(DETECTOR.detect(List.of(response, log))).isEmpty();
		}

		@Test
		void s3ContainmentWorksInTheOtherDirection() {
			// The logged WARN message is the contained fragment, the error text is
			// longer. The unrelated throwable only qualifies the log (spec E8)
			final TimelineEvent log = logEvent("log-s3r", T.minusSeconds(1), "WARN", E11000,
					"java.lang.IllegalStateException: background refresh failed\n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-s3r", T, "failed: " + E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-s3r").matchType()).isEqualTo(LeakMatch.MatchType.PARTIAL);
			assertThat(matches.get("resp-s3r").logThrowableClass()).isEqualTo("java.lang.IllegalStateException");
		}

	}

	@Nested
	@DisplayName("No false positives")
	class NoFalsePositives {

		@Test
		void t4GenericErrorTextsDoNotMatch() {
			final TimelineEvent logConnection = logEvent("log-4a", T.minusSeconds(1), "ERROR", "connection refused",
					"java.net.ConnectException: Connection refused\n\tat a.B(B.java:1)");
			final TimelineEvent logTimeout = logEvent("log-4b", T.minusSeconds(2), "ERROR", "timeout",
					"java.util.concurrent.TimeoutException: timeout occurred after 30s\n\tat c.D(D.java:2)");
			final TimelineEvent response = responseEvent("resp-4", T, "E11000 sample\nE11000 sample");

			assertThat(DETECTOR.detect(List.of(response, logConnection, logTimeout))).isEmpty();
		}

		@Test
		void t8ErrorLogWithoutMatchingTextDoesNotAnnotate() {
			// A qualifying candidate (unrelated throwable) whose text does not match
			// the error text annotates nothing
			final TimelineEvent log = logEvent("log-8", T.minusSeconds(1), "ERROR", "unrelated background job failure",
					"java.lang.IllegalStateException: unrelated background job failure\n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-8", T, E11000 + "\n" + E11000);

			assertThat(DETECTOR.detect(List.of(response, log))).isEmpty();
		}

		@Test
		void t8bMessageOnlyLogsNeverQualifyEvenWhenTheTextMatchesExactly() {
			// Issue #251, owner review BLOCK 3: log.warn("Tenant acme not found") next to
			// return error("Tenant acme not found") is an intentional error message, not
			// a leaked exception. No throwable means no candidate, at any level.
			final TimelineEvent warnNoThrowable = logEvent("log-e8a", T.minusSeconds(1), "WARN",
					"Tenant acme not found", null);
			final TimelineEvent errorNoThrowable = logEvent("log-e8b", T.minusSeconds(2), "ERROR", E11000, null);
			final TimelineEvent tenantError = responseEvent("resp-e8a", T, "Tenant acme not found");
			final TimelineEvent e11000Error = responseEvent("resp-e8b", T.plusSeconds(1), E11000);

			assertThat(DETECTOR.detect(List.of(tenantError, e11000Error, warnNoThrowable, errorNoThrowable))).isEmpty();
		}

		@Test
		void t10ResponsesWithoutLogsAndLogsWithoutResponsesAreIgnored() {
			final TimelineEvent onlyResponse = responseEvent("resp-10", T, E11000 + "\n" + E11000);
			final TimelineEvent onlyLog = logEvent("log-10", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000);

			assertThat(DETECTOR.detect(List.of(onlyResponse))).isEmpty();
			assertThat(DETECTOR.detect(List.of(onlyLog))).isEmpty();
			assertThat(DETECTOR.detect(List.of())).isEmpty();
			assertThat(DETECTOR.detect(null)).isEmpty();
		}

		@Test
		void t11InfoLogQualification() {
			// INFO with a throwable qualifies as a candidate
			final TimelineEvent infoWithThrowable = logEvent("log-11a", T.minusSeconds(1), "INFO", "context",
					"java.lang.IllegalStateException: " + E11000);
			final TimelineEvent response = responseEvent("resp-11", T, E11000 + "\n" + E11000);
			assertThat(DETECTOR.detect(List.of(response, infoWithThrowable))).containsKey("resp-11");

			// INFO with only a message never qualifies, even when the text matches
			final TimelineEvent infoMessageOnly = logEvent("log-11b", T.minusSeconds(1), "INFO", E11000, null);
			assertThat(DETECTOR.detect(List.of(response, infoMessageOnly))).isEmpty();
		}

		@Test
		void t7BlankErrorTextNeverMatchesAndNeverThrows() {
			final TimelineEvent log = logEvent("log-7", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000);
			final TimelineEvent blankText = responseEvent("resp-7", T, "   ");
			final TimelineEvent noTextBlocks = responseEvent("resp-7b", T);

			assertThat(DETECTOR.detect(List.of(blankText, noTextBlocks, log))).isEmpty();
		}

		@Test
		void t13ProtocolErrorFrameIsNotAToolResult() {
			final ObjectNode payload = JsonNodeFactory.instance.objectNode();
			payload.put("jsonrpc", "2.0");
			payload.put("id", 99);
			payload.putObject("error").put("code", -32603).put("message", "Internal error");
			final TimelineEvent protocolError = new TimelineEvent("resp-13", null, null,
					TimelineEventType.MCP_JSONRPC_RESPONSE, T, payload);
			final TimelineEvent log = logEvent("log-13", T.minusSeconds(1), "ERROR", "boom",
					"java.lang.RuntimeException: Internal error\n\tat a.B(B.java:1)");

			assertThat(DETECTOR.detect(List.of(protocolError, log))).isEmpty();
		}

		@Test
		void t9bLogsOnlyInputLeavesMatchesEmpty() {
			final List<TimelineEvent> logsOnly = new ArrayList<>();
			for (int i = 0; i < 50; i++) {
				logsOnly.add(logEvent("log-x" + i, T.minusSeconds(i), "ERROR", "m",
						"java.lang.IllegalStateException: sample " + i + "\n\tat a.B(B.java:1)"));
			}
			assertThat(DETECTOR.detect(logsOnly)).isEmpty();
		}

	}

	@Nested
	@DisplayName("Window and tie-break")
	class WindowAndTieBreak {

		@Test
		void t6WindowBoundaries() {
			final TimelineEvent response = responseEvent("resp-6", T, E11000 + "\n" + E11000);

			// Exactly at response - 10s: inclusive lower bound, matches
			assertThat(matchAt(T.minus(Duration.ofSeconds(10)), response)).isNotNull();
			// One nano before: outside
			assertThat(matchAt(T.minus(Duration.ofSeconds(10)).minusNanos(1), response)).isNull();
			// One nano before the upper bound: matches
			assertThat(matchAt(T.plus(Duration.ofSeconds(2)).minusNanos(1), response)).isNotNull();
			// Exactly at response + 2s: exclusive upper bound, outside
			assertThat(matchAt(T.plus(Duration.ofSeconds(2)), response)).isNull();
		}

		private LeakMatch matchAt(final Instant logTimestamp, final TimelineEvent response) {
			final TimelineEvent log = logEvent("log-6", logTimestamp, "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)");
			return DETECTOR.detect(List.of(response, log)).get("resp-6");
		}

		@Test
		void t5LatestTimestampWinsAndMultipleCandidatesIsReported() {
			final TimelineEvent older = logEvent("log-5a", T.minusSeconds(5), "ERROR", "older insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)");
			final TimelineEvent newer = logEvent("log-5b", T.minusSeconds(1), "ERROR", "newer insert failed",
					"com.other.WrappedException: " + E11000 + "\n\tat c.D(D.java:2)");
			final TimelineEvent response = responseEvent("resp-5", T, E11000 + "\n" + E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, older, newer));

			assertThat(matches.get("resp-5").logEventId()).isEqualTo("log-5b");
			assertThat(matches.get("resp-5").multipleCandidates()).isTrue();
			assertThat(matches.get("resp-5").droppedCandidates()).isEqualTo(1);
			assertThat(matches.get("resp-5").logThrowableClass()).isEqualTo("com.other.WrappedException");
		}

		@Test
		void t5bEqualTimestampTieBreakPicksLaterScanPosition() {
			// D4 tie-break: identical timestamps, the later position in the ascending
			// scan wins
			final Instant same = T.minusSeconds(2);
			final TimelineEvent first = logEvent("log-5c", same, "ERROR", "first insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)");
			final TimelineEvent second = logEvent("log-5d", same, "ERROR", "second insert failed",
					"com.other.WrappedException: " + E11000 + "\n\tat c.D(D.java:2)");
			final TimelineEvent response = responseEvent("resp-5b", T, E11000 + "\n" + E11000);

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, first, second));

			assertThat(matches.get("resp-5b").logEventId()).isEqualTo("log-5d");
			assertThat(matches.get("resp-5b").logThrowableClass()).isEqualTo("com.other.WrappedException");
			assertThat(matches.get("resp-5b").multipleCandidates()).isTrue();
			assertThat(matches.get("resp-5b").droppedCandidates()).isEqualTo(1);
		}

	}

	@Nested
	@DisplayName("Normalization N()")
	class Normalization {

		@Test
		void step1CollapsesWhitespaceAndNormalizesLineBreaks() {
			assertThat(ExceptionLeakDetector.collapseWhitespace("  a \t b  \r\n c \r d \t")).isEqualTo("a b \n c \n d");
		}

		@Test
		void step2KeepsOnlyFirstLineAndDeepestCausedBy() {
			final List<String> lines = ExceptionLeakDetector
				.significantLines("java.lang.RuntimeException: top\n\tat a.B(B.java:1)\n\tat c.D(D.java:2)\n"
						+ "Caused by: java.lang.IllegalStateException: root\n\tat e.F(F.java:3)\n"
						+ "Caused by: java.lang.Exception: deepest");
			assertThat(lines).containsExactly("java.lang.RuntimeException: top",
					"Caused by: java.lang.Exception: deepest");
		}

		@Test
		void step2bStripsClassPrefixAndKeepsMessage() {
			assertThat(ExceptionLeakDetector.stripExceptionPrefix("java.lang.RuntimeException: boom"))
				.isEqualTo("boom");
			assertThat(ExceptionLeakDetector.stripExceptionPrefix("Caused by: com.example.Err: bad input"))
				.isEqualTo("bad input");
			// No message: the class name becomes the message part
			assertThat(ExceptionLeakDetector.stripExceptionPrefix("java.lang.NullPointerException: "))
				.isEqualTo("java.lang.NullPointerException");
		}

		@Test
		void step3StripsIsoAndTimeOfDayTimestamps() {
			assertThat(ExceptionLeakDetector.stripTimestamps("2026-10-01T09:59:59.123Z boom")).isEqualTo("boom");
			assertThat(ExceptionLeakDetector.stripTimestamps("at 12:00:01 boom")).isEqualTo("at boom");
			assertThat(ExceptionLeakDetector.stripTimestamps("at 12:00:01.456 boom")).isEqualTo("at boom");
			assertThat(ExceptionLeakDetector.stripTimestamps("no tokens here")).isEqualTo("no tokens here");
		}

		@Test
		void normalizationIsIdempotent() {
			final String raw = "java.lang.RuntimeException: 2026-10-01T09:59:59Z boom\n\tat a.B(B.java:1)\n"
					+ "Caused by: java.lang.IllegalStateException:  at 12:00:01 root  ";
			// Step 2 is stable on its own output
			final List<String> significantOnce = ExceptionLeakDetector.significantLines(raw);
			assertThat(ExceptionLeakDetector.significantLines(String.join("\n", significantOnce)))
				.isEqualTo(significantOnce);
			// Steps 1 and 3 are stable on their own output
			final String collapsedOnce = ExceptionLeakDetector.collapseWhitespace(raw);
			assertThat(ExceptionLeakDetector.collapseWhitespace(collapsedOnce)).isEqualTo(collapsedOnce);
			final String strippedOnce = ExceptionLeakDetector
				.stripTimestamps(ExceptionLeakDetector.stripExceptionPrefix(significantOnce.get(0)));
			assertThat(ExceptionLeakDetector.stripTimestamps(strippedOnce)).isEqualTo(strippedOnce);
		}

	}

	@Nested
	@DisplayName("Enrichment")
	class Enrichment {

		@Test
		void enrichAddsLeakKeyWithoutMutatingStoredPayload() {
			final ObjectNode storedPayload = responsePayload(E11000 + "\n" + E11000);
			final TimelineEvent response = new TimelineEvent("resp-e", null, null,
					TimelineEventType.MCP_JSONRPC_RESPONSE, T, storedPayload);
			final TimelineEvent log = logEvent("log-e", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)");

			final List<TimelineEvent> enriched = DETECTOR.enrich(new ArrayList<>(List.of(response, log)));

			assertThat(storedPayload.has(ExceptionLeakDetector.LEAK_KEY)).isFalse();
			assertThat(enriched).hasSize(2);
			final TimelineEvent enrichedResponse = enriched.get(0);
			assertThat(enrichedResponse).isNotSameAs(response);
			assertThat(enrichedResponse.id()).isEqualTo("resp-e");
			assertThat(enrichedResponse.payload().get("_leak").get("matchType").asString())
				.isEqualTo(LeakMatch.MatchType.EXACT.name());
			// The non-matching event is returned as the same instance
			assertThat(enriched.get(1)).isSameAs(log);
			// Original stored fields survive the deep copy
			assertThat(enrichedResponse.payload().path("result").path("isError").asBoolean()).isTrue();
		}

		@Test
		void enrichWithoutMatchesReturnsSameListInstance() {
			final List<TimelineEvent> events = List.of(logEvent("log-n", T, "INFO", "hello", null));
			assertThat(DETECTOR.enrich(events)).isSameAs(events);
		}

		@Test
		void leakNodeCarriesTheD5Schema() {
			final TimelineEvent log = logEvent("log-d5", T.minusSeconds(1), "ERROR", "insert failed",
					"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-d5", T, E11000 + "\n" + E11000);

			final JsonNode leak = DETECTOR.enrich(List.of(response, log))
				.get(0)
				.payload()
				.get(ExceptionLeakDetector.LEAK_KEY);

			assertThat(leak).isNotNull();
			assertThat(leak.get("matchType").asString()).isEqualTo("EXACT");
			assertThat(leak.get("verbatim").asBoolean()).isTrue();
			assertThat(leak.get("logEventId").asString()).isEqualTo("log-d5");
			assertThat(leak.get("logTimestamp").asString()).isEqualTo(T.minusSeconds(1).toString());
			assertThat(leak.get("logMessage").asString()).isEqualTo("insert failed");
			assertThat(leak.get("logThrowableClass").asString())
				.isEqualTo("org.springframework.dao.DuplicateKeyException");
			assertThat(leak.get("multipleCandidates").asBoolean()).isFalse();
			assertThat(leak.get("droppedCandidates").asInt()).isZero();
		}

		@Test
		void logMessageIsCappedAt200Chars() {
			final String longMessage = "x".repeat(500);
			// The same long text carried by the throwable qualifies the log (spec E8)
			final TimelineEvent log = logEvent("log-cap", T.minusSeconds(1), "ERROR", longMessage,
					"java.lang.IllegalStateException: " + longMessage + "\n\tat a.B(B.java:1)");
			final TimelineEvent response = responseEvent("resp-cap", T, "x".repeat(250));

			final Map<String, LeakMatch> matches = DETECTOR.detect(List.of(response, log));

			assertThat(matches.get("resp-cap").logMessage()).hasSize(200);
		}

	}

	@Nested
	@DisplayName("Performance")
	class Performance {

		@Test
		@Severity(SeverityLevel.MINOR)
		@Description("1000+ event page, every pair interleaved in the same window: the single "
				+ "indexing pass keeps the query-time enrichment inside the soft bound.")
		void thousandEventsPageStaysWithinBudget() {
			final List<TimelineEvent> events = new ArrayList<>(1000);
			final Instant start = Instant.parse("2026-10-01T09:00:00Z");
			for (int i = 0; i < 500; i++) {
				events.add(logEvent("perf-log-" + i, start.plusNanos(i * 1_000_000L), "ERROR", "insert failed " + i,
						"org.springframework.dao.DuplicateKeyException: " + E11000 + "\n\tat a.B(B.java:1)"));
				events.add(responseEvent("perf-resp-" + i, start.plusNanos(i * 1_000_000L + 500_000L),
						E11000 + "\n" + E11000));
			}
			// Warmup: the timeline poll path is always hot
			DETECTOR.enrich(events);
			long best = Long.MAX_VALUE;
			List<TimelineEvent> enriched = null;
			for (int run = 0; run < 3; run++) {
				final long begin = System.nanoTime();
				enriched = DETECTOR.enrich(events);
				best = Math.min(best, Duration.ofNanos(System.nanoTime() - begin).toMillis());
			}
			System.out.println("ExceptionLeakDetector: 1000-event page (all pairs matching) enriched in " + best
					+ " ms (best of 3)");
			assertThat(enriched).hasSize(1000);

			// 200 ms soft bound from the spec for a typical page, asserted with
			// generous CI slack against this deliberately adversarial all-pairs page
			Assertions.assertThat(best).isLessThan(1000);
		}

	}

}
