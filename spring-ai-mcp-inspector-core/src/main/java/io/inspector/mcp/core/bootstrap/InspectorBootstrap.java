/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.inspector.mcp.core.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable boot-state payload the inspector backend renders into {@code index.html} (as
 * {@code window.__MCP_INSPECTOR_BOOTSTRAP}) and serves as JSON from the
 * {@code ${path}/config} endpoint.
 *
 * <p>
 * An {@link InspectorBootstrapAssembler} populates framework-default fields
 * ({@code authToken}, {@code proxyAddress}, {@code detectedTransport},
 * {@code detectedUrl}) before invoking any registered
 * {@link InspectorBootstrapCustomizer} beans. Customizers may override any field and may
 * push arbitrary key/value entries into {@link #getExtra()}.
 *
 * <p>
 * The class is a deliberately plain mutable POJO so that Jackson can serialize it with
 * zero configuration and customizers can mutate it in-place.
 */
public class InspectorBootstrap {

	private String authToken;

	private String proxyAddress;

	private String detectedTransport;

	private String detectedUrl;

	private String defaultUrl;

	private String defaultTransport;

	private List<CustomHeader> defaultHeaders = new ArrayList<>();

	private List<ServerEntry> serverEntries = new ArrayList<>();

	private final Map<String, Object> extra = new LinkedHashMap<>();

	public String getAuthToken() {
		return authToken;
	}

	public void setAuthToken(String authToken) {
		this.authToken = authToken;
	}

	public String getProxyAddress() {
		return proxyAddress;
	}

	public void setProxyAddress(String proxyAddress) {
		this.proxyAddress = proxyAddress;
	}

	public String getDetectedTransport() {
		return detectedTransport;
	}

	public void setDetectedTransport(String detectedTransport) {
		this.detectedTransport = detectedTransport;
	}

	public String getDetectedUrl() {
		return detectedUrl;
	}

	public void setDetectedUrl(String detectedUrl) {
		this.detectedUrl = detectedUrl;
	}

	public String getDefaultUrl() {
		return defaultUrl;
	}

	public void setDefaultUrl(String defaultUrl) {
		this.defaultUrl = defaultUrl;
	}

	public String getDefaultTransport() {
		return defaultTransport;
	}

	public void setDefaultTransport(String defaultTransport) {
		this.defaultTransport = defaultTransport;
	}

	public List<CustomHeader> getDefaultHeaders() {
		return defaultHeaders;
	}

	public void setDefaultHeaders(List<CustomHeader> defaultHeaders) {
		this.defaultHeaders = (defaultHeaders != null) ? defaultHeaders : new ArrayList<>();
	}

	public List<ServerEntry> getServerEntries() {
		return serverEntries;
	}

	public void setServerEntries(List<ServerEntry> serverEntries) {
		this.serverEntries = (serverEntries != null) ? serverEntries : new ArrayList<>();
	}

	/**
	 * Returns the mutable {@code extra} map — the escape hatch for customizers that need
	 * to surface fields not modeled as first-class properties. Intentionally has no
	 * setter: the map identity is fixed for the lifetime of this bootstrap instance.
	 */
	public Map<String, Object> getExtra() {
		return extra;
	}

}
