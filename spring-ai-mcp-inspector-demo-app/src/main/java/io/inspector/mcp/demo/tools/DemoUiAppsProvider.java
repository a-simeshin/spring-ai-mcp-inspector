package io.inspector.mcp.demo.tools;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.MetaProvider;
import org.springframework.stereotype.Component;

/**
 * MCP Apps (ui://) fixture tools and resources for the demo server.
 *
 * <p>
 * These fixtures provide two MCP Apps that the Inspector Apps tab can render. The
 * MetaProvider implementations supply {@code _meta.ui.resourceUri} so the frontend's
 * {@code getToolUiResourceUri()} detects them as "App tools". The corresponding resources
 * serve HTML content that exercises each App template.
 */
@Component
public class DemoUiAppsProvider {

	/**
	 * MetaProvider supplying {@code _meta.ui.resourceUri = "ui://demo/form-app"} for the
	 * form fixture App tool.
	 */
	public static class FormAppMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("ui", Map.of("resourceUri", "ui://demo/form-app"));
		}

	}

	/**
	 * MetaProvider supplying {@code _meta.ui.resourceUri = "ui://demo/dashboard-app"} for
	 * the dashboard fixture App tool.
	 */
	public static class DashboardAppMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("ui", Map.of("resourceUri", "ui://demo/dashboard-app"));
		}

	}

	/**
	 * MetaProvider supplying {@code _meta.ui.resourceUri = "ui://demo/missing-app"} for
	 * the malformed-resource fixture tool.
	 */
	public static class MalformedAppMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("ui", Map.of("resourceUri", "ui://demo/missing-app"));
		}

	}

	/**
	 * MetaProvider supplying {@code _meta.ui.csp} for the form fixture App UI resource.
	 * The declared resource domain lets the sandbox serialize {@code script-src
	 * 'unsafe-inline' <domain>}: without an explicit CSP the sandbox baseline policy
	 * blocks every script, so the fixture guest bridge could never answer the host
	 * handshake and the lifecycle pill would stay on {@code initializing}.
	 */
	public static class FormAppResourceMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("ui", Map.of("csp", Map.of("resourceDomains", List.of("localhost"))));
		}

	}

	/**
	 * MetaProvider supplying {@code _meta.ui.csp} for the dashboard fixture App UI
	 * resource: same inline-script unlock as {@link FormAppResourceMetaProvider}.
	 */
	public static class DashboardAppResourceMetaProvider implements MetaProvider {

		@Override
		public Map<String, Object> getMeta() {
			return Map.of("ui", Map.of("csp", Map.of("resourceDomains", List.of("localhost"))));
		}

	}

	@McpTool(name = "formApp", description = "Form fixture App: opens an HTML form in the Apps tab sandbox",
			metaProvider = FormAppMetaProvider.class,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public String formApp() {
		return "Form App activated. Open the Apps tab to see the form.";
	}

	@McpTool(name = "dashboardApp",
			description = "Dashboard fixture App: consumes tool-input-partial streams in the sandbox",
			metaProvider = DashboardAppMetaProvider.class,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public String dashboardApp() {
		return "Dashboard App activated. Open the Apps tab to see the dashboard.";
	}

	@McpTool(name = "malformedAppTool",
			description = "Fixture tool pointing to a non-existent ui:// resource: exercises fallback rendering",
			metaProvider = MalformedAppMetaProvider.class,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
	public String malformedAppTool() {
		return "Malformed App tool. Opening will trigger an error alert.";
	}

	@McpResource(uri = "ui://demo/form-app", name = "form-app-html",
			description = "HTML content for the form fixture App", mimeType = "text/html;profile=mcp-app",
			metaProvider = FormAppResourceMetaProvider.class)
	public String formAppHtml() {
		return """
				<!DOCTYPE html>
				<html>
				<head><meta charset="utf-8"><title>Form App</title></head>
				<body>
				  <h1>Form App (fixture)</h1>
				  <p>Fixture App UI for the demo tools; completes the SEP-1865 handshake.</p>
				  <p id="bridge-state">App bridge waiting for the host handshake.</p>
				  <script>
				    (function () {
				      var state = document.getElementById('bridge-state');
				      // SEP-1865: the guest initiates the ui/initialize handshake and
				      // sends ui/notifications/initialized after the host result arrives.
				      var nextId = 1;
				      window.parent.postMessage({
				        jsonrpc: '2.0', id: nextId, method: 'ui/initialize',
				        params: {
				          protocolVersion: '2026-01-26',
				          appInfo: { name: 'demo-form-app', version: '1.0.0' },
				          appCapabilities: {}
				        }
				      }, '*');
				      window.addEventListener('message', function (event) {
				        var data = event.data;
				        if (!data || data.jsonrpc !== '2.0' || data.id !== nextId) return;
				        if (data.result === undefined) return;
				        window.parent.postMessage({ jsonrpc: '2.0', method: 'ui/notifications/initialized' }, '*');
				        if (state) state.textContent = 'App Bridge initialized successfully.';
				      });
				    })();
				  </script>
				</body>
				</html>
				""";
	}

	@McpResource(uri = "ui://demo/dashboard-app", name = "dashboard-app-html",
			description = "HTML content for the dashboard fixture App", mimeType = "text/html;profile=mcp-app",
			metaProvider = DashboardAppResourceMetaProvider.class)
	public String dashboardAppHtml() {
		return """
				<!DOCTYPE html>
				<html>
				<head><meta charset="utf-8"><title>Dashboard App</title></head>
				<body>
				  <h1>Dashboard App (fixture)</h1>
				  <p>Fixture App UI demonstrating tool-input-partial consumption.</p>
				  <p id="bridge-state">App bridge waiting for the host handshake.</p>
				  <script>
				    (function () {
				      var state = document.getElementById('bridge-state');
				      // SEP-1865: the guest initiates the ui/initialize handshake and
				      // sends ui/notifications/initialized after the host result arrives.
				      var nextId = 1;
				      window.parent.postMessage({
				        jsonrpc: '2.0', id: nextId, method: 'ui/initialize',
				        params: {
				          protocolVersion: '2026-01-26',
				          appInfo: { name: 'demo-dashboard-app', version: '1.0.0' },
				          appCapabilities: {}
				        }
				      }, '*');
				      window.addEventListener('message', function (event) {
				        var data = event.data;
				        if (!data || data.jsonrpc !== '2.0' || data.id !== nextId) return;
				        if (data.result === undefined) return;
				        window.parent.postMessage({ jsonrpc: '2.0', method: 'ui/notifications/initialized' }, '*');
				        if (state) state.textContent = 'App Bridge initialized successfully.';
				      });
				    })();
				  </script>
				</body>
				</html>
				""";
	}

}