// [spring-ai-mcp-inspector PATCH] Tasks tab - task list with auto-polling, cancel, and details (#182).
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { TabsContent } from "@/components/ui/tabs";
import { Task } from "@modelcontextprotocol/sdk/types.js";
import {
  AlertCircle,
  RefreshCw,
  XCircle,
  Clock,
  CheckCircle2,
  AlertTriangle,
  PlayCircle,
  ExternalLink,
} from "lucide-react";
import { useState, useEffect, useRef, useCallback } from "react";
import { cn } from "@/lib/utils";

const MCP_TASKS_DOCS_URL =
  "https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks";
const LIST_POLL_INTERVAL_MS = 2000;
const DEFAULT_TASK_POLL_INTERVAL_MS = 2000;
const TTL_COUNTDOWN_INTERVAL_MS = 1000;
const TERMINAL_STATUSES: readonly (Task["status"] | "success")[] = [
  "completed",
  "success",
  "failed",
  "cancelled",
] as const;

const TaskStatusIcon = ({
  status,
  className,
}: {
  status: Task["status"] | "success";
  className?: string;
}) => {
  switch (status) {
    case "working":
      return (
        <Clock
          className={cn("h-4 w-4 animate-pulse text-blue-500", className)}
        />
      );
    case "input_required":
      return (
        <AlertTriangle className={cn("h-4 w-4 text-yellow-500", className)} />
      );
    case "completed":
    case "success":
      return (
        <CheckCircle2 className={cn("h-4 w-4 text-green-500", className)} />
      );
    case "failed":
      return <XCircle className={cn("h-4 w-4 text-red-500", className)} />;
    case "cancelled":
      return <XCircle className={cn("h-4 w-4 text-gray-500", className)} />;
    default:
      return <PlayCircle className={cn("h-4 w-4", className)} />;
  }
};

const formatTtl = (createdAt: string, ttl: number | null): string => {
  if (ttl === null) return "Infinite";
  const elapsed = Date.now() - new Date(createdAt).getTime();
  const remaining = Math.max(0, ttl - elapsed);
  if (remaining <= 0) return "Expired";
  if (remaining < 1000) return `${remaining}ms`;
  if (remaining < 60000) return `${Math.round(remaining / 1000)}s`;
  return `${Math.round(remaining / 60000)}m ${Math.round((remaining % 60000) / 1000)}s`;
};

type TaskRowState = {
  previousStatus: Task["status"];
  transitioning: boolean;
};

