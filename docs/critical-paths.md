# Critical user paths

Maps each critical user path through the inspector UI to the integration
test(s) that cover it. Every test listed here is a real `@Test` method in
the demo modules' Selenide end-to-end suites (`spring-ai-mcp-inspector-demo-webmvc`
unless noted otherwise): see [CONTRIBUTING.md](../CONTRIBUTING.md#end-to-end-selenide)
for how to run them.

| Critical user path | Covering integration test |
| --- | --- |
| Connect / switch transport / disconnect | `InspectorUiIT.Connect#connect_withSseDefault_transitionsToConnectedBranch`, `#transportSwitch_toSse_showsUrlInputAndConnects`, `#transportSwitch_toStdio_showsCommandArgsAndEnvInputs`, `#disconnect_afterConnected_returnsToConnectButton`; `InspectorUiIT.Stdio#connect_viaStdioToExternalJar_listsToolsAndCallsEcho`; `InspectorUiIT.ConnectMatrix#connect_viaStreamableHttp_reachesToolsList (parameterized)`; `BootstrapRegressionE2ETest#inspectorBoot_withInlineBootstrap_mountsReactAndShowsConnect`; `InspectorUiSmokeIT#connect_overSse_showsServerInfoInSidebar` (demo-app) |
| List tools and call a tool | `InspectorUiIT.Tools#toolsList_afterListTools_showsAll16Tools`, `#toolsSearch_withQuery_filtersList`, `#callTool_sumWith7And8_resultContains15`, `#callTool_echoWithText_resultContainsText`, `#callTool_structuredOutput_rendersStructuredContent`, `#callTool_multiContent_rendersTextAndImage` |
| List and read resources (static, template, blob) | `InspectorUiIT.Resources#resourcesList_afterList_showsStaticAndTemplates`, `#readResource_greeting_showsHelloFromMcpDemo`, `#readResource_templateWithVariable_expandsAndReads`, `#readResource_blob_showsPngOrBlobPayload`, `#resourcesSearch_withConfig_narrowsToDemoConfig` |
| List and render prompts | `InspectorUiIT.Prompts#promptsList_afterList_showsThreePrompts`, `#renderPrompt_greetingWithName_showsHelloWorld`, `#renderPrompt_multiTurnWithTopic_showsThreeMessages` |
| Sampling (askLlm approve / reject) | `InspectorUiIT.Sampling#samplingRequest_approve_toolReturnsCannedText`, `#samplingRequest_reject_toolSurfacesFailure` |
| Elicitation (askUser submit / decline, url-mode) | `InspectorUiIT.Elicitation#elicitationRequest_submitAnswer_toolReturnsAnswer`, `#elicitationRequest_decline_toolSurfacesUserDecline`; `InspectorUiIT.ElicitationUrlMode#elicitationUrlMode_accept_toolReturnsUserAccepted` |
| Error surfaces (tool error, browser console) | `InspectorUiIT.Tools#callTool_errorTool_surfacesErrorState`; `InspectorUiIT.BrowserConsole#browserConsole_duringConnectAndToolsList_hasNoSevereErrors` |
| Long-running tasks (run / cancel) | `InspectorUiIT.Tasks#tasksTab_runSlowEchoAsTask_showsActiveAndCompleted`, `#tasksTab_cancelLongRunningTask_transitionsToCancelled` |
| OAuth flow | `InspectorUiIT.OAuthFlow#quickOAuthFlow_withStub_mountsSpaAfterCallback`, `#oauthCallbackRoutes_whenOpened_serveTemplatedSpa` |

Note: some prompt-rendering tests currently begin with an `assumeTrue` precondition on the rendered DOM and may self-skip; the honest-e2e change (PR "fail instead of skip") removes those gates. Until it lands, treat the prompts row as partially gated.
