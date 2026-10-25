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

package io.inspector.mcp.core.dto;

import java.util.Map;

/**
 * Detection result for the SEP-2640 Skills extension, parsed from the <em>raw</em>
 * initialize result frame of the MCP handshake.
 *
 * <p>
 * The extension identifier is {@code io.modelcontextprotocol/skills}. Per SEP-2133 the
 * canonical wire location is {@code result.capabilities.extensions}. The MCP Java SDK
 * typed {@code ServerCapabilities} record has no {@code extensions} component, so a
 * declaration that arrives only in the canonical location is dropped during
 * deserialization into the typed model. The parser therefore works on the untyped result
 * map captured at the transport seam (see
 * {@code io.inspector.mcp.core.client.InitializeResultCapture}) and reads the canonical
 * location first, honouring a legacy {@code result._meta.extensions} declaration as a
 * fallback so servers that predate the canonical wire location keep working. When both
 * are present the canonical declaration wins.
 *
 * <p>
 * The single optional setting defined by the SEP is {@code directoryRead} (boolean,
 * default {@code false}); an empty object declares support for {@code skills/list} and
 * {@code skills/get} without {@code resources/directory/read}. Absent, non-map, or
 * non-object-shaped declarations all yield {@code supported == false} rather than a
 * parsing failure: a server with a malformed extension block must not break the inspector
 * connection.
 *
 * @param supported {@code true} iff the extension identifier is present with a map-shaped
 * value
 * @param directoryRead the {@code directoryRead} flag (default {@code false})
 * @author Artem Simeshin
 * @see <a href=
 * "https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2640">SEP-2640</a>
 * @see <a href="https://modelcontextprotocol.io/seps/2133-extensions">SEP-2133 extension
 * negotiation</a>
 */
public record SkillsExtensionSupport(boolean supported, boolean directoryRead) {

	/** Extension identifier declared by SEP-2640. */
	public static final String EXTENSION_ID = "io.modelcontextprotocol/skills";

	/**
	 * {@link #supported} {@code == false} singleton for absent/malformed declarations.
	 */
	public static final SkillsExtensionSupport ABSENT = new SkillsExtensionSupport(false);

	private SkillsExtensionSupport(final boolean supported) {
		this(supported, false);
	}

	/**
	 * Parses the skills-extension entry out of a raw initialize result frame: the untyped
	 * {@code result} object of the initialize response, exactly as it arrived on the wire
	 * and before the SDK's typed model drops the canonical declaration.
	 * @param rawInitializeResult the raw initialize result; may be {@code null} or
	 * contain arbitrary values
	 * @return the detection result; never {@code null}
	 */
	public static SkillsExtensionSupport fromRawInitializeResult(final Map<String, Object> rawInitializeResult) {
		if (rawInitializeResult == null) {
			return ABSENT;
		}
		final SkillsExtensionSupport canonical = fromExtensions(extensionsOf(rawInitializeResult.get("capabilities")));
		if (canonical.supported()) {
			return canonical;
		}
		return fromExtensions(extensionsOf(rawInitializeResult.get("_meta")));
	}

	/**
	 * Parses the skills-extension entry out of a raw {@code extensions} map (the value of
	 * {@code capabilities.extensions} or {@code _meta.extensions} of an initialize
	 * result).
	 * @param extensions the raw extensions map; may be {@code null} or contain arbitrary
	 * values
	 * @return the detection result; never {@code null}
	 */
	public static SkillsExtensionSupport fromExtensions(final Map<String, Object> extensions) {
		if (extensions == null) {
			return ABSENT;
		}
		final Object entry = extensions.get(EXTENSION_ID);
		if (!(entry instanceof Map<?, ?> settings)) {
			return ABSENT;
		}
		final Object directoryRead = settings.get("directoryRead");
		return new SkillsExtensionSupport(true, Boolean.TRUE.equals(directoryRead));
	}

	/**
	 * Extracts the {@code extensions} map from a {@code capabilities} or {@code _meta}
	 * container, tolerating any malformed shape.
	 * @param container the raw container value; may be {@code null}
	 * @return the extensions map, or {@code null} when absent or malformed
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> extensionsOf(final Object container) {
		if (!(container instanceof Map<?, ?> map)) {
			return null;
		}
		final Object extensions = map.get("extensions");
		if (extensions instanceof Map<?, ?> extensionMap) {
			return (Map<String, Object>) extensionMap;
		}
		return null;
	}

}
