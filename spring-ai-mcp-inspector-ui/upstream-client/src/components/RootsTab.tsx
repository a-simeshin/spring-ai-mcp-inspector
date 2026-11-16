import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TabsContent } from "@/components/ui/tabs";
import { Root } from "@modelcontextprotocol/sdk/types.js";
import { Plus, Minus, Save } from "lucide-react";
import { useState } from "react";
import {
  RootUriIssue,
  RootUriValidationResult,
  validateRootUri,
} from "@/lib/rootsUriValidation";

// [spring-ai-mcp-inspector PATCH] Block Save Changes when any root URI is
// invalid and show the reason inline next to the offending field; the
// notifications/roots/list_changed notification stays unsent until every
// root URI is valid.
const ROOT_URI_ERROR_MESSAGES: Record<RootUriIssue, string> = {
  empty: "Root URI is required.",
  missingScheme: "Add a URI scheme, for example file:///path/to/dir.",
  invalidCharacters:
    "Contains characters that are not allowed in a URI. Spaces, control and non-ASCII characters must be percent-encoded.",
  fileUriMissingPath:
    "Specify a path after the file:// scheme, for example file:///path/to/dir.",
};

const RootsTab = ({
  roots,
  setRoots,
  onRootsChange,
}: {
  roots: Root[];
  setRoots: React.Dispatch<React.SetStateAction<Root[]>>;
  onRootsChange: () => void;
}) => {
  const [saveAttempted, setSaveAttempted] = useState(false);

  const addRoot = () => {
    setRoots((currentRoots) => [...currentRoots, { uri: "file://", name: "" }]);
  };

  const removeRoot = (index: number) => {
    setRoots((currentRoots) => currentRoots.filter((_, i) => i !== index));
  };

  const updateRoot = (index: number, field: keyof Root, value: string) => {
    setRoots((currentRoots) =>
      currentRoots.map((root, i) =>
        i === index ? { ...root, [field]: value } : root,
      ),
    );
  };

  const handleSave = () => {
    setSaveAttempted(true);
    if (roots.some((root) => !validateRootUri(root.uri).valid)) {
      return;
    }
    onRootsChange();
  };

  return (
    <TabsContent value="roots">
      <div className="space-y-4">
        <Alert>
          <AlertDescription>
            Configure the root directories that the server can access
          </AlertDescription>
        </Alert>

        {roots.map((root, index) => {
          const validation: RootUriValidationResult = saveAttempted
            ? validateRootUri(root.uri)
            : { valid: true };
          return (
            <div key={index}>
              <div className="flex gap-2 items-center">
                <Input
                  placeholder="file:// URI"
                  value={root.uri}
                  onChange={(e) => updateRoot(index, "uri", e.target.value)}
                  className="flex-1"
                  aria-invalid={validation.valid ? undefined : true}
                  aria-describedby={
                    validation.valid ? undefined : `root-uri-error-${index}`
                  }
                />
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={() => removeRoot(index)}
                >
                  <Minus className="h-4 w-4" />
                </Button>
              </div>
              {!validation.valid && validation.reason && (
                <p
                  id={`root-uri-error-${index}`}
                  role="alert"
                  className="text-xs text-red-500 mt-1"
                >
                  {ROOT_URI_ERROR_MESSAGES[validation.reason]}
                </p>
              )}
            </div>
          );
        })}

        <div className="flex gap-2">
          <Button variant="outline" onClick={addRoot}>
            <Plus className="h-4 w-4 mr-2" />
            Add Root
          </Button>
          <Button onClick={handleSave}>
            <Save className="h-4 w-4 mr-2" />
            Save Changes
          </Button>
        </div>
      </div>
    </TabsContent>
  );
};

export default RootsTab;
