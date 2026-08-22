# Critical user paths

This table maps the inspector UI's critical user paths to the integration
tests that actually cover them, so a change to one of these flows has a known
place to add or update a test. All classes live under the demo modules'
`e2e`/`ui` packages and drive a real Chromium browser via Selenide (see
[CONTRIBUTING.md](../CONTRIBUTING.md#end-to-end-selenide)).

Tests are named `Class#method` and grouped by the nested `@Nested` class where
applicable (`InspectorUiIT.<Nested>#method`).

| Critical user path | Covering integration test(s) |
| --- | --- |
| Connect to a server (transport switch, disconnect, STDIO/Streamable HTTP/SSE) | `InspectorUiIT.Connect#connect_withSseDefault_transitionsToConnectedBranch`, `InspectorUiIT.Connect#transportSwitch_toSse_showsUrlInputAndConnects`, `InspectorUiIT.Connect#transportSwitch_toStdio_showsCommandArgsAndEnvInputs`, `InspectorUiIT.Connect#disconnect_afterConnected_returnsToConnectButton`, `InspectorUiIT.ConnectMatrix#connect_viaStreamableHttp_reachesToolsList`, `InspectorUiIT.ConnectMatrix#connectionTypeSelect_streamableHttp_exposesViaProxyAndDirect`, `InspectorUiIT.Stdio#connect_viaStdioToExternalJar_listsToolsAndCallsEcho`, `InspectorUiSmokeIT#connect_overSse_showsServerInfoInSidebar`, `BootstrapRegressionE2ETest#inspectorBoot_withInlineBootstrap_mountsReactAndShowsConnect` |
| List tools | `InspectorUiIT.Tools#toolsList_afterListTools_showsAll16Tools`, `InspectorUiIT.Tools#toolsSearch_withQuery_filtersList`, `InspectorUiIT.TabsAvailability#tabs_afterConnect_alwaysOnTabsVisible` |
| Call a tool (primitive/enum/object args, large/multi-content/structured output, async) | `InspectorUiIT.Tools#callTool_sumWith7And8_resultContains15`, `InspectorUiIT.Tools#callTool_echoWithText_resultContainsText`, `InspectorUiIT.Tools#callTool_toggleFlagChecked_returnsFlagIsOn`, `InspectorUiIT.Tools#callTool_chooseColorGreen_returnsGreen`, `InspectorUiIT.Tools#callTool_lookupUserNestedObject_returnsName`, `InspectorUiIT.Tools#callTool_largeOutput_doesNotHangUi`, `InspectorUiIT.Tools#callTool_structuredOutput_rendersStructuredContent`, `InspectorUiIT.Tools#callTool_multiContent_rendersTextAndImage`, `InspectorUiIT.Tools#callTool_slowEcho_showsRunningThenResult` |
| List and read resources (static, template, blob, large, subscribe) | `InspectorUiIT.Resources#resourcesList_afterList_showsStaticAndTemplates`, `InspectorUiIT.Resources#readResource_greeting_showsHelloFromMcpDemo`, `InspectorUiIT.Resources#readResource_markdown_showsMarkdownMimeType`, `InspectorUiIT.Resources#readResource_blob_showsPngOrBlobPayload`, `InspectorUiIT.Resources#readResource_largeText_rendersWithoutCrashing`, `InspectorUiIT.Resources#readResource_templateWithVariable_expandsAndReads`, `InspectorUiIT.Resources#resourcesSearch_withConfig_narrowsToDemoConfig`, `InspectorUiIT.ResourceSubscribe#clockResource_afterListing_emitsServerNotifications` |
| List and render prompts (arguments, multi-turn, completion suggestions) | `InspectorUiIT.Prompts#promptsList_afterList_showsThreePrompts`, `InspectorUiIT.Prompts#renderPrompt_greetingWithName_showsHelloWorld`, `InspectorUiIT.Prompts#renderPrompt_multiTurnWithTopic_showsThreeMessages`, `InspectorUiIT.Prompts#renderPrompt_optionalDescriptionRequiredOnly_succeeds`, `InspectorUiIT.CompletionPopover#completionPopover_typePrefixS_suggestsSports` |
| Sampling and elicitation (server asks the client for input) | `InspectorUiIT.Sampling#samplingRequest_approve_toolReturnsCannedText`, `InspectorUiIT.Sampling#samplingRequest_reject_toolSurfacesFailure`, `InspectorUiIT.Elicitation#elicitationRequest_submitAnswer_toolReturnsAnswer`, `InspectorUiIT.Elicitation#elicitationRequest_decline_toolSurfacesUserDecline` |
| Errors surface without breaking the UI (failing tool call, browser console stays clean) | `InspectorUiIT.Tools#callTool_errorTool_surfacesErrorState`, `InspectorUiIT.BrowserConsole#browserConsole_duringConnectAndToolsList_hasNoSevereErrors` |

`InspectorUiIT` also covers additional flows not listed as a dedicated
critical path (ping, history, theme switching, roots, tasks, OAuth stub):
see its nested classes for the full inventory.
