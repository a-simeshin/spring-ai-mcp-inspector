import { TabsContent } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
// [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): state machine
import { useState, useRef } from "react";
import { Loader2, Check, X } from "lucide-react";

type PingResult =
  | { kind: "idle" }
  | { kind: "pending" }
  | { kind: "success"; latencyMs: number }
  | { kind: "error"; message: string };

// [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): onPingClick returns Promise for await
const PingTab = ({
  onPingClick,
}: {
  onPingClick: () => Promise<unknown>;
}) => {
  const [result, setResult] = useState<PingResult>({ kind: "idle" });
  const mountedRef = useRef(true);

  const handleClick = () => {
    const startTime = performance.now();
    setResult({ kind: "pending" });

    onPingClick()
      .then(() => {
        if (!mountedRef.current) return;
        const latencyMs = Math.round(performance.now() - startTime);
        setResult({ kind: "success", latencyMs });
      })
      .catch((err: unknown) => {
        if (!mountedRef.current) return;
        const message =
          err instanceof Error ? err.message : String(err);
        setResult({ kind: "error", message });
      });
  };

  return (
    <TabsContent value="ping">
      <div className="grid grid-cols-2 gap-4">
        {/* [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): flex-col layout for result */}
        <div className="col-span-2 flex flex-col justify-center items-center gap-3">
          {/* [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): pending disables button */}
          <Button
            onClick={handleClick}
            disabled={result.kind === "pending"}
            className="font-bold py-6 px-12 rounded-full"
          >
            Ping Server
          </Button>

          {/* [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): pending state */}
          {result.kind === "pending" && (
            <div className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
              <Loader2 className="w-4 h-4 animate-spin" />
              Pinging...
            </div>
          )}

          {/* [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): success state */}
          {result.kind === "success" && (
            <div className="flex items-center gap-2 text-sm text-green-600 dark:text-green-400">
              <Check className="w-4 h-4" />
              Pong! Round-trip: {result.latencyMs}ms
            </div>
          )}

          {/* [spring-ai-mcp-inspector PATCH] Ping inline feedback (#232): error state */}
          {result.kind === "error" && (
            <div className="flex items-center gap-2 text-sm text-red-500 dark:text-red-400 max-w-full break-words px-4 text-center">
              <X className="w-4 h-4 shrink-0" />
              <span>Ping failed: {result.message}</span>
            </div>
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default PingTab;
