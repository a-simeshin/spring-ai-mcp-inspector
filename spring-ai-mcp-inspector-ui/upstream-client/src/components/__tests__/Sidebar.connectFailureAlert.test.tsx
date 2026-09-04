describe("sidebar status text (connectionStatus === error)", () => {
    const statusTextProps = { connectionStatus: "error" as const };

    it('shows "proxy token is correct" for unauthorized', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "unauthorized",
          message: "Server rejected token",
          retryable: true,
        },
      });
      expect(screen.getByText(/proxy token is correct/i)).toBeInTheDocument();
    });

    it('does NOT mention token for connection_refused', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "connection_refused",
          message: "Connection refused",
          retryable: true,
        },
      });
      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for dns', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "dns",
          message: "Unknown host",
          retryable: true,
        },
      });
      expect(screen.getByText(/URL is reachable/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for timeout', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "timeout",
          message: "Timed out",
          retryable: true,
        },
      });
      expect(screen.getByText(/not responding/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it('does NOT mention token for unknown', () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "unknown",
          message: "Something went wrong",
          retryable: true,
        },
      });
      expect(screen.getByText(/MCP server is running/i)).toBeInTheDocument();
      expect(screen.queryByText(/proxy token/i)).not.toBeInTheDocument();
    });

    it("shows Reset session button for timeout errors", () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "timeout",
          message: "Connection timed out after 5000ms",
          retryable: true,
        },
      });

      expect(screen.getByTestId("reset-session-button")).toBeInTheDocument();
      expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
    });

    it("does not show Reset session button for non-timeout errors", () => {
      renderSidebar({
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "connection_refused",
          message: "Connection refused: connect ECONNREFUSED",
          retryable: true,
        },
      });

      expect(
        screen.queryByTestId("reset-session-button"),
      ).not.toBeInTheDocument();
      expect(screen.getByTestId("retry-connect-button")).toBeInTheDocument();
    });

    it("calls onResetSession when Reset session is clicked", () => {
      const onResetSession = jest.fn();
      renderSidebar({
        onResetSession,
        ...statusTextProps,
        connectionError: {
          code: "MCP_CONNECT_FAILED",
          reason: "timeout",
          message: "Connection timed out after 5000ms",
          retryable: true,
        },
      });

      fireEvent.click(screen.getByTestId("reset-session-button"));
      expect(onResetSession).toHaveBeenCalledTimes(1);
    });
  });
});
