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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The exception-leak matching engine (issue #251): detects that the error text a
 * {@code tools/call} result carried out to the client model is the text of an exception
 * the application logged, and reports the match so the web layers can annotate the
 * response event with a synthetic {@code _leak} payload key.
 *
 * <p>
 * Pure function over an immutable query-result snapshot, computed at query time only
 * (spec D3): the engine never locks the ring buffer, never mutates stored payloads, and
 * costs nothing on the append path. Candidates are matched by TIME WINDOW + TEXT (spec
 * D1): an {@code APP_LOG} event is a candidate only when its timestamp falls within
 * {@code [responseTimestamp - 10s, responseTimestamp + 2s)} (inclusive lower bound,
 * exclusive upper bound) and it either carries a non-blank throwable or was logged at
 * ERROR/WARN level with a non-blank message.
 *
 * <p>
 * Match tiers, first tier that yields a match wins (spec S1-S3): S1 EXACT (normalized
 * equality), S2 NORMALIZED (equality after lowercasing and whitespace removal), S3
 * PARTIAL (containment either way with a length floor of 24 chars AND 50 percent of the
 * longer text). Within the winning tier the LATEST-timestamp candidate is picked and
 * {@code multipleCandidates} is reported when more than one candidate matched (spec D4).
 *
 * <p>
 * Normalization order (spec N(), idempotent): (1) whitespace collapse and line-break
 * normalization; (2) stack-frame drop on the log throwable, keeping only the throwable
 * first line and the first {@code Caused by:} line; (2b) strip the {@code Caused by:}
 * marker and the leading {@code FQCN + ": "} prefix, keeping the message part (the class
 * name when the message is empty); (3) strip ISO-8601 and time-of-day timestamp tokens.
 * The Spring AI error text is always the two-line
 * {@code topMessage + lineSeparator + rootCauseMessage} form with the message duplicated
 * when the exception has no cause, which is why the two-line candidate form repeats the
 * top message in that case.
 *
 * @author Artem Simeshin
 */
public final class ExceptionLeakDetector {

	/** The exact enrichment key spelling (single underscore, lowercase). */
	public static final String LEAK_KEY = "_leak";

	/** Match window before the response timestamp, inclusive lower bound (spec D1). */
	static final Duration WINDOW_BEFORE = Duration.ofSeconds(10);

	/** Match window after the response timestamp, exclusive upper bound (spec D1). */
	static final Duration WINDOW_AFTER = Duration.ofSeconds(2);

	/** S3 PARTIAL floor: the contained fragment must be at least this many chars. */
	static final int PARTIAL_MIN_CHARS = 24;

	/** Cap of the {@code logMessage} annotation field (spec D5). */
	static final int LOG_MESSAGE_CAP = 200;

	private static final Pattern LINE_BREAKS = Pattern.compile("\\r\\n|\\r");

	private static final Pattern SPACES_AND_TABS = Pattern.compile("[ \\t]+");

	private static final Pattern ALL_WHITESPACE = Pattern.compile("\\s+");

	private static final Pattern ISO_TIMESTAMP = Pattern
		.compile("[ \t]*\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?[ \t]*");

	private static final Pattern TIME_OF_DAY = Pattern
		.compile("[ \\t]*\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?\\b[ \\t]*");

	private static final Pattern CAUSED_BY_PREFIX = Pattern.compile("^Caused by:\\s*");

	private static final Pattern CLASS_PREFIX = Pattern.compile("^[\\w.$]+:\\s*");

	private static final Pattern LEADING_FQCN = Pattern.compile("^([\\w.$]+)");

	/**
	 * Creates a stateless detector; safe to share across threads.
	 */
	public ExceptionLeakDetector() {
	}

	/**
	 * Detects exception leaks over a timeline query-result snapshot.
	 *
	 * <p>
	 * The input is indexed in a single pass (responses with {@code result.isError=true}
	 * and a non-blank error text, plus qualifying {@code APP_LOG} candidates); each
	 * response is then matched against the candidates in its window only.
	 * @param events the query-result page, any order (the store serves newest-first)
	 * @return {@code responseEventId -> match} for every response that matched, never
	 * {@code null}, empty when nothing matched or the input is empty
	 */
	public Map<String, LeakMatch> detect(final Collection<TimelineEvent> events) {
		if (events == null || events.isEmpty()) {
			return Map.of();
		}
		final List<IndexedResponse> responses = new ArrayList<>();
		final List<Candidate> candidates = new ArrayList<>();
		for (final TimelineEvent event : events) {
			if (event.type() == TimelineEventType.MCP_JSONRPC_RESPONSE) {
				final IndexedResponse response = indexResponse(event);
				if (response != null) {
					responses.add(response);
				}
			}
			else if (event.type() == TimelineEventType.APP_LOG) {
				final Candidate candidate = indexCandidate(event);
				if (candidate != null) {
					candidates.add(candidate);
				}
			}
		}
		if (responses.isEmpty() || candidates.isEmpty()) {
			return Map.of();
		}
		candidates.sort(Comparator.comparing((final Candidate candidate) -> candidate.event.timestamp()));
		final Map<String, LeakMatch> matches = new HashMap<>();
		for (final IndexedResponse response : responses) {
			final List<TierMatch> exact = new ArrayList<>();
			final List<TierMatch> normalized = new ArrayList<>();
			final List<TierMatch> partial = new ArrayList<>();
			for (final Candidate candidate : candidates) {
				if (inWindow(candidate.event.timestamp(), response.event.timestamp())) {
					matchTiers(response, candidate, exact, normalized, partial);
				}
			}
			final List<TierMatch> winners = (!exact.isEmpty()) ? exact
					: ((!normalized.isEmpty()) ? normalized : partial);
			if (!winners.isEmpty()) {
				final TierMatch best = pickLatest(winners);
				final LeakMatch.MatchType tier = (!exact.isEmpty()) ? LeakMatch.MatchType.EXACT
						: ((!normalized.isEmpty()) ? LeakMatch.MatchType.NORMALIZED : LeakMatch.MatchType.PARTIAL);
				matches.put(response.event.id(), toMatch(tier, best, winners));
			}
		}
		return matches;
	}

	/**
	 * Enriches a timeline query-result page with {@code _leak} annotations.
	 *
	 * <p>
	 * Enriched events carry a DEEP COPY of the stored payload with the additive
	 * {@code _leak} key set (the {@code _} prefix cannot collide with real JSON-RPC
	 * fields); the stored frame is never mutated, mirroring the
	 * {@code _protocolNegotiation} enrichment rule. Events without a match are returned
	 * as-is, and the original list is never modified.
	 * @param events the query-result page
	 * @return the enriched page (the same list instance when nothing matched)
	 */
	public List<TimelineEvent> enrich(final List<TimelineEvent> events) {
		final Map<String, LeakMatch> matches = detect(events);
		if (matches.isEmpty()) {
			return events;
		}
		final List<TimelineEvent> enriched = new ArrayList<>(events.size());
		for (final TimelineEvent event : events) {
			final LeakMatch match = matches.get(event.id());
			if (match == null) {
				enriched.add(event);
				continue;
			}
			if (event.payload() instanceof ObjectNode payload) {
				final ObjectNode copy = payload.deepCopy();
				copy.set(LEAK_KEY, toLeakNode(match));
				enriched.add(new TimelineEvent(event.id(), event.correlationId(), event.sessionId(), event.type(),
						event.timestamp(), copy));
			}
			else {
				enriched.add(event);
			}
		}
		return enriched;
	}

	/**
	 * Builds the {@code _leak} annotation node per the D5 schema.
	 * @param match the computed match
	 * @return the annotation object node
	 */
	private static ObjectNode toLeakNode(final LeakMatch match) {
		final ObjectNode leak = JsonNodeFactory.instance.objectNode();
		leak.put("matchType", match.matchType().name());
		leak.put("verbatim", match.verbatim());
		leak.put("logEventId", match.logEventId());
		leak.put("logTimestamp", match.logTimestamp().toString());
		leak.put("logMessage", match.logMessage());
		if (match.logThrowableClass() != null) {
			leak.put("logThrowableClass", match.logThrowableClass());
		}
		else {
			leak.putNull("logThrowableClass");
		}
		leak.put("multipleCandidates", match.multipleCandidates());
		leak.put("droppedCandidates", match.droppedCandidates());
		return leak;
	}

	/**
	 * Deterministic tie-break (spec D4): the latest timestamp wins; among equal
	 * timestamps the later position in the ascending scan wins.
	 * @param winners the candidates that matched in the winning tier
	 * @return the picked match
	 */
	private static TierMatch pickLatest(final List<TierMatch> winners) {
		TierMatch best = winners.get(0);
		for (final TierMatch candidate : winners) {
			if (!candidate.candidate.event.timestamp().isBefore(best.candidate.event.timestamp())) {
				best = candidate;
			}
		}
		return best;
	}

	private static LeakMatch toMatch(final LeakMatch.MatchType tier, final TierMatch best,
			final List<TierMatch> winners) {
		final TimelineEvent log = best.candidate.event;
		return new LeakMatch(tier, tier == LeakMatch.MatchType.EXACT && best.verbatim, log.id(), log.timestamp(),
				cappedFirstLine(log.message()), best.candidate.throwableClass, winners.size() > 1, winners.size() - 1);
	}

	/**
	 * The log message first line capped at {@value #LOG_MESSAGE_CAP} chars.
	 * @param message the raw APP_LOG message, may be {@code null}
	 * @return the capped first line, never {@code null}
	 */
	private static String cappedFirstLine(final String message) {
		if (message == null || message.isEmpty()) {
			return "";
		}
		final String firstLine = message.split("\n", 2)[0].trim();
		return (firstLine.length() > LOG_MESSAGE_CAP) ? firstLine.substring(0, LOG_MESSAGE_CAP) : firstLine;
	}

	private static void matchTiers(final IndexedResponse response, final Candidate candidate,
			final List<TierMatch> exact, final List<TierMatch> normalized, final List<TierMatch> partial) {
		boolean exactMatch = false;
		boolean normalizedMatch = false;
		boolean partialMatch = false;
		boolean verbatim = false;
		for (final Form form : candidate.forms) {
			if (form.text.equals(response.normalized)) {
				exactMatch = true;
				verbatim = verbatim || (!form.timestampsStripped && !response.timestampsStripped);
			}
			else if (form.aggressive.equals(response.aggressive)) {
				normalizedMatch = true;
			}
			else if (isPartial(form.text, response.normalized)) {
				partialMatch = true;
			}
		}
		if (exactMatch) {
			exact.add(new TierMatch(candidate, verbatim));
		}
		else if (normalizedMatch) {
			normalized.add(new TierMatch(candidate, false));
		}
		else if (partialMatch) {
			partial.add(new TierMatch(candidate, false));
		}
	}

	/**
	 * S3 PARTIAL: containment either way with the length floors (at least
	 * {@value #PARTIAL_MIN_CHARS} chars and at least half of the longer text).
	 * @param formText the normalized candidate form
	 * @param errorText the normalized error text
	 * @return whether the containment holds above the floors
	 */
	private static boolean isPartial(final String formText, final String errorText) {
		final String shorter;
		final String longer;
		if (formText.length() <= errorText.length()) {
			shorter = formText;
			longer = errorText;
		}
		else {
			shorter = errorText;
			longer = formText;
		}
		if (shorter.length() < PARTIAL_MIN_CHARS || shorter.length() * 2 < longer.length()) {
			return false;
		}
		return longer.contains(shorter);
	}

	/**
	 * Window check (spec D1): inclusive lower bound, exclusive upper bound.
	 * @param candidateTimestamp the APP_LOG timestamp
	 * @param responseTimestamp the response timestamp
	 * @return whether the candidate is inside the window
	 */
	private static boolean inWindow(final Instant candidateTimestamp, final Instant responseTimestamp) {
		return !candidateTimestamp.isBefore(responseTimestamp.minus(WINDOW_BEFORE))
				&& candidateTimestamp.isBefore(responseTimestamp.plus(WINDOW_AFTER));
	}

	/**
	 * Indexes a response event when its frame is a tool result with {@code isError=true}
	 * and a non-blank error text (the concat of all {@code type=text} content blocks
	 * joined by newlines; blank error text never matches, spec E2).
	 * @param event the response event
	 * @return the indexed response, or {@code null} when the event is not an error tool
	 * result
	 */
	private static IndexedResponse indexResponse(final TimelineEvent event) {
		final JsonNode payload = event.payload();
		if (payload == null || !payload.isObject()) {
			return null;
		}
		final JsonNode result = payload.path("result");
		if (!result.isObject() || !result.path("isError").asBoolean(false)) {
			return null;
		}
		final JsonNode content = result.path("content");
		if (!content.isArray() || content.isEmpty()) {
			return null;
		}
		final StringBuilder errorText = new StringBuilder();
		for (final JsonNode block : content) {
			if (!"text".equals(block.path("type").asString(""))) {
				continue;
			}
			final String text = block.path("text").asString("");
			if (text.isEmpty()) {
				continue;
			}
			if (errorText.length() > 0) {
				errorText.append('\n');
			}
			errorText.append(text);
		}
		if (errorText.length() == 0) {
			return null;
		}
		final String collapsed = collapseWhitespace(errorText.toString());
		if (collapsed.isEmpty()) {
			return null;
		}
		final String stripped = stripTimestamps(collapsed);
		return new IndexedResponse(event, stripped, !stripped.equals(collapsed), aggressive(stripped));
	}

	/**
	 * Indexes an APP_LOG event when it qualifies as a candidate (spec E8): a non-blank
	 * throwable at any level, or a non-blank message at ERROR/WARN level.
	 * @param event the APP_LOG event
	 * @return the indexed candidate, or {@code null} when the log never qualifies
	 */
	private static Candidate indexCandidate(final TimelineEvent event) {
		final String throwable = event.throwable();
		final String message = event.message();
		final boolean hasThrowable = throwable != null && !throwable.isBlank();
		final boolean hasMessage = message != null && !message.isBlank();
		final String level = event.logLevel();
		final boolean errorOrWarn = "ERROR".equals(level) || "WARN".equals(level);
		if (!hasThrowable && !(errorOrWarn && hasMessage)) {
			return null;
		}
		final List<Form> forms = new ArrayList<>(3);
		String throwableClass = null;
		if (hasThrowable) {
			final List<String> lines = significantLines(throwable);
			final String top = stripExceptionPrefix(lines.get(0));
			final String causedBy = (lines.size() > 1) ? stripExceptionPrefix(lines.get(1)) : null;
			// The universal Spring AI shape (spec F1): the no-cause form duplicates the
			// top message on the second line.
			addForm(forms, top + "\n" + ((causedBy != null) ? causedBy : top));
			addForm(forms, top);
			throwableClass = leadingClassName(lines.get(0));
		}
		if (hasMessage) {
			addForm(forms, collapseWhitespace(message));
		}
		return new Candidate(event, List.copyOf(forms), throwableClass);
	}

	private static void addForm(final List<Form> forms, final String beforeTimestampStrip) {
		final String stripped = stripTimestamps(beforeTimestampStrip);
		forms.add(new Form(stripped, aggressive(stripped), !stripped.equals(beforeTimestampStrip)));
	}

	/**
	 * Normalization step 1: trim the whole text, collapse internal runs of spaces and
	 * tabs to one space, normalize line breaks to {@code \n}.
	 * @param text the raw text
	 * @return the collapsed text
	 */
	static String collapseWhitespace(final String text) {
		return SPACES_AND_TABS.matcher(LINE_BREAKS.matcher(text).replaceAll("\n")).replaceAll(" ").trim();
	}

	/**
	 * Normalization step 3: strip ISO-8601 timestamps and {@code HH:mm:ss.SSS}
	 * time-of-day tokens (log formatter artifacts, never part of the exception text).
	 * @param text the collapsed text
	 * @return the text without timestamp tokens
	 */
	static String stripTimestamps(final String text) {
		return TIME_OF_DAY.matcher(ISO_TIMESTAMP.matcher(text).replaceAll(" ")).replaceAll(" ").trim();
	}

	/**
	 * S2 aggressive form: lowercase, then remove ALL whitespace (including newlines) and
	 * the literal two-character {@code \n}/{@code \r} escape sequences a JSON-serialized
	 * side may carry instead of real line breaks.
	 * @param text the normalized text
	 * @return the aggressive comparison form
	 */
	static String aggressive(final String text) {
		final String lower = text.toLowerCase(Locale.ROOT);
		return ALL_WHITESPACE.matcher(lower.replace("\\r\\n", "").replace("\\n", "").replace("\\r", "")).replaceAll("");
	}

	/**
	 * Normalization steps 1 and 2 on a throwable: keep ONLY the throwable first line and
	 * the FIRST {@code Caused by:} line; every {@code \tat} frame line and every
	 * subsequent line is dropped (the model text never contains stack frames).
	 * @param throwable the raw throwable text
	 * @return the kept significant lines (one or two entries)
	 */
	static List<String> significantLines(final String throwable) {
		final String[] lines = collapseWhitespace(throwable).split("\n", -1);
		final List<String> kept = new ArrayList<>(2);
		for (final String raw : lines) {
			final String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			if (kept.isEmpty()) {
				kept.add(line);
			}
			else if (kept.size() == 1 && line.startsWith("Caused by:")) {
				kept.add(line);
			}
		}
		return kept;
	}

	/**
	 * Normalization step 2b: remove the {@code Caused by:} marker and the leading
	 * {@code FQCN + ": "} prefix, keeping the message part. When the message part is
	 * empty (an NPE without a message) the class name is kept as the message part (spec
	 * E6).
	 * @param line the significant throwable line
	 * @return the message part
	 */
	static String stripExceptionPrefix(final String line) {
		final String withoutMarker = CAUSED_BY_PREFIX.matcher(line).replaceFirst("");
		final String stripped = CLASS_PREFIX.matcher(withoutMarker).replaceFirst("");
		if (!stripped.isBlank()) {
			return stripped;
		}
		final Matcher className = LEADING_FQCN.matcher(withoutMarker);
		return (className.find()) ? className.group(1) : withoutMarker;
	}

	/**
	 * The {@code logThrowableClass} annotation field: the leading FQCN of the throwable
	 * first line, or {@code null} when the line does not start class-like.
	 * @param firstLine the throwable first significant line
	 * @return the FQCN or {@code null}
	 */
	private static String leadingClassName(final String firstLine) {
		final Matcher className = LEADING_FQCN.matcher(CAUSED_BY_PREFIX.matcher(firstLine).replaceFirst(""));
		return (className.find()) ? className.group(1) : null;
	}

	/**
	 * One normalized comparison form of a candidate.
	 *
	 * @param text the step-1/2b/3 normalized form used for S1 and S3
	 * @param aggressive the S2 form (lowercase, whitespace removed)
	 * @param timestampsStripped whether step 3 removed any timestamp token from this form
	 * (disables verbatim)
	 */
	private record Form(String text, String aggressive, boolean timestampsStripped) {
	}

	/**
	 * An indexed response with its normalized error text.
	 *
	 * @param event the response event
	 * @param normalized the normalized error text
	 * @param timestampsStripped whether step 3 removed any timestamp token
	 * @param aggressive the S2 form of the error text
	 */
	private record IndexedResponse(TimelineEvent event, String normalized, boolean timestampsStripped,
			String aggressive) {
	}

	/**
	 * An indexed APP_LOG candidate with its comparison forms.
	 *
	 * @param event the APP_LOG event
	 * @param forms the normalized comparison forms (two-line, one-line, message)
	 * @param throwableClass the throwable FQCN, or {@code null} when no throwable
	 */
	private record Candidate(TimelineEvent event, List<Form> forms, String throwableClass) {
	}

	/**
	 * A candidate that matched in a tier.
	 *
	 * @param candidate the matched candidate
	 * @param verbatim whether an EXACT form matched without any timestamp stripping
	 */
	private record TierMatch(Candidate candidate, boolean verbatim) {
	}

}
