import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { TabsContent } from "@/components/ui/tabs";
import {
  AlertCircle,
  RefreshCw,
  BookOpen,
  CheckCircle2,
  XCircle,
  FileText,
  FolderOpen,
  ChevronDown,
  ChevronRight,
} from "lucide-react";
import { useState, useCallback, useEffect } from "react";

// Types for SEP-2640 skills extension
interface SkillResource {
  uri: string;
  digest: string;
  size: number;
}

interface SkillEntry {
  uri: string;
  frontmatter: Record<string, unknown>;
  resources: SkillResource[];
}

interface DirectoryResource {
  uri: string;
  name: string;
  mimeType: string;
}

interface ReadContent {
  uri: string;
  mimeType: string;
  text: string;
}

interface SkillsTabProps {
  listSkills: () => Promise<SkillEntry[]>;
  getSkill: (uri: string) => Promise<SkillEntry>;
  readDirectory: (uri: string) => Promise<DirectoryResource[]>;
  readResource: (uri: string) => Promise<ReadContent>;
  error: string | null;
  clearError: () => void;
}

// Compute SHA-256 digest of a string, returning "sha256:<hex>"
async function computeDigest(text: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(text);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  const hex = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
  return "sha256:" + hex;
}

function CollapsibleSection({
  title,
  defaultOpen = false,
  children,
}: {
  title: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  return (
    <div className="rounded-lg border">
      <button
        className="flex items-center gap-2 w-full px-4 py-3 text-left font-semibold hover:bg-accent/50"
        onClick={() => setIsOpen(!isOpen)}
        aria-expanded={isOpen}
      >
        {isOpen ? (
          <ChevronDown className="w-4 h-4" />
        ) : (
          <ChevronRight className="w-4 h-4" />
        )}
        {title}
      </button>
      {isOpen && <div className="px-4 pb-4">{children}</div>}
    </div>
  );
}

function FrontmatterView({
  frontmatter,
  listName,
  listDescription,
}: {
  frontmatter: Record<string, unknown>;
  listName?: string;
  listDescription?: string;
}) {
  const fmName = typeof frontmatter["name"] === "string" ? frontmatter["name"] : undefined;
  const fmDescription = typeof frontmatter["description"] === "string" ? frontmatter["description"] : undefined;

  const nameMismatch = listName !== undefined && fmName !== undefined && fmName !== listName;
  const descMismatch =
    listDescription !== undefined &&
    fmDescription !== undefined &&
    fmDescription !== listDescription;

  return (
    <div className="space-y-2">
      {nameMismatch && (
        <Alert variant="destructive" className="py-2">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle className="text-sm">Frontmatter name mismatch</AlertTitle>
          <AlertDescription className="text-xs">
            SKILL.md has name &quot;{fmName}&quot; but skills/list advertises &quot;{listName}&quot;
          </AlertDescription>
        </Alert>
      )}
      {!nameMismatch && descMismatch && (
        <Alert variant="destructive" className="py-2">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle className="text-sm">Frontmatter description mismatch</AlertTitle>
          <AlertDescription className="text-xs">
            SKILL.md description does not match skills/list entry
          </AlertDescription>
        </Alert>
      )}
      <table className="w-full text-sm">
        <tbody>
          {Object.entries(frontmatter).map(([key, value]) => (
            <tr key={key} className="border-b last:border-0">
              <td className="py-1 pr-4 font-medium text-muted-foreground align-top whitespace-nowrap">
                {key}
              </td>
              <td className="py-1 text-foreground break-words">
                {typeof value === "object" && value !== null
                  ? JSON.stringify(value, null, 2)
                  : String(value)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DigestIndicator({
  actualDigest,
  expectedDigest,
  fileName,
}: {
  actualDigest: string;
  expectedDigest: string;
  fileName: string;
}) {
  const match = actualDigest === expectedDigest;
  return (
    <div className="flex items-center gap-2" title={`Expected: ${expectedDigest}\nActual: ${actualDigest}`}>
      {match ? (
        <CheckCircle2 className="w-4 h-4 text-green-500 flex-shrink-0" />
      ) : (
        <XCircle className="w-4 h-4 text-red-500 flex-shrink-0" />
      )}
      <span className={match ? "text-green-600" : "text-red-600"}>{fileName}</span>
    </div>
  );
}

function FileContentViewer({ content }: { content: ReadContent }) {
  return (
    <div className="rounded-md border bg-muted/30">
      <div className="px-3 py-2 text-xs text-muted-foreground border-b bg-muted/50 font-mono truncate">
        {content.uri}
      </div>
      <pre className="p-3 text-sm font-mono overflow-auto max-h-96 whitespace-pre-wrap break-all">
        {content.text}
      </pre>
    </div>
  );
}

const SkillsTab = ({
  listSkills,
  getSkill,
  readDirectory,
  readResource,
  error,
  clearError,
}: SkillsTabProps) => {
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [selectedUri, setSelectedUri] = useState<string | null>(null);
  const [selectedSkill, setSelectedSkill] = useState<SkillEntry | null>(null);
  const [directoryFiles, setDirectoryFiles] = useState<DirectoryResource[]>([]);
  const [fileContents, setFileContents] = useState<Map<string, ReadContent>>(new Map());
  const [digests, setDigests] = useState<Map<string, string>>(new Map());
  const [isLoadingList, setIsLoadingList] = useState(false);
  const [isLoadingDetail, setIsLoadingDetail] = useState(false);
  const [isLoadingFileContent, setIsLoadingFileContent] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  const handleListSkills = useCallback(async () => {
    setIsLoadingList(true);
    setLocalError(null);
    clearError();
    try {
      const result = await listSkills();
      setSkills(result);
      setSelectedUri(null);
      setSelectedSkill(null);
      setDirectoryFiles([]);
      setFileContents(new Map());
      setDigests(new Map());
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setLocalError(msg);
    } finally {
      setIsLoadingList(false);
    }
  }, [listSkills, clearError]);

  const handleSelectSkill = useCallback(
    async (uri: string) => {
      setSelectedUri(uri);
      setIsLoadingDetail(true);
      setLocalError(null);
      setDirectoryFiles([]);
      setFileContents(new Map());
      setDigests(new Map());
      try {
        const skill = await getSkill(uri);
        setSelectedSkill(skill);

        // Load directory listing for the skill
        const dirUri = uri.substring(0, uri.lastIndexOf("/"));
        try {
          const files = await readDirectory(dirUri);
          setDirectoryFiles(files);
        } catch {
          // Directory listing is optional; continue with what we have
        }
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        setLocalError(msg);
      } finally {
        setIsLoadingDetail(false);
      }
    },
    [getSkill, readDirectory],
  );

  const handleReadFile = useCallback(
    async (fileUri: string) => {
      if (fileContents.has(fileUri)) return;
      setIsLoadingFileContent(fileUri);
      try {
        const content = await readResource(fileUri);
        setFileContents((prev) => {
          const next = new Map(prev);
          next.set(fileUri, content);
          return next;
        });

        // Client-side digest computation
        const computedDigest = await computeDigest(content.text);
        setDigests((prev) => {
          const next = new Map(prev);
          next.set(fileUri, computedDigest);
          return next;
        });
      } catch {
        // File read failure is non-fatal
      } finally {
        setIsLoadingFileContent(null);
      }
    },
    [readResource, fileContents],
  );

  // Auto-load selected skill detail
  useEffect(() => {
    if (selectedUri && !selectedSkill) {
      handleSelectSkill(selectedUri);
    }
  }, [selectedUri, selectedSkill, handleSelectSkill]);

  const displayError = error || localError;

  const selectedSkillFromList = selectedUri
    ? skills.find((s) => s.uri === selectedUri) || null
    : null;

  return (
    <TabsContent value="skills" className="flex-1 overflow-hidden p-0 m-0">
      <div className="flex h-full overflow-hidden p-4 gap-4">
        <div className="w-1/3">
          <div className="bg-card border border-border rounded-lg shadow max-h-[24vh] overflow-y-auto sm:max-h-none sm:overflow-y-visible">
            <div className="p-4 border-b border-gray-200 dark:border-border">
              <h3 className="font-semibold dark:text-white">Skills</h3>
            </div>
            <div className="p-4">
              <Button
                variant="outline"
                className="w-full mb-4"
                onClick={handleListSkills}
                disabled={isLoadingList}
              >
                {isLoadingList ? (
                  <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <RefreshCw className="mr-2 h-4 w-4" />
                )}
                {skills.length > 0 ? "Refresh" : "List Skills"}
              </Button>
              <div className="space-y-2 overflow-y-auto max-h-96">
                {skills.map((skill, index) => (
                  <div
                    key={skill.uri}
                    data-testid={`skill-row-${index}`}
                    className={`flex items-center py-2 px-4 rounded hover:bg-gray-50 dark:hover:bg-secondary cursor-pointer ${
                      selectedUri === skill.uri
                        ? "bg-accent"
                        : ""
                    }`}
                    onClick={() => setSelectedUri(skill.uri)}
                  >
                    <BookOpen className="w-4 h-4 mr-2 flex-shrink-0" />
                    <div className="overflow-hidden">
                      <span className="font-medium block truncate">
                        {typeof skill.frontmatter["name"] === "string"
                          ? skill.frontmatter["name"]
                          : skill.uri.split("/").slice(-2, -1)[0] || skill.uri}
                      </span>
                      <span className="text-xs text-muted-foreground block truncate">
                        {typeof skill.frontmatter["description"] === "string"
                          ? skill.frontmatter["description"]
                          : ""}
                      </span>
                    </div>
                  </div>
                ))}
                {skills.length === 0 && !isLoadingList && (
                  <div className="text-center py-4 text-muted-foreground">
                    <BookOpen className="mx-auto mb-2 h-8 w-8 opacity-20" />
                    <p className="text-sm">No skills listed</p>
                    <p className="text-xs mt-1">Click &quot;List Skills&quot; to load</p>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 bg-background border border-border rounded-lg">
          {displayError && (
            <Alert variant="destructive" className="mb-4">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>Error</AlertTitle>
              <AlertDescription>{displayError}</AlertDescription>
            </Alert>
          )}

          {isLoadingDetail && (
            <div className="flex items-center justify-center h-32">
              <RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          )}

          {!isLoadingDetail && selectedSkill && (
            <div className="space-y-4">
              <div className="border-b pb-4">
                <h2 className="text-2xl font-bold tracking-tight">
                  {typeof selectedSkill.frontmatter["name"] === "string"
                    ? selectedSkill.frontmatter["name"]
                    : "Skill Details"}
                </h2>
                <p className="text-muted-foreground text-sm mt-1">
                  {selectedSkill.uri}
                </p>
              </div>

              {selectedSkillFromList && (
                <CollapsibleSection title="Frontmatter" defaultOpen={true}>
                  <FrontmatterView
                    frontmatter={selectedSkill.frontmatter}
                    listName={
                      typeof selectedSkillFromList.frontmatter["name"] === "string"
                        ? selectedSkillFromList.frontmatter["name"] as string
                        : undefined
                    }
                    listDescription={
                      typeof selectedSkillFromList.frontmatter["description"] === "string"
                        ? selectedSkillFromList.frontmatter["description"] as string
                        : undefined
                    }
                  />
                </CollapsibleSection>
              )}

              <CollapsibleSection
                title={`Supporting Files (${directoryFiles.length})`}
                defaultOpen={true}
              >
                {directoryFiles.length > 0 ? (
                  <div className="space-y-2">
                    {directoryFiles.map((file) => {
                      const expectedResource = selectedSkill.resources.find(
                        (r) => r.uri === file.uri,
                      );
                      const actualDigest = digests.get(file.uri);
                      const hasContent = fileContents.has(file.uri);

                      return (
                        <div key={file.uri} className="rounded border p-2">
                          <div className="flex items-center justify-between mb-1">
                            <div className="flex items-center gap-2 overflow-hidden">
                              <FileText className="w-4 h-4 flex-shrink-0 text-muted-foreground" />
                              <span className="text-sm font-medium truncate">
                                {file.name}
                              </span>
                              <span className="text-xs text-muted-foreground">
                                ({file.mimeType})
                              </span>
                            </div>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 px-2 text-xs"
                              onClick={() => handleReadFile(file.uri)}
                              disabled={isLoadingFileContent === file.uri || hasContent}
                            >
                              {isLoadingFileContent === file.uri ? (
                                <RefreshCw className="h-3 w-3 animate-spin" />
                              ) : hasContent ? (
                                <CheckCircle2 className="h-3 w-3 text-green-500" />
                              ) : (
                                <FolderOpen className="h-3 w-3" />
                              )}
                              <span className="ml-1">
                                {hasContent ? "Loaded" : "Read"}
                              </span>
                            </Button>
                          </div>

                          <div className="text-xs space-y-1">
                            {expectedResource && (
                              <div className="text-muted-foreground">
                                Size: {expectedResource.size} bytes
                              </div>
                            )}
                            {expectedResource && actualDigest && (
                              <DigestIndicator
                                actualDigest={actualDigest}
                                expectedDigest={expectedResource.digest}
                                fileName="sha256"
                              />
                            )}
                            {expectedResource && !hasContent && (
                              <div className="text-xs text-muted-foreground italic">
                                Click &quot;Read&quot; to verify digest
                              </div>
                            )}
                          </div>

                          {hasContent && fileContents.get(file.uri) && (
                            <div className="mt-2">
                              <FileContentViewer
                                content={fileContents.get(file.uri)!}
                              />
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <div className="text-center py-4 text-muted-foreground">
                    <FileText className="mx-auto mb-2 h-6 w-6 opacity-30" />
                    <p className="text-sm">No supporting files found</p>
                  </div>
                )}
              </CollapsibleSection>

              <CollapsibleSection title="Digest Verification">
                {selectedSkill.resources.length > 0 ? (
                  <div className="space-y-1">
                    <p className="text-xs text-muted-foreground mb-2">
                      Expected digests (SHA-256, as advertised by server):
                    </p>
                    {selectedSkill.resources.map((resource) => {
                      const actualDigest = digests.get(resource.uri);
                      return (
                        <div key={resource.uri} className="flex items-center gap-2 text-sm">
                          {actualDigest ? (
                            <DigestIndicator
                              actualDigest={actualDigest}
                              expectedDigest={resource.digest}
                              fileName={resource.uri.split("/").pop() || resource.uri}
                            />
                          ) : (
                            <div className="flex items-center gap-2 text-muted-foreground">
                              <span className="w-4 h-4 rounded-full border-2 border-muted-foreground flex-shrink-0" />
                              <span>{resource.uri.split("/").pop() || resource.uri}</span>
                              <span className="text-xs italic">(not yet verified)</span>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    No resources to verify.
                  </p>
                )}
              </CollapsibleSection>
            </div>
          )}

          {!isLoadingDetail && !selectedSkill && !displayError && (
            <div className="flex h-full items-center justify-center text-muted-foreground">
              <div className="text-center">
                <BookOpen className="mx-auto mb-4 h-12 w-12 opacity-20" />
                <h3 className="text-lg font-medium">No Skill Selected</h3>
                <p>Select a skill from the list to view its details.</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default SkillsTab;
export type { SkillEntry, SkillResource, DirectoryResource, ReadContent };