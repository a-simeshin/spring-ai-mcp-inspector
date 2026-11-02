import { Alert, AlertDescription } from "@/components/ui/alert";
import { TabsContent } from "@/components/ui/tabs";
import {
  InputRequests,
  InputResponses,
  ToolCallParams,
} from "@/lib/inputRequired";
// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28)
// input_required panel tab (#253). Sibling of the legacy ElicitationTab.tsx
// (2025-06-18 SSE elicitation), which stays untouched.
import InputRequiredChain, {
  PendingInputChain,
} from "./InputRequiredChain";

export type {
  InputRequests,
  InputResponses,
  ToolCallParams,
  PendingInputChain,
};

export type Props = {
  pendingChains: PendingInputChain[];
  onSubmit: (chain: PendingInputChain, responses: InputResponses) => void;
  onCancel: (chain: PendingInputChain) => void;
};

const InputRequiredTab = ({ pendingChains, onSubmit, onCancel }: Props) => {
  return (
    <TabsContent value="input-required">
      <div className="h-96 overflow-y-auto">
        <Alert>
          <AlertDescription>
            When a tools/call result needs more input (SEP-2322
            input_required), the pending requests appear here. Answering
            re-sends the original call with your inputResponses and the
            server&apos;s requestState echoed back.
          </AlertDescription>
        </Alert>
        <div className="mt-4 space-y-4">
          <h3 className="text-lg font-semibold">Pending Input Requests</h3>
          {pendingChains.map((chain) => (
            <InputRequiredChain
              key={chain.correlationId}
              chain={chain}
              onSubmit={onSubmit}
              onCancel={onCancel}
            />
          ))}
          {pendingChains.length === 0 && (
            <p className="text-gray-500">No pending input requests</p>
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default InputRequiredTab;
