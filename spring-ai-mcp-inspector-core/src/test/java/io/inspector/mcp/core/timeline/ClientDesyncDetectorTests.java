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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.inspector.mcp.core.timeline.ClientConfigReader.ClientConfig;
import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncFinding;
import io.inspector.mcp.core.timeline.ClientDesyncDetector.DesyncType;
import io.inspector.mcp.core.timeline.ClientHandlerScanner.HandlerBinding;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientDesyncDetector}.
 *
 * @author Artem Simeshin
 */
class ClientDesyncDetectorTests {

	private static HandlerBinding binding(final String kind, final String client, final String bean,
			final String method) {
		return new HandlerBinding(kind, client, bean, "com.example.Foo", method,
				"(Ljava/lang/Object;)Ljava/lang/String;", "org.springframework.ai.mcp.annotation." + "Mcp"
						+ kind.substring(0, 1).toUpperCase() + kind.substring(1));
	}

	private static ClientConfig config(final String name, final String transport, final String url,
			final String command) {
		return new ClientConfig(name, transport, url, command);
	}

	private static ClientConfig stdioConfig(final String name, final String command) {
		return config(name, "stdio", null, command);
	}

	private static ClientConfig sseConfig(final String name, final String url) {
		return config(name, "sse", url, null);
	}

	@Nested
	@DisplayName("detectOrphanHandlers")
	class DetectOrphanHandlers {

