import { Alert, AlertDescription } from "@/components/ui/alert";
import { TabsContent } from "@/components/ui/tabs";
import { JsonSchemaType } from "@/utils/jsonUtils";
import ElicitationRequest from "./ElicitationRequest";

interface ElicitationRequestBase {
  id: number;
  message: string;
}

export interface ElicitationFormRequestData extends ElicitationRequestBase {
  mode?: "form";
  requestedSchema: JsonSchemaType;
}

export interface ElicitationUrlRequestData extends ElicitationRequestBase {
  mode: "url";
  url: string;
  elicitationId: string;
}

export type ElicitationRequestData =
  | ElicitationFormRequestData
  | ElicitationUrlRequestData;

export interface ElicitationResponse {
  action: "accept" | "decline" | "cancel";
  content?: Record<string, unknown>;
}

export type PendingElicitationRequest = {
  id: number;
  request: ElicitationRequestData;
  originatingTab?: string;
  /** [spring-ai-mcp-inspector PATCH] Issue #298: the tools/call this card is */
  /** correlated with, recorded at arrival while that call was still waiting. */
  toolCallId?: number;
  /** [spring-ai-mcp-inspector PATCH] Issue #298: the correlated tools/call */
  /** has settled, so the user's answer can no longer be delivered. */
  expired?: boolean;
};

export type Props = {
  pendingRequests: PendingElicitationRequest[];
  onResolve: (id: number, response: ElicitationResponse) => void;
};

const ElicitationTab = ({ pendingRequests, onResolve }: Props) => {
  return (
    <TabsContent value="elicitations">
      <div className="h-96">
        <Alert>
          <AlertDescription>
            When the server requests information from the user, requests will
            appear here for response.
          </AlertDescription>
        </Alert>
        <div className="mt-4 space-y-4">
          <h3 className="text-lg font-semibold">Recent Requests</h3>
          {pendingRequests.map((request) => (
            <ElicitationRequest
              key={request.id}
              request={request}
              onResolve={onResolve}
            />
          ))}
          {pendingRequests.length === 0 && (
            <p className="text-gray-500">No pending requests</p>
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default ElicitationTab;
