import { ServerNotification } from "@modelcontextprotocol/sdk/types.js";
import { useState } from "react";
import JsonView from "./JsonView";
import { Button } from "@/components/ui/button";
import { useKeepAlive } from "@/lib/hooks/useKeepAlive";

const HistoryAndNotifications = ({
  requestHistory,
  serverNotifications,
  onClearHistory,
  onClearNotifications,
  sessionId,
}: {
  requestHistory: Array<{ request: string; response?: string }>;
  serverNotifications: ServerNotification[];
  onClearHistory?: () => void;
  onClearNotifications?: () => void;
  sessionId?: string | null;
}) => {
  const [expandedRequests, setExpandedRequests] = useState<{
    [key: number]: boolean;
  }>({});
  const [expandedNotifications, setExpandedNotifications] = useState<{
    [key: number]: boolean;
  }>({});
  const [keepAliveExpanded, setKeepAliveExpanded] = useState(false);
  const keepAlive = useKeepAlive(sessionId ?? null);

  const toggleRequestExpansion = (index: number) => {
    setExpandedRequests((prev) => ({ ...prev, [index]: !prev[index] }));
  };

  const toggleNotificationExpansion = (index: number) => {
    setExpandedNotifications((prev) => ({ ...prev, [index]: !prev[index] }));
  };

  return (
    <div className="bg-card overflow-hidden flex h-full">
      <div className="flex-1 overflow-y-auto p-4 border-r">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">History</h2>
          <Button
            variant="outline"
            size="sm"
            onClick={onClearHistory}
            disabled={requestHistory.length === 0}
          >
            Clear
          </Button>
        </div>
        {/* [spring-ai-mcp-inspector PATCH] Collapsed keep-alive pings section (#235). */}
        {keepAlive.observed && (
          <div className="mb-4 border border-cyan-800 rounded">
            <div
              className="flex items-center justify-between px-3 py-2 cursor-pointer bg-cyan-950/30 hover:bg-cyan-950/50"
              onClick={() => setKeepAliveExpanded(!keepAliveExpanded)}
            >
              <span className="text-sm font-medium text-cyan-300">
                Keep-alive pings ({keepAlive.recentPings.length})
              </span>
              <span className="text-cyan-400 text-xs">
                {keepAliveExpanded ? "▼" : "▶"}
              </span>
            </div>
            {keepAliveExpanded && (
              <div className="px-3 py-2 space-y-1">
                {keepAlive.recentPings.slice().reverse().map((ping, i) => (
                  <div key={ping} className="text-xs font-mono text-cyan-200">
                    {keepAlive.recentPings.length - i}. {new Date(ping).toLocaleTimeString()}
                  </div>
                ))}
                {keepAlive.recentPings.length === 0 && (
                  <div className="text-xs text-cyan-500 italic">No pings recorded yet</div>
                )}
              </div>
            )}
          </div>
        )}
        {requestHistory.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400 italic">
            No history yet
          </p>
        ) : (
          <ul className="space-y-3">
            {requestHistory
              .slice()
              .reverse()
              .map((request, index) => (
                <li
                  key={index}
                  className="text-sm text-foreground bg-secondary py-2 px-3 rounded"
                >
                  <div
                    className="flex justify-between items-center cursor-pointer"
                    onClick={() =>
                      toggleRequestExpansion(requestHistory.length - 1 - index)
                    }
                  >
                    <span className="font-mono">
                      {requestHistory.length - index}.{" "}
                      {JSON.parse(request.request).method}
                    </span>
                    <span>
                      {expandedRequests[requestHistory.length - 1 - index]
                        ? "▼"
                        : "▶"}
                    </span>
                  </div>
                  {expandedRequests[requestHistory.length - 1 - index] && (
                    <>
                      <div className="mt-2">
                        <div className="flex justify-between items-center mb-1">
                          <span className="font-semibold text-blue-600">
                            Request:
                          </span>
                        </div>

                        <JsonView
                          data={request.request}
                          className="bg-background"
                        />
                      </div>
                      {request.response && (
                        <div className="mt-2">
                          <div className="flex justify-between items-center mb-1">
                            <span className="font-semibold text-green-600">
                              Response:
                            </span>
                          </div>
                          <JsonView
                            data={request.response}
                            className="bg-background"
                          />
                        </div>
                      )}
                    </>
                  )}
                </li>
              ))}
          </ul>
        )}
      </div>
      <div className="flex-1 overflow-y-auto p-4">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Server Notifications</h2>
          <Button
            variant="outline"
            size="sm"
            onClick={onClearNotifications}
            disabled={serverNotifications.length === 0}
          >
            Clear
          </Button>
        </div>
        {serverNotifications.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400 italic">
            No notifications yet
          </p>
        ) : (
          <ul className="space-y-3">
            {serverNotifications
              .slice()
              .reverse()
              .map((notification, index) => (
                <li
                  key={index}
                  className="text-sm text-foreground bg-secondary py-2 px-3 rounded"
                >
                  <div
                    className="flex justify-between items-center cursor-pointer"
                    onClick={() =>
                      toggleNotificationExpansion(
                        serverNotifications.length - 1 - index,
                      )
                    }
                  >
                    <span className="font-mono">
                      {serverNotifications.length - index}.{" "}
                      {notification.method}
                    </span>
                    <span>
                      {expandedNotifications[
                        serverNotifications.length - 1 - index
                      ]
                        ? "▼"
                        : "▶"}
                    </span>
                  </div>
                  {expandedNotifications[
                    serverNotifications.length - 1 - index
                  ] && (
                    <div className="mt-2">
                      <div className="flex justify-between items-center mb-1">
                        <span className="font-semibold text-purple-600">
                          Details:
                        </span>
                      </div>
                      <JsonView
                        data={JSON.stringify(notification, null, 2)}
                        className="bg-background"
                      />
                    </div>
                  )}
                </li>
              ))}
          </ul>
        )}
      </div>
    </div>
  );
};

export default HistoryAndNotifications;