		@Test
		@DisplayName("flags handler referencing non-existent client")
		void flagsOrphanHandler() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "typo-client", "beanA", "onSample"));
			final Map<String, ClientConfig> clients = Map.of("real-client", stdioConfig("real-client", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanHandlers(bindings, clients);

			// then
			assertThat(findings).hasSize(1);
			final DesyncFinding finding = findings.get(0);
			assertThat(finding.type()).isEqualTo(DesyncType.ORPHAN_HANDLER);
			assertThat(finding.clientName()).isEqualTo("typo-client");
			assertThat(finding.handlerKind()).isEqualTo("sampling");
		}

		@Test
		@DisplayName("does not flag wildcard handler")
		void doesNotFlagWildcard() {
			// given
			final List<HandlerBinding> bindings = List
				.of(binding("logging", ClientHandlerScanner.ALL_CLIENTS, "beanA", "onLog"));
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanHandlers(bindings, clients);

			// then
			assertThat(findings).isEmpty();
		}

		@Test
		@DisplayName("does not flag handler for configured client")
		void doesNotFlagMatched() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("progress", "c1", "beanA", "onProgress"));
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanHandlers(bindings, clients);

			// then
			assertThat(findings).isEmpty();
		}

	}

	@Nested
	@DisplayName("detectOrphanClients")
	class DetectOrphanClients {

		@Test
		@DisplayName("flags client with no handler when sibling of same transport has one")
		void flagsOrphanClientWithSibling() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "c1", "beanA", "onSample"));
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"), "c2",
					stdioConfig("c2", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, clients);

			// then
			assertThat(findings).hasSize(1);
			final DesyncFinding finding = findings.get(0);
			assertThat(finding.type()).isEqualTo(DesyncType.ORPHAN_CLIENT);
			assertThat(finding.clientName()).isEqualTo("c2");
		}

		@Test
		@DisplayName("does not flag when no handlers exist at all")
		void noHandlersNoFlag() {
			// given
			final List<HandlerBinding> bindings = List.of();
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, clients);

			// then
			assertThat(findings).isEmpty();
		}

		@Test
		@DisplayName("does not flag when wildcard handler covers all clients")
		void wildcardCoversAll() {
			// given
			final List<HandlerBinding> bindings = List
				.of(binding("logging", ClientHandlerScanner.ALL_CLIENTS, "beanA", "onLog"));
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"), "c2",
					stdioConfig("c2", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, clients);

			// then
			assertThat(findings).isEmpty();
		}

		@Test
		@DisplayName("does not flag configured client without callback when no sibling has handler")
		void noFalsePositiveWithoutSibling() {
			// given: handler only for client-a (stdio), client-b is sse with no handler
			final List<HandlerBinding> bindings = List.of(binding("sampling", "client-a", "beanA", "onSample"));
			final Map<String, ClientConfig> clients = Map.of("client-a", stdioConfig("client-a", "echo"), "client-b",
					sseConfig("client-b", "https://example.invalid/sse"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, clients);

			// then: client-b is NOT an orphan because no sse sibling has a handler
			assertThat(findings).isEmpty();
		}

		@Test
		@DisplayName("does not flag when only client of its transport type has no handler")
		void singleTransportNoSibling() {
			// given: only one sse client, no handler for it, but a handler for a stdio
			// client
			final List<HandlerBinding> bindings = List.of(binding("sampling", "stdio-client", "beanA", "onSample"));
			final Map<String, ClientConfig> clients = Map.of("stdio-client", stdioConfig("stdio-client", "echo"),
					"sse-client", sseConfig("sse-client", "https://example.invalid/sse"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectOrphanClients(bindings, clients);

			// then: sse-client is NOT an orphan because no other sse client has a handler
			assertThat(findings).isEmpty();
		}

	}

	@Nested
	@DisplayName("detectTransportMismatches")
	class DetectTransportMismatches {

		@Test
		@DisplayName("flags stdio client with URL property")
		void flagsStdioWithUrl() {
			// given
			final Map<String, ClientConfig> clients = Map.of("c1",
					config("c1", "stdio", "https://example.com/mcp", null));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectTransportMismatches(clients);

			// then
			assertThat(findings).hasSize(1);
			final DesyncFinding finding = findings.get(0);
			assertThat(finding.type()).isEqualTo(DesyncType.TRANSPORT_MISMATCH);
			assertThat(finding.clientName()).isEqualTo("c1");
		}

		@Test
		@DisplayName("flags sse client with command property")
		void flagsSseWithCommand() {
			// given
			final Map<String, ClientConfig> clients = Map.of("c1", config("c1", "sse", null, "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectTransportMismatches(clients);

			// then
			assertThat(findings).hasSize(1);
			assertThat(findings.get(0).type()).isEqualTo(DesyncType.TRANSPORT_MISMATCH);
		}

		@Test
		@DisplayName("flags sse client with both url and command configured")
		void flagsSseWithBothUrlAndCommand() {
			// given
			final Map<String, ClientConfig> clients = Map.of("mixed",
					config("mixed", "sse", "https://example.invalid/sse", "unexpected-command"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectTransportMismatches(clients);

			// then
			assertThat(findings).hasSize(2);
			assertThat(findings)
				.anyMatch((f) -> f.type() == DesyncType.TRANSPORT_MISMATCH && f.message().contains("command property"));
			assertThat(findings).anyMatch(
					(f) -> f.type() == DesyncType.TRANSPORT_MISMATCH && f.message().contains("both url and command"));
		}

		@Test
		@DisplayName("flags stdio client with command that looks like URL")
		void flagsStdioWithUrlLookingCommand() {
			// given
			final Map<String, ClientConfig> clients = Map.of("c1",
					config("c1", "stdio", null, "https://should-be-sse.com"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectTransportMismatches(clients);

			// then
			assertThat(findings).hasSize(1);
			final DesyncFinding finding = findings.get(0);
			assertThat(finding.type()).isEqualTo(DesyncType.TRANSPORT_MISMATCH);
			assertThat(finding.clientName()).isEqualTo("c1");
			assertThat(finding.source()).contains("https://should-be-sse.com");
		}

		@Test
		@DisplayName("does not flag matching transport and property")
		void doesNotFlagMatched() {
			// given
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"), "c2",
					sseConfig("c2", "https://x"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectTransportMismatches(clients);

			// then
			assertThat(findings).isEmpty();
		}

	}

	@Nested
	@DisplayName("detectDuplicateBindings")
	class DetectDuplicateBindings {

		@Test
		@DisplayName("flags two methods binding same kind to same client")
		void flagsDuplicate() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "c1", "beanA", "onSample"),
					binding("sampling", "c1", "beanB", "onSample2"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectDuplicateBindings(bindings);

			// then
			assertThat(findings).hasSize(1);
			final DesyncFinding finding = findings.get(0);
			assertThat(finding.type()).isEqualTo(DesyncType.DUPLICATE_BINDING);
			assertThat(finding.clientName()).isEqualTo("c1");
			assertThat(finding.handlerKind()).isEqualTo("sampling");
			assertThat(finding.source()).contains("beanA#onSample").contains("beanB#onSample2");
		}

		@Test
		@DisplayName("does not flag different kinds on same client")
		void differentKindsNoFlag() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "c1", "beanA", "onSample"),
					binding("logging", "c1", "beanA", "onLog"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectDuplicateBindings(bindings);

			// then
			assertThat(findings).isEmpty();
		}

		@Test
		@DisplayName("does not flag same kind on different clients")
		void differentClientsNoFlag() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "c1", "beanA", "onSample"),
					binding("sampling", "c2", "beanA", "onSample"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detectDuplicateBindings(bindings);

			// then
			assertThat(findings).isEmpty();
		}

	}

	@Nested
	@DisplayName("detect (combined)")
	class DetectCombined {

		@Test
		@DisplayName("returns all four finding types when present")
		void returnsAllTypes() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "nonexistent", "beanA", "onSample"),
					binding("progress", "c1", "beanA", "onProgress"),
					binding("progress", "c1", "beanB", "onProgress2"));
			final Map<String, ClientConfig> clients = Map.of("c1", config("c1", "stdio", "https://mismatch.com", null),
					"c2", stdioConfig("c2", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detect(bindings, clients);

			// then
			final List<DesyncType> types = findings.stream().map(DesyncFinding::type).toList();
			assertThat(types).contains(DesyncType.ORPHAN_HANDLER, DesyncType.ORPHAN_CLIENT,
					DesyncType.TRANSPORT_MISMATCH, DesyncType.DUPLICATE_BINDING);
		}

		@Test
		@DisplayName("returns empty list when everything is consistent")
		void returnsEmptyWhenConsistent() {
			// given
			final List<HandlerBinding> bindings = List.of(binding("sampling", "c1", "beanA", "onSample"));
			final Map<String, ClientConfig> clients = Map.of("c1", stdioConfig("c1", "echo"));

			// when
			final List<DesyncFinding> findings = ClientDesyncDetector.detect(bindings, clients);

			// then
			assertThat(findings).isEmpty();
		}

	}

}
