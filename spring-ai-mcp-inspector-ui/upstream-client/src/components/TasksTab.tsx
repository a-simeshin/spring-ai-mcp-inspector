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
const MAX_POLL_BACKOFF_MS = 30000;
const TTL_COUNTDOWN_INTERVAL_MS = 1000;
// Aligned with the SDK TaskStatus enum: the backend emits
// "completed" for finished tasks and never "success".
const TERMINAL_STATUSES: readonly Task["status"][] = [
  "completed",
  "failed",
  "cancelled",
] as const;

const TaskStatusIcon = ({
  status,
  className,
}: {
  status: Task["status"];
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

const formatTtl = (
  referenceTimestamp: string,
  ttl: number | null,
  isActive: boolean,
): string => {
  if (ttl === null) return "Infinite";
  const reference = new Date(referenceTimestamp).getTime();
  // An unparsable timestamp must not leak "NaNm NaNs" into the UI.
  if (Number.isNaN(reference)) return "Invalid date";
  const elapsed = Date.now() - reference;
  const remaining = Math.max(0, ttl - elapsed);
  if (remaining <= 0) {
    // The backend never evicts a working task, so an
    // active task whose TTL has elapsed is still alive: "Past TTL", not the
    // terminal "Expired" (reserved for terminal tasks, which the sweeper
    // removes after their TTL).
    return isActive ? "Past TTL" : "Expired";
  }
  if (remaining < 1000) return `${remaining}ms`;
  if (remaining < 60000) return `${Math.round(remaining / 1000)}s`;
  return `${Math.floor(remaining / 60000)}m ${Math.round((remaining % 60000) / 1000)}s`;
};

type TaskRowState = {
  previousStatus: Task["status"];
  transitioning: boolean;
};

const TasksTab = ({
  tasks,
  tasksSupported = true,
  listTasks,
  clearTasks,
  cancelTask,
  cancelSupported,
  getTask,
  selectedTask,
  setSelectedTask,
  error,
  nextCursor,
  activeTab,
  updateTask,
}: {
  tasks: Task[];
  // [spring-ai-mcp-inspector PATCH] Optional with default true: callers that
  // predate issue #212 keep the supported layout; App passes it explicitly.
  // cancelSupported (also this patch) disables both Cancel affordances with a
  // tooltip when tasks.cancel is absent.
  tasksSupported?: boolean;
  listTasks: () => void;
  clearTasks: () => void;
  cancelTask: (taskId: string) => Promise<void>;
  cancelSupported?: boolean;
  getTask: (taskId: string) => Promise<Task>;
  selectedTask: Task | null;
  setSelectedTask: (task: Task | null) => void;
  error: string | null;
  nextCursor?: string;
  activeTab: string;
  updateTask?: (task: Task) => void;
}) => {
  const [isCancelling, setIsCancelling] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now());
  const [ttlCountdowns, setTtlCountdowns] = useState<Record<string, string>>(
    {},
  );
  const [taskRowStates, setTaskRowStates] = useState<
    Record<string, TaskRowState>
  >({});

  // Track per-task pollers (recursive timeouts with backoff)
  const taskPollersRef = useRef<Map<string, { stop: () => void }>>(new Map());
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
  const updateTaskRef = useRef(updateTask);
  useEffect(() => {
    updateTaskRef.current = updateTask;
  }, [updateTask]);

  // Stable ref for the list callback: App passes an inline arrow, so
  // listTasks gets a new identity on every App render (every MCP call pushes
  // into requestHistory and re-renders App). Reading the ref at call time and
  // keying the effect only on activeTab keeps the timer alive across those
  // re-renders; otherwise the per-task tasks/get alone would tear the list
  // poll down before it fires.
  const listTasksRef = useRef(listTasks);
  useEffect(() => {
    listTasksRef.current = listTasks;
  }, [listTasks]);
  const listErrorRef = useRef(error);
  useEffect(() => {
    listErrorRef.current = error;
  }, [error]);

  // Auto-poll the task list every ~2s when the tab is active. A failing
  // tasks/list (reflected in the error prop on the next tick) doubles the
  // delay up to MAX_POLL_BACKOFF_MS; a clean poll resets it.
  useEffect(() => {
    // [spring-ai-mcp-inspector PATCH] Never poll tasks/list when the server
    // does not advertise the tasks capability (issue #212).
    if (activeTab !== "tasks" || !tasksSupported) return;
    let delay = LIST_POLL_INTERVAL_MS;
    let timeoutId: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      void listTasksRef.current();
      delay = listErrorRef.current
        ? Math.min(delay * 2, MAX_POLL_BACKOFF_MS)
        : LIST_POLL_INTERVAL_MS;
      timeoutId = setTimeout(tick, delay);
    };
    timeoutId = setTimeout(tick, delay);
    return () => {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
    };
  }, [activeTab, tasksSupported]);

  // TTL countdown ticker: only runs while the tasks tab is active and at
  // least one task has a TTL, instead of re-rendering at 1 Hz for the whole
  // session.
  const hasTtlTasks = tasks.some((t) => t.ttl !== null);
  useEffect(() => {
    if (activeTab !== "tasks" || !hasTtlTasks) return;
    const interval = setInterval(() => {
      setNow(Date.now());
    }, TTL_COUNTDOWN_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [activeTab, hasTtlTasks]);

  // Update TTL countdowns reactively. The backend expires
  // a task from its last update timestamp (creation, transitions and
  // keep-alives all refresh it), so the countdown is measured from
  // lastUpdatedAt, not createdAt: a keep-alive moves the countdown.
  useEffect(() => {
    const newCountdowns: Record<string, string> = {};
    for (const task of tasks) {
      newCountdowns[task.taskId] = formatTtl(
        task.lastUpdatedAt || task.createdAt,
        task.ttl,
        !TERMINAL_STATUSES.includes(task.status),
      );
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

  // Per-task polling: for each non-terminal task, poll tasks/get at its own
  // pollInterval. Keyed on active task IDs (string join) so a new tasks array
  // identity with the same IDs never restarts a poller. Callbacks are read
  // through refs at call time so a parent re-render's fresh callback is used
  // by the already-running poller.
  const activeTaskIdString = tasks
    .filter((t) => !TERMINAL_STATUSES.includes(t.status))
    .map((t) => t.taskId)
    .sort()
    .join(",");
  useEffect(() => {
    const pollers = taskPollersRef.current;

    // Same activeTab gate as the list poll above: no tasks/get may run while
    // another tab is shown, and none may run at all when the server does not
    // advertise the tasks capability (issue #212). Leaving the tasks tab (or
    // losing the capability) stops every poller; returning re-runs this
    // effect and restarts them from their base intervals.
    if (activeTab !== "tasks" || !tasksSupported) {
      for (const [taskId, poller] of [...pollers]) {
        poller.stop();
        pollers.delete(taskId);
      }
      return;
    }

    const activeIds = activeTaskIdString
      ? new Set(activeTaskIdString.split(","))
      : new Set<string>();

    // Stop pollers for tasks that became terminal or left the list
    for (const [taskId, poller] of [...pollers]) {
      if (!activeIds.has(taskId)) {
        poller.stop();
        pollers.delete(taskId);
      }
    }

    // Start pollers for newly active tasks. Each poller is a recursive
    // timeout: a failed tasks/get doubles the delay up to MAX_POLL_BACKOFF_MS
    // and a successful one resets it.
    for (const task of tasks) {
      if (TERMINAL_STATUSES.includes(task.status)) continue;
      if (pollers.has(task.taskId)) continue;

      const baseInterval = task.pollInterval ?? DEFAULT_TASK_POLL_INTERVAL_MS;
      // Delay for the NEXT tick, updated from the outcome of the previous
      // poll: a failure doubles it (capped), a success resets it.
      let delay = baseInterval;
      let timeoutId: ReturnType<typeof setTimeout> | undefined;
      const tick = () => {
        // Schedule the next tick synchronously at the current delay so the
        // poller keeps running independently of promise resolution; stop()
        // clears the pending timeout. The outcome below only adjusts the
        // delay used by the following reschedule.
        timeoutId = setTimeout(tick, delay);
        getTaskRef
          .current(task.taskId)
          .then((updated) => {
            delay = baseInterval;
            // A polled task update must reach the tasks list, not only the
            // details pane: otherwise a task that finished between two list
            // polls stays "working" in the list until the next tasks/list.
            if (updateTaskRef.current) {
              updateTaskRef.current(updated);
            }
            // Update selectedTask if this is the currently selected task
            if (selectedTaskRef.current?.taskId === task.taskId) {
              setSelectedTaskRef.current(updated);
            }
          })
          .catch(() => {
            delay = Math.min(delay * 2, MAX_POLL_BACKOFF_MS);
          });
      };
      timeoutId = setTimeout(tick, delay);

      pollers.set(task.taskId, {
        stop: () => {
          if (timeoutId !== undefined) clearTimeout(timeoutId);
        },
      });
    }
    // Intentionally no cleanup here: React would run it on every change of
    // activeTaskIdString and tear down surviving tasks' pollers, restarting
    // their timers from zero. Removals are handled above; unmount is handled
    // by the dedicated effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTaskIdString, activeTab, tasksSupported]);

  // Unmount-only cleanup for the per-task pollers
  useEffect(() => {
    const pollers = taskPollersRef.current;
    return () => {
      for (const poller of pollers.values()) {
        poller.stop();
      }
      pollers.clear();
    };
  }, []);

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
      {!tasksSupported ? (
        // [spring-ai-mcp-inspector PATCH] Reachable empty state for the
        // capability-absent case (issue #212). The exact message matches the
        // acceptance criteria.
        <div className="flex h-full items-center justify-center p-8">
          <div className="text-center max-w-md">
            <Clock className="mx-auto mb-4 h-12 w-12 opacity-20" />
            <h3 className="text-lg font-medium mb-2">Tasks Not Available</h3>
            <p className="text-muted-foreground">
              Server does not advertise the tasks capability; long-running task tracking is unavailable.
            </p>
          </div>
        </div>
      ) : (
        <>
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
                                task.status === "completed" &&
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
                            aria-label={
                              cancelSupported === false
                                ? `Cancel task ${task.taskId} - server does not advertise tasks/cancel capability`
                                : `Cancel task ${task.taskId}`
                            }
                            onClick={(e) => {
                              e.stopPropagation();
                              void handleCancel(task.taskId);
                            }}
                            disabled={
                              cancelSupported === false ||
                              isCancelling === task.taskId
                            }
                            title={
                              cancelSupported === false
                                ? "Server does not advertise the tasks/cancel capability."
                                : undefined
                            }
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
                    aria-label={
                      cancelSupported === false
                        ? `Cancel task ${displayedTask.taskId} - server does not advertise tasks/cancel capability`
                        : `Cancel task ${displayedTask.taskId}`
                    }
                    onClick={() => handleCancel(displayedTask.taskId)}
                    disabled={
                      cancelSupported === false ||
                      isCancelling === displayedTask.taskId
                    }
                    title={
                      cancelSupported === false
                        ? "Server does not advertise the tasks/cancel capability."
                        : undefined
                    }
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
                        displayedTask.status === "completed" &&
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
        </>
      )}
    </TabsContent>
  );
};

export default TasksTab;