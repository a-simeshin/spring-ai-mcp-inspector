/* [spring-ai-mcp-inspector PATCH] CSP serializer: McpUiResourceCsp object => CSP header string. */

/**
 * CSP config object matching @modelcontextprotocol/ext-apps McpUiResourceCsp.
 */
export interface McpUiResourceCsp {
  connectDomains?: string[];
  resourceDomains?: string[];
  frameDomains?: string[];
  baseUriDomains?: string[];
}

/**
 * Serialize a McpUiResourceCsp object to a Content-Security-Policy header value.
 * Uses restrictive defaults for every directive. An absent or empty csp object
 * produces the all-'none' baseline.
 */
export function serializeCsp(csp?: McpUiResourceCsp | null): string {
  const parts: string[] = [];
  parts.push("default-src 'none'");

  // connect-src: omitted/empty => 'none'
  const connectDomains = csp?.connectDomains;
  if (connectDomains && connectDomains.length > 0) {
    parts.push("connect-src " + connectDomains.join(" "));
  } else {
    parts.push("connect-src 'none'");
  }

  // resourceDomains maps to img-src, script-src, style-src, font-src, media-src
  const resourceDomains = csp?.resourceDomains;
  if (resourceDomains && resourceDomains.length > 0) {
    const joined = resourceDomains.join(" ");
    parts.push("img-src " + joined);
    parts.push("script-src 'unsafe-inline' " + joined);
    parts.push("style-src 'unsafe-inline' " + joined);
    parts.push("font-src " + joined);
    parts.push("media-src " + joined);
  } else {
    parts.push("img-src 'none'");
    parts.push("script-src 'unsafe-inline' 'none'");
    parts.push("style-src 'unsafe-inline' 'none'");
    parts.push("font-src 'none'");
    parts.push("media-src 'none'");
  }

  // frame-src: omitted/empty => 'none'
  const frameDomains = csp?.frameDomains;
  if (frameDomains && frameDomains.length > 0) {
    parts.push("frame-src " + frameDomains.join(" "));
  } else {
    parts.push("frame-src 'none'");
  }

  // base-uri: omitted => 'self'
  const baseUriDomains = csp?.baseUriDomains;
  if (baseUriDomains && baseUriDomains.length > 0) {
    parts.push("base-uri " + baseUriDomains.join(" "));
  } else {
    parts.push("base-uri 'self'");
  }

  parts.push("form-action 'none'");

  return parts.join("; ");
}