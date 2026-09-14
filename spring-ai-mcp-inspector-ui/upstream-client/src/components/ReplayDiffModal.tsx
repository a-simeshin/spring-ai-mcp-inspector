// [spring-ai-mcp-inspector PATCH] ReplayDiffModal: side-by-side diff dialog for
// Replay & diff action on failed Timeline rows.
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { diffJson, formatDiffValue } from "@/utils/jsonDiff";
import type { ReplayDiffInfo } from "./TimelineTab";

interface ReplayDiffModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  diffInfo: ReplayDiffInfo | null;
  newResponse: Record<string, unknown> | null;
  newTimestamp: string | null;
  loading: boolean;
}

const TYPE_STYLES: Record<string, string> = {
  added: "text-green-400 bg-green-950/30",
  removed: "text-red-400 bg-red-950/30",
  changed: "text-amber-400 bg-amber-950/30",
};

function DiffTable({ entries }: { entries: ReturnType<typeof diffJson> }) {
  if (entries.length === 0) {
    return (
      <div className="text-gray-500 text-sm italic">
        No differences found. The responses are structurally identical.
      </div>
    );
  }
  return (
    <div className="overflow-auto max-h-[50vh] border border-gray-700 rounded">
      <table className="w-full text-xs font-mono">
        <thead className="bg-gray-800 sticky top-0">
          <tr>
            <th className="text-left p-2 text-gray-400">Path</th>
            <th className="text-left p-2 text-gray-400">Change</th>
            <th className="text-left p-2 text-gray-400">Old value</th>
            <th className="text-left p-2 text-gray-400">New value</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.path} className="border-t border-gray-800">
              <td className="p-2 text-gray-300 align-top">{entry.path}</td>
              <td className={`p-2 align-top ${TYPE_STYLES[entry.type] ?? ""}`}>
                {entry.type}
              </td>
              <td className="p-2 text-red-300 align-top whitespace-pre-wrap break-all">
                {entry.type === "added" ? "-" : formatDiffValue(entry.oldValue)}
              </td>
              <td className="p-2 text-green-300 align-top whitespace-pre-wrap break-all">
                {entry.type === "removed" ? "-" : formatDiffValue(entry.newValue)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ReplayDiffModal({
  open,
  onOpenChange,
  diffInfo,
  newResponse,
  newTimestamp,
  loading,
}: ReplayDiffModalProps) {
  if (!diffInfo) return null;

  const oldResult = diffInfo.originalResponse.result;
  const newResult = newResponse?.result;
  const newIsError =
    newResult !== null &&
    typeof newResult === "object" &&
    (newResult as Record<string, unknown>).isError === true;
  const oldIsError = true; // by construction we only open this for failed calls

  const entries = diffJson(oldResult, newResult);

  // If both sides are errors, check whether they are identical.
  const bothErrorsIdentical =
    oldIsError &&
    newIsError &&
    entries.length === 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-hidden flex flex-col bg-gray-900 text-gray-100 border-gray-700">
        <DialogHeader>
          <DialogTitle className="text-gray-100">
            Replay &amp; diff: {diffInfo.toolName}
          </DialogTitle>
          <DialogDescription className="text-gray-400">
            Comparing recorded failed response with the new response.
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-hidden flex flex-col gap-4 min-h-0">
          {/* Side-by-side summary */}
          <div className="grid grid-cols-2 gap-4 shrink-0">
            <div className="border border-red-800 rounded p-3 bg-red-950/20">
              <div className="text-xs font-semibold text-red-300 mb-1">
                Recorded (failed)
              </div>
              <div className="text-[11px] text-gray-400">
                {diffInfo.originalTimestamp}
              </div>
              <div className="text-[11px] text-gray-500 mt-1">
                correlation: {diffInfo.correlationId.substring(0, 8)}
              </div>
            </div>
            <div className={`border rounded p-3 ${newIsError ? "border-red-800 bg-red-950/20" : "border-green-800 bg-green-950/20"}`}>
              <div className={`text-xs font-semibold mb-1 ${newIsError ? "text-red-300" : "text-green-300"}`}>
                {loading ? "Replaying..." : newIsError ? "New (failed)" : "New (success)"}
              </div>
              <div className="text-[11px] text-gray-400">
                {newTimestamp ?? "-"}
              </div>
            </div>
          </div>

          {bothErrorsIdentical ? (
            <div className="p-4 border border-amber-700 rounded bg-amber-950/30 text-amber-200 text-sm">
              The new call failed with the exact same error as the recorded one.
              No differences to show.
            </div>
          ) : (
            <DiffTable entries={entries} />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default ReplayDiffModal;