const TasksTab = ({
  tasks,
  listTasks,
  clearTasks,
  cancelTask,
  getTask,
  selectedTask,
  setSelectedTask,
  error,
  nextCursor,
  activeTab,
}: {
  tasks: Task[];
  listTasks: () => void;
  clearTasks: () => void;
  cancelTask: (taskId: string) => Promise<void>;
  getTask: (taskId: string) => Promise<Task>;
  selectedTask: Task | null;
  setSelectedTask: (task: Task | null) => void;
  error: string | null;
  nextCursor?: string;
  activeTab: string;
}) => {
  const [isCancelling, setIsCancelling] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now());
  const [ttlCountdowns, setTtlCountdowns] = useState<Record<string, string>>(
    {},
  );
  const [taskRowStates, setTaskRowStates] = useState<
    Record<string, TaskRowState>
  >({});

  // Track per-task polling intervals
  const taskPollIntervalsRef = useRef<Map<string, ReturnType<typeof setInterval>>>(new Map());
  const taskStatusesRef = useRef<Map<string, Task["status"]>>(new Map());
  const transitionTimeoutsRef = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());
  const selectedTaskRef = useRef<Task | null>(null);
  useEffect(() => {
    selectedTaskRef.current = selectedTask;
  }, [selectedTask]);

  // Stable refs for callbacks to avoid effect re-run on every render
  const getTaskRef = useRef(getTask);
  useEffect(() => {
    getTaskRef.current = getTask;
  }, [getTask]);
  const setSelectedTaskRef = useRef(setSelectedTask);
  useEffect(() => {
    setSelectedTaskRef.current = setSelectedTask;
  }, [setSelectedTask]);

  // Auto-poll the task list every ~2s when the tab is active
  useEffect(() => {
    if (activeTab !== "tasks") return;
    const interval = setInterval(() => {
      listTasks();
    }, LIST_POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [listTasks, activeTab]);

  // TTL countdown update every second
  useEffect(() => {
    const interval = setInterval(() => {
      setNow(Date.now());
    }, TTL_COUNTDOWN_INTERVAL_MS);
    return () => clearInterval(interval);
  }, []);

  // Update TTL countdowns reactively
  useEffect(() => {
    const newCountdowns: Record<string, string> = {};
    for (const task of tasks) {
      newCountdowns[task.taskId] = formatTtl(task.createdAt, task.ttl);
    }
    setTtlCountdowns(newCountdowns);
  }, [tasks, now]);

  // Detect status transitions for animation
  useEffect(() => {
    const timeouts = transitionTimeoutsRef.current;
    setTaskRowStates((prev) => {
      const next = { ...prev };
      for (const task of tasks) {
        const prevStatus = taskStatusesRef.current.get(task.taskId);
        if (prevStatus && prevStatus !== task.status) {
          next[task.taskId] = {
            previousStatus: prevStatus,
            transitioning: true,
          };
          // Clear transition flag after animation
          const id = setTimeout(() => {
            setTaskRowStates((s) => {
              if (s[task.taskId]) {
                return {
                  ...s,
                  [task.taskId]: { ...s[task.taskId], transitioning: false },
                };
              }
              return s;
            });
          }, 600);
          timeouts.add(id);
        }
        taskStatusesRef.current.set(task.taskId, task.status);
      }
      return next;
    });
    return () => {
      for (const id of timeouts) clearTimeout(id);
      timeouts.clear();
    };
  }, [tasks]);

  // Per-task polling: for each non-terminal task, poll tasks/get at its pollInterval
  // Keyed on active task IDs (string join) to avoid re-creating intervals every 2s
  // when the tasks array reference changes but the set of active IDs does not.
  const activeTaskIdString = tasks
    .filter((t) => !TERMINAL_STATUSES.includes(t.status))
    .map((t) => t.taskId)
    .sort()
    .join(",");
  useEffect(() => {
    const intervals = taskPollIntervalsRef.current;
    const getTaskFn = getTaskRef.current;
    const setSelectedTaskFn = setSelectedTaskRef.current;
    const activeIds = activeTaskIdString
      ? new Set(activeTaskIdString.split(","))
      : new Set<string>();

    // Stop polling for tasks that are no longer active or no longer in the list
    for (const [taskId] of intervals) {
      if (!activeIds.has(taskId)) {
        clearInterval(intervals.get(taskId));
        intervals.delete(taskId);
      }
    }

    // Start polling for new active tasks
    for (const task of tasks) {
      if (TERMINAL_STATUSES.includes(task.status)) continue;
      if (intervals.has(task.taskId)) continue;

      const pollInterval =
        task.pollInterval ?? DEFAULT_TASK_POLL_INTERVAL_MS;
      const intervalId = setInterval(async () => {
        try {
          const updated = await getTaskFn(task.taskId);
          // Update selectedTask if this is the currently selected task
          if (selectedTaskRef.current?.taskId === task.taskId) {
            setSelectedTaskFn(updated);
          }
        } catch (e) {
          // Ignore polling errors for individual tasks
          console.debug(
            `[TasksTab] Poll error for ${task.taskId}:`,
            e instanceof Error ? e.message : String(e),
          );
        }
      }, pollInterval);
      intervals.set(task.taskId, intervalId);
    }

    return () => {
      // Only clean up during unmount: intervals for surviving active tasks
      // are reused. The 'activeIds' deps guard re-creates intervals only when
      // the active set changes.
      for (const [, intervalId] of intervals) {
        clearInterval(intervalId);
      }
      intervals.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTaskIdString]);

  const displayedTask = selectedTask
    ? tasks.find((t) => t.taskId === selectedTask.taskId) || selectedTask
    : null;

  const handleCancel = useCallback(
    async (taskId: string) => {
      setIsCancelling(taskId);
      try {
        await cancelTask(taskId);
      } finally {
        setIsCancelling(null);
      }
    },
    [cancelTask],
  );

  const hasActiveTasks = tasks.some(
    (t) => !TERMINAL_STATUSES.includes(t.status),
  );
  const buttonText = nextCursor
    ? "List More Tasks"
    : tasks.length === 0
      ? "List Tasks"
      : "Refresh Tasks";

  return (
    <TabsContent value="tasks" className="flex-1 overflow-hidden p-0 m-0">
      <div className="flex h-full overflow-hidden p-4 gap-4">
        <div className="w-1/3 flex flex-col gap-4">
          {/* Task list pane */}
          <div className="bg-card border border-border rounded-lg shadow overflow-y-auto flex-1">
            <div className="p-4 border-b border-gray-200 dark:border-border">
              <h3 className="font-semibold dark:text-white">Tasks</h3>
            </div>
            <div className="p-4">
              <div className="flex gap-2 mb-4">
                <Button
                  variant="outline"
                  className="flex-1"
                  onClick={() => {
                    listTasks();
                  }}
                  disabled={!nextCursor && tasks.length > 0 && !hasActiveTasks}
                >
                  {buttonText}
                </Button>
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => {
                    listTasks();
                  }}
                  title="Refresh now"
                >
                  <RefreshCw className="h-4 w-4" />
                </Button>
              </div>
              {clearTasks && (
                <Button
                  variant="outline"
                  className="w-full mb-4"
                  onClick={clearTasks}
                  disabled={tasks.length === 0}
                >
                  Clear All
                </Button>
              )}
              <div className="space-y-2">
                {tasks.map((task) => {
                  const rowState = taskRowStates[task.taskId];
                  const isTransitioning = rowState?.transitioning;
                  const isActive = !TERMINAL_STATUSES.includes(task.status);
                  return (
                    <div
                      key={task.taskId}
                      data-testid={`task-row-${task.taskId}`}
                      className={cn(
                        "flex items-center py-2 px-4 rounded hover:bg-gray-50 dark:hover:bg-secondary cursor-pointer transition-all duration-300",
                        selectedTask?.taskId === task.taskId &&
                          "bg-accent",
                        isTransitioning && "animate-pulse",
                      )}
                      onClick={() => setSelectedTask(task)}
                    >
                      <div className="flex items-center gap-2 overflow-hidden w-full">
                        <TaskStatusIcon status={task.status} />
                        <div className="flex flex-col overflow-hidden flex-1 min-w-0">
                          <span className="truncate font-medium text-sm">
                            {task.taskId.length > 16
                              ? `${task.taskId.slice(0, 16)}...`
                              : task.taskId}
                          </span>
                          <span className="truncate text-xs text-muted-foreground flex items-center gap-1">
                            <span
                              className={cn(
                                "inline-block px-1 py-0.5 rounded text-[10px] font-medium uppercase",
                                task.status === "working" &&
                                  "bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300",
                                (task.status === "completed" ||
                                  (task.status as string) === "success") &&
                                  "bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300",
                                task.status === "failed" &&
                                  "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300",
                                task.status === "cancelled" &&
                                  "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400",
                                task.status === "input_required" &&
                                  "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300",
                              )}
                            >
                              {task.status.replace("_", " ")}
                            </span>
                            {task.ttl !== null && isActive && (
                              <span className="text-[10px] text-muted-foreground">
                                TTL: {ttlCountdowns[task.taskId] ?? ""}
                              </span>
                            )}
                          </span>
                        </div>
                        {isActive && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-6 w-6 p-0 shrink-0"
                            aria-label={`Cancel task ${task.taskId}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              void handleCancel(task.taskId);
                            }}
                            disabled={isCancelling === task.taskId}
                          >
                            {isCancelling === task.taskId ? (
                              <RefreshCw className="h-3 w-3 animate-spin" />
                            ) : (
                              <XCircle className="h-3 w-3 text-muted-foreground hover:text-destructive" />
                            )}
                          </Button>
                        )}
                      </div>
                    </div>
                  );
                })}
                {tasks.length === 0 && (
                  <div className="text-center py-6 space-y-2">
                    <Clock className="mx-auto h-8 w-8 text-muted-foreground/40" />
                    <p className="text-sm text-muted-foreground">
                      No tasks yet. Launch a long-running tool from the Tools
                      tab to see task lifecycle.
                    </p>
                    <a
                      href={MCP_TASKS_DOCS_URL}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                    >
                      <ExternalLink className="h-3 w-3" />
                      SEP-1686: Tasks specification
                    </a>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 bg-background border border-border rounded-lg">
          {error && (
            <Alert variant="destructive" className="mb-4">
              <AlertCircle className="h-4 w-4" />
              <AlertTitle>Error</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          {displayedTask ? (
            <div className="space-y-6">
              <div className="flex items-center justify-between border-b pb-4">
                <div className="min-w-0 flex-1">
                  <h2 className="text-2xl font-bold tracking-tight">
                    Task Details
                  </h2>
                  <p className="text-muted-foreground truncate">
                    ID: {displayedTask.taskId}
                  </p>
                </div>
                {(displayedTask.status === "working" ||
                  displayedTask.status === "input_required") && (
                  <Button
                    variant="destructive"
                    size="sm"
                    className="shrink-0 ml-4"
                    aria-label={`Cancel task ${displayedTask.taskId}`}
                    onClick={() => handleCancel(displayedTask.taskId)}
                    disabled={isCancelling === displayedTask.taskId}
                  >
                    {isCancelling === displayedTask.taskId ? (
                      <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                    ) : (
                      <XCircle className="mr-2 h-4 w-4" />
                    )}
                    Cancel Task
                  </Button>
                )}
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="rounded-lg border p-3">
                  <p className="text-sm font-medium text-muted-foreground">
                    Status
                  </p>
                  <div className="mt-1 flex items-center gap-2">
                    <TaskStatusIcon status={displayedTask.status} />
                    <span
                      className={cn(
                        "font-semibold capitalize",
                        displayedTask.status === "working" && "text-blue-500",
                        (displayedTask.status === "completed" ||
                          (displayedTask.status as string) === "success") &&
                          "text-green-500",
                        displayedTask.status === "failed" && "text-red-500",
                        displayedTask.status === "cancelled" &&
                          "text-gray-500",
                        displayedTask.status === "input_required" &&
                          "text-yellow-500",
                      )}
                    >
                      {displayedTask.status.replace("_", " ")}
                    </span>
                  </div>
                </div>
                <div className="rounded-lg border p-3">
                  <p className="text-sm font-medium text-muted-foreground">
                    Created At
                  </p>
                  <p className="mt-1 font-medium">
                    {new Date(displayedTask.createdAt).toLocaleString()}
                  </p>
                </div>
                <div className="rounded-lg border p-3">
                  <p className="text-sm font-medium text-muted-foreground">
                    Last Updated
                  </p>
                  <p className="mt-1 font-medium">
                    {displayedTask.lastUpdatedAt
                      ? new Date(displayedTask.lastUpdatedAt).toLocaleString()
                      : "-"}
                  </p>
                </div>
                <div className="rounded-lg border p-3">
                  <p className="text-sm font-medium text-muted-foreground">
                    TTL
                  </p>
                  <p className="mt-1 font-medium">
                    {displayedTask.ttl === null
                      ? "Infinite"
                      : `${displayedTask.ttl}ms`}
                  </p>
                </div>
              </div>

              {/* Details drawer: spec attributes */}
              <div className="rounded-lg border">
                <div className="px-3 py-2 border-b bg-muted/30">
                  <p className="text-sm font-medium">Spec Attributes</p>
                </div>
                <div className="grid grid-cols-3 gap-4 p-3">
                  <div>
                    <p className="text-xs text-muted-foreground">pollInterval</p>
                    <p className="text-sm font-medium">
                      {displayedTask.pollInterval !== undefined
                        ? `${displayedTask.pollInterval}ms`
                        : "default (2s)"}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">keepAlive</p>
                    <p className="text-sm font-medium">
                      {(displayedTask as Task & { keepAlive?: number })
                        .keepAlive !== undefined
                        ? `${(displayedTask as Task & { keepAlive?: number }).keepAlive}ms`
                        : "N/A"}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">TTL remaining</p>
                    <p
                      className={cn(
                        "text-sm font-medium",
                        displayedTask.ttl !== null &&
                          ttlCountdowns[displayedTask.taskId] === "Expired" &&
                          "text-destructive",
                      )}
                    >
                      {displayedTask.ttl !== null
                        ? ttlCountdowns[displayedTask.taskId] ?? ""
                        : "Infinite"}
                    </p>
                  </div>
                </div>
              </div>

              {displayedTask.statusMessage && (
                <div className="rounded-lg border p-3">
                  <p className="text-sm font-medium text-muted-foreground">
                    Status Message
                  </p>
                  <p className="mt-1 whitespace-pre-wrap">
                    {displayedTask.statusMessage}
                  </p>
                </div>
              )}

              <div className="space-y-2">
                <h3 className="text-lg font-semibold">Full Task Object</h3>
                <div className="rounded-md border">
                  <pre className="p-4 text-xs overflow-x-auto whitespace-pre-wrap break-all">
                    {JSON.stringify(displayedTask, null, 2)}
                  </pre>
                </div>
              </div>
            </div>
          ) : (
            <div className="flex h-full items-center justify-center text-muted-foreground">
              <div className="text-center max-w-md">
                <Clock className="mx-auto mb-4 h-12 w-12 opacity-20" />
                <h3 className="text-lg font-medium">No Task Selected</h3>
                <p className="text-sm mt-2">
                  Select a task from the list to view its details, or launch a
                  long-running tool from the Tools tab to create one. The
                  inspector demonstrates MCP Tasks (SEP-1686) - a protocol for
                  managing asynchronous work.
                </p>
                <a
                  href={MCP_TASKS_DOCS_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-sm text-primary hover:underline mt-3"
                >
                  <ExternalLink className="h-3 w-3" />
                  SEP-1686: Tasks specification
                </a>
              </div>
            </div>
          )}
        </div>
      </div>
    </TabsContent>
  );
};

export default TasksTab;