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

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Structured proxy error DTO surfaced to the browser on upstream auth/HTTP failures (D3
 * contract). Fields follow the exact literal table: {@code {status, code, reason,
 * guidance, url}}.
 *
 * @param status the HTTP status to surface
 * @param code machine-readable error code (e.g. {@code unauthorized})
 * @param reason human-readable failure description
 * @param guidance actionable remediation hint
 * @param url D5-redacted target URL ({@code scheme://host[:port]/path}, query and
 * fragment stripped)
 * @author Artem Simeshin
 */
@JsonInclude(Include.NON_NULL)
public record ProxyErrorDto(int status, String code, String reason, String guidance, String url) {

	/**
	 * Returns a copy with the {@code url} field set (call sites attach the D5-redacted
	 * target URL after mapping).
	 * @param redactedUrl the redacted URL to attach
	 * @return a new DTO with the url filled in
	 */
	public ProxyErrorDto withUrl(final String redactedUrl) {
		return new ProxyErrorDto(this.status, this.code, this.reason, this.guidance, redactedUrl);
	}

	/**
	 * D5 redaction: returns {@code scheme://host[:port]/path} with the query and fragment
	 * stripped, so an API-key QUERY value never leaks.
	 * @param uri the source URI
	 * @return the redacted URL string
	 */
	public static String redactUrl(final URI uri) {
		if (uri == null) {
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		if (uri.getScheme() != null) {
			sb.append(uri.getScheme()).append("://");
		}
		sb.append((uri.getHost() != null) ? uri.getHost() : "");
		if (uri.getPort() > 0) {
			sb.append(":").append(uri.getPort());
		}
		if (uri.getRawPath() != null && !uri.getRawPath().isBlank()) {
			sb.append(uri.getRawPath());
		}
		return sb.toString();
	}

}
