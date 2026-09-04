// [spring-ai-mcp-inspector PATCH] Test for the connect-error alert UI.
// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import Sidebar from "../Sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import type { ConnectFailure } from "@/lib/connectErrors";
import { LoggingLevel } from "@modelcontextprotocol/sdk/types.js";

1|2|describe("sidebar status text (connectionStatus === error)", () => {
3|    const statusTextProps = { connectionStatus: "error" as const };
4|5|// [spring-ai-mcp-inspector PATCH] Test for the connect-error alert UI.
6|// jsdom lacks MediaQueryList; useTheme calls window.matchMedia on mount.
7|import { fireEvent, render, screen } from "@testing-library/react";
8|import "@testing-library/jest-dom";
9|import Sidebar from "../Sidebar";
10|import { TooltipProvider } from "@/components/ui/tooltip";
11|import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
12|import type { ConnectFailure } from "@/lib/connectErrors";
13|import { LoggingLevel } from "@modelcontextprotocol/sdk/types.js";
14|15|
16|    it('shows "proxy token is correct" for unauthorized', () => {
17|      renderSidebar({
18|        ...statusTextProps,
19|        connectionError: {
20|          code: "MCP_CONNECT_FAILED",
21|          reason: "unauthorized",
22|          message: "Server rejected token",
23|          retryable: true,
24|        },
25|      });
26|      expect(screen.getByText(/proxy token is correct/i)).toBeInTheDocument();
27|    });
28|
29|    it('does NOT mention token for connection_refused', () => {
30|      renderSidebar({
31|        ...statusTextProps,
32|        connectionError: {
33|          code: "MCP_CONNECT_FAILED",
34|          reason: "connection_refused",
35|          message: "Connection refused",
36|          retryable: true,
37|        },
38|      });
39|      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
40|      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
41|    });
42|
43|    it('does NOT mention token for dns', () => {
44|      renderSidebar({
45|        ...statusTextProps,
46|        connectionError: {
47|          code: "MCP_CONNECT_FAILED",
48|          reason: "dns",
49|          message: "Unknown host",
50|          retryable: true,
51|        },
52|      });
53|      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
54|      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
55|    });
56|
57|    it('does NOT mention token for timeout', () => {
58|      renderSidebar({
59|        ...statusTextProps,
60|        connectionError: {
61|          code: "MCP_CONNECT_FAILED",
62|          reason: "timeout",
63|          message: "Timed out",
64|          retryable: true,
65|        },
66|      });
67|      expect(screen.getByText(/not responding/i)).toBeInTheDocument();
68|      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
69|    });
70|
71|    it('does NOT mention token for unknown', () => {
72|      renderSidebar({
73|        ...statusTextProps,
74|        connectionError: {
75|          code: "MCP_CONNECT_FAILED",
76|          reason: "unknown",
77|          message: "Something went wrong",
78|          retryable: true,
79|        },
80|      });
81|      expect(screen.getByText(/MCP server is running/i)).toBeInTheDocument();
82|      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
83|    });
84|
85|    it("shows Reset session button for timeout errors", () => {
86|      renderSidebar({
87|        ...statusTextProps,
88|        connectionError: {
89|          code: "MCP_CONNECT_FAILED",
90|          reason: "timeout",
91|          message: "Connection timed out after 5000ms",
92|          retryable: true,
93|        },
94|      });
95|
96|      expect(screen.getByTestId("reset-session-button")).toBeInTheDocument();
97|      expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
98|    });
99|
100|    it("does not show Reset session button for non-timeout errors", () => {
101|      renderSidebar({
102|        ...statusTextProps,
103|        connectionError: {
104|          code: "MCP_CONNECT_FAILED",
105|          reason: "connection_refused",
106|          message: "Connection refused: connect ECONNREFUSED",
107|          retryable: true,
108|        },
109|      });
110|
111|      expect(
112|        screen.queryByTestId("reset-session-button"),
113|      ).not.toBeInTheDocument();
114|      expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
115|    });
116|
117|    it("calls onResetSession when Reset session is clicked", () => {
118|      const onResetSession = jest.fn();
119|      renderSidebar({
120|        onResetSession,
121|        ...statusTextProps,
122|        connectionError: {
123|          code: "MCP_CONNECT_FAILED",
124|          reason: "timeout",
125|          message: "Connection timed out after 5000ms",
126|          retryable: true,
127|        },
128|      });
129|
130|      fireEvent.click(screen.getByTestId("reset-session-button"));
131|      expect(onResetSession).toHaveBeenCalledTimes(1);
132|    });
133|  });
134|});
135|