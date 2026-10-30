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

/**
 * One exception-leak match computed by {@link ExceptionLeakDetector}: the error text a
 * tool result carried out to the client model matches an {@code APP_LOG} exception inside
 * the match window.
 *
 * @param matchType how the texts matched (spec S1-S3 tier)
 * @param verbatim {@code true} only for an {@link MatchType#EXACT} match whose equality
 * held without the timestamp-stripping normalization step on either side
 * @param logEventId the {@link TimelineEvent#id()} of the matched {@code APP_LOG} event
 * @param logTimestamp the timestamp of the matched {@code APP_LOG} event
 * @param logMessage the log message first line, capped at 200 chars
 * @param logThrowableClass the FQCN of the logged throwable, or {@code null} when the log
 * carried no throwable
 * @param multipleCandidates {@code true} when more than one candidate matched in the
 * winning tier (deterministic latest-timestamp pick, spec D4)
 * @param droppedCandidates how many matched candidates were not picked (the "+N more"
 * affordance input)
 * @author Artem Simeshin
 */
public record LeakMatch(MatchType matchType, boolean verbatim, String logEventId, Instant logTimestamp,
		String logMessage, String logThrowableClass, boolean multipleCandidates, int droppedCandidates) {

	/**
	 * The match tier that produced the annotation (spec S1-S3).
	 */
	public enum MatchType {

		/**
		 * Char-for-char equality of the normalized forms (S1).
		 */
		EXACT,

		/**
		 * Equality after aggressive normalization: lowercase, all whitespace removed
		 * (S2).
		 */
		NORMALIZED,

		/**
		 * Containment either way with the length floors: at least 24 chars and at least
		 * 50 percent of the longer text (S3).
		 */
		PARTIAL

	}

}
