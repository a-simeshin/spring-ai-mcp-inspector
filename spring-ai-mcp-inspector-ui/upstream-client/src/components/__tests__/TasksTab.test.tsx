// [spring-ai-mcp-inspector PATCH] TasksTab behavior tests: list poll, per-task poll, terminal-state stop (#182).
import { act, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Task } from "@modelcontextprotocol/sdk/types.js";
import TasksTab from "../TasksTab";

// Mock the UI components that may not be fully compatible with jest
jest.mock("@/components/ui/alert", () => ({
  Alert: ({ children, ...props }: React.PropsWithChildren<object>) => (
    <div data-testid="alert" {...props}>
      {children}
    </div>
  ),
  AlertDescription: ({
    children,
    ...props
  }: React.PropsWithChildren<object>) => (
    <div data-testid="alert-description" {...props}>
      {children}
    </div>
  ),
  AlertTitle: ({ children, ...props }: React.PropsWithChildren<object>) => (
    <div data-testid="alert-title" {...props}>
      {children}
    </div>
  ),
}));

jest.mock("@/components/ui/button", () => ({
  Button: ({
    children,
    onClick,
    disabled,
    ...props
  }: React.PropsWithChildren<{
    onClick?: () => void;
    disabled?: boolean;
  }>) => (
    <button onClick={onClick} disabled={disabled} {...props}>
      {children}
    </button>
  ),
}));

jest.mock("@/components/ui/tabs", () => ({
  TabsContent: ({
    children,
    ...props
  }: React.PropsWithChildren<object>) => (
    <div data-testid="tabs-content" {...props}>
      {children}
    </div>
  ),
}));

// Mock lucide-react icons
jest.mock("lucide-react", () => ({
  AlertCircle: () => <div>AlertCircle</div>,
  RefreshCw: () => <div>RefreshCw</div>,
  XCircle: () => <div>XCircle</div>,
  Clock: () => <div>Clock</div>,
  CheckCircle2: () => <div>CheckCircle2</div>,
  AlertTriangle: () => <div>AlertTriangle</div>,
  PlayCircle: () => <div>PlayCircle</div>,
  ExternalLink: () => <div>ExternalLink</div>,
}));

const makeTask = (overrides: Partial<Task> = {}): Task => ({
  taskId: "task-1",
  status: "working" as Task["status"],
  ttl: 60000,
  createdAt: new Date(Date.now() - 10000).toISOString(),
  lastUpdatedAt: new Date(Date.now() - 5000).toISOString(),
  pollInterval: 2000,
  ...overrides,
});

const defaultProps = {
  tasks: [] as Task[],
  listTasks: jest.fn(),
  clearTasks: jest.fn(),
  cancelTask: jest.fn(),
  getTask: jest.fn(),
  selectedTask: null as Task | null,
  setSelectedTask: jest.fn(),
  error: null as string | null,
  nextCursor: undefined as string | undefined,
  activeTab: "tasks",
};

describe("TasksTab behavior tests", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  describe("list poll", () => {
    it("polls tasks/list every 2s when the tasks tab is active", () => {
      const listTasks = jest.fn();
      render(<TasksTab {...defaultProps} listTasks={listTasks} />);

      expect(listTasks).not.toHaveBeenCalled();

      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(1);

      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(2);

      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(3);
    });

    it("does not poll tasks/list when the tasks tab is not active", () => {
      const listTasks = jest.fn();
      render(
        <TasksTab {...defaultProps} listTasks={listTasks} activeTab="tools" />,
      );

      jest.advanceTimersByTime(10000);
      expect(listTasks).not.toHaveBeenCalled();
    });

    it("stops polling when tab changes away from tasks", () => {
      const listTasks = jest.fn();
      const { rerender } = render(
        <TasksTab {...defaultProps} listTasks={listTasks} activeTab="tasks" />,
      );

      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(1);

      rerender(
        <TasksTab {...defaultProps} listTasks={listTasks} activeTab="tools" />,
      );

      jest.advanceTimersByTime(10000);
      expect(listTasks).toHaveBeenCalledTimes(1); // no more calls
    });

    it("keeps the list poll alive across parent re-renders that replace the listTasks identity", () => {
      // Regression (owner review BLOCK 1 on 0a41ca9): App passes an inline
      // arrow, so listTasks has a new identity on every App render, and the
      // per-task tasks/get alone re-renders App every 2s. With the effect
      // keyed on [listTasks, activeTab] the interval was torn down before it
      // fired and tasks/list never ran. Re-renders here arrive every 1900ms
      // against the 2000ms poll: red under the old deps (0 calls), green on
      // the fix.
      const identities: jest.Mock[] = [jest.fn()];
      const { rerender } = render(
        <TasksTab {...defaultProps} listTasks={identities[0]} />,
      );

      for (let i = 1; i <= 10; i++) {
        jest.advanceTimersByTime(1900);
        // Fresh identity every cycle, exactly like App re-rendering
        identities.push(jest.fn());
        rerender(
          <TasksTab
            {...defaultProps}
            listTasks={identities[identities.length - 1]}
          />,
        );
      }

      // The poll must have fired at t=2000, 4000, ... 19000ms despite the
      // re-renders at 1900, 3800, ... 19000ms.
      const totalCalls = identities.reduce(
        (sum, fn) => sum + fn.mock.calls.length,
        0,
      );
      expect(totalCalls).toBeGreaterThanOrEqual(9);
    });

    it("backs off the list poll while tasks/list keeps failing and resets after a clean poll", () => {
      // The error prop is how App reports a failed tasks/list. A failing
      // server must not be re-polled at full rate forever.
      const listTasks = jest.fn();
      const { rerender } = render(
        <TasksTab {...defaultProps} listTasks={listTasks} error={null} />,
      );

      // t=2000: first poll; App then reports the failure via the error prop
      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(1);
      rerender(
        <TasksTab {...defaultProps} listTasks={listTasks} error="boom" />,
      );

      // t=4000: second poll; the delay read at this tick doubles to 4000
      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(2);

      // Backed off: no poll at t=6000
      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(2);

      // t=8000: third poll; delay doubles again to 8000
      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(3);

      // The poll succeeds: App clears the error
      rerender(
        <TasksTab {...defaultProps} listTasks={listTasks} error={null} />,
      );

      // t=16000: fourth poll (one more backed-off gap), then the reset
      // delay of 2000ms puts the fifth at t=18000
      jest.advanceTimersByTime(8000);
      expect(listTasks).toHaveBeenCalledTimes(4);
      jest.advanceTimersByTime(2000);
      expect(listTasks).toHaveBeenCalledTimes(5);
    });
  });

  describe("per-task poll", () => {
    it("starts polling a working task at its pollInterval", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "completed" }),
      );
      const tasks = [makeTask({ taskId: "t1", status: "working", pollInterval: 3000 })];

      render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={getTask}
        />,
      );

      expect(getTask).not.toHaveBeenCalled();

      jest.advanceTimersByTime(3000);
      expect(getTask).toHaveBeenCalledTimes(1);
      expect(getTask).toHaveBeenCalledWith("t1");

      jest.advanceTimersByTime(3000);
      expect(getTask).toHaveBeenCalledTimes(2);
    });

    it("stops polling when task reaches terminal status", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "completed" }),
      );
      const tasks = [makeTask({ taskId: "t1", status: "working", pollInterval: 2000 })];

      const { rerender } = render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(2000);
      expect(getTask).toHaveBeenCalledTimes(1);

      // Task transitions to completed (terminal)
      const updatedTasks = [makeTask({ taskId: "t1", status: "completed" })];
      rerender(
        <TasksTab
          {...defaultProps}
          tasks={updatedTasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(10000);
      expect(getTask).toHaveBeenCalledTimes(1); // no more polls
    });

    it("uses default pollInterval (2s) when task.pollInterval is undefined", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1" }),
      );
      const tasks = [makeTask({ taskId: "t1", status: "working", pollInterval: undefined })];

      render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(2000);
      expect(getTask).toHaveBeenCalledTimes(1);
    });

    it("polls multiple tasks at different intervals", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "working" }),
      );
      const tasks = [
        makeTask({ taskId: "t1", status: "working", pollInterval: 1000 }),
        makeTask({ taskId: "t2", status: "working", pollInterval: 3000 }),
      ];

      render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(1000);
      expect(getTask).toHaveBeenCalledTimes(1);
      expect(getTask).toHaveBeenCalledWith("t1");

      jest.advanceTimersByTime(2000); // total 3000
      expect(getTask).toHaveBeenCalledTimes(4); // t1@1000, t1@2000, t1@3000, t2@3000
    });

    it("uses the latest getTask callback when parent re-renders with same task IDs", () => {
      // Regression: effect deps are [activeTaskIdString] only, so the interval
      // must read getTask/setSelectedTask through refs at call time rather
      // than capturing them at effect setup. Otherwise a fresh callback from
      // a parent re-render is never used and the poll goes stale.
      const firstGetTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "working" }),
      );
      const secondGetTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "working" }),
      );
      const tasks = [
        makeTask({ taskId: "t1", status: "working", pollInterval: 1000 }),
      ];

      const { rerender } = render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={firstGetTask}
        />,
      );

      jest.advanceTimersByTime(1000);
      expect(firstGetTask).toHaveBeenCalledTimes(1);
      expect(secondGetTask).not.toHaveBeenCalled();

      // Parent re-renders: fresh tasks array (same IDs), fresh getTask
      // callback identity. Active ID string is unchanged, so the effect
      // does NOT re-run and the existing interval survives.
      rerender(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 1000 })]}
          getTask={secondGetTask}
        />,
      );

      jest.advanceTimersByTime(1000);
      // The still-running interval must call the NEW callback via the ref,
      // not the stale one captured when the effect first ran.
      expect(secondGetTask).toHaveBeenCalledTimes(1);
      expect(secondGetTask).toHaveBeenCalledWith("t1");
      expect(firstGetTask).toHaveBeenCalledTimes(1); // unchanged
    });

    it("keeps polling despite parent re-renders with fresh array identity faster than pollInterval", () => {
      // Regression (owner review on 0a41ca9, list-poll analogue): with effect
      // deps [tasks, getTask, setSelectedTask] every re-render tears the
      // poller down before it fires. Here re-renders arrive every 1900ms
      // while pollInterval is 2000ms: under the reverted deps the task is
      // never polled, on the fixed head it is.
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "working" }),
      );

      const { rerender } = render(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 2000 })]}
          getTask={getTask}
        />,
      );

      // 10 re-renders with a fresh tasks array every 1900ms (faster than the
      // 2000ms pollInterval), same task id
      for (let i = 0; i < 10; i++) {
        jest.advanceTimersByTime(1900);
        rerender(
          <TasksTab
            {...defaultProps}
            tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 2000 })]}
            getTask={getTask}
          />,
        );
      }

      expect(getTask.mock.calls.length).toBeGreaterThanOrEqual(1);
    });

    it("does not restart a surviving task's poller when another task joins the list", () => {
      // Regression: the old cleanup cleared the whole interval map on every
      // dep change, so every surviving task's timer restarted from zero.
      // t1 (3000ms) must still fire at t=3000 even though t2 (1000ms) was
      // added at t=1000.
      const getTask = jest.fn().mockImplementation((id: string) =>
        Promise.resolve(makeTask({ taskId: id, status: "working" })),
      );

      const { rerender } = render(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 3000 })]}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(1000);
      // t2 joins: active ID string changes, effect re-runs, but t1's poller
      // must survive untouched
      rerender(
        <TasksTab
          {...defaultProps}
          tasks={[
            makeTask({ taskId: "t1", status: "working", pollInterval: 3000 }),
            makeTask({ taskId: "t2", status: "working", pollInterval: 1000 }),
          ]}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(2000); // total 3000: t1's original schedule
      expect(getTask).toHaveBeenCalledWith("t1");
      const t1Calls = getTask.mock.calls.filter(([id]) => id === "t1");
      expect(t1Calls.length).toBe(1);
    });

    it("backs off per-task polling on repeated failures and resets on success", async () => {
      const getTask = jest
        .fn()
        .mockRejectedValueOnce(new Error("boom"))
        .mockRejectedValueOnce(new Error("boom"))
        .mockResolvedValue(makeTask({ taskId: "t1", status: "working" }));

      render(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 1000 })]}
          getTask={getTask}
        />,
      );

      // advanceTimersByTimeAsync also flushes the promise handlers that
      // adjust the backoff delay.
      // t=1000: first poll fails -> delay becomes 2000
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(1);

      // t=2000: second poll (scheduled at the base interval before the first
      // failure landed); it fails too -> delay becomes 4000
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(2);

      // Backed off: no poll at t=3000
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(2);

      // t=4000: third poll fires and succeeds -> delay resets to 1000ms
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(3);

      // t=5000..7000: no poll (this tick's reschedule used the backed-off
      // 4000ms delay before the reset)
      await jest.advanceTimersByTimeAsync(3000);
      expect(getTask).toHaveBeenCalledTimes(3);

      // t=8000 and t=9000: back to the base interval
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(4);
      await jest.advanceTimersByTimeAsync(1000);
      expect(getTask).toHaveBeenCalledTimes(5);
    });

    it("propagates a polled task update into the tasks list, not only the selected task", async () => {
      // Regression (owner review BLOCK on 1c0a8d7): the poll result went only
      // to setSelectedTask, so a task that finished between two list polls
      // stayed "working" in the list and kept its poller alive.
      const finished = makeTask({ taskId: "t1", status: "completed" });
      const getTask = jest.fn().mockResolvedValue(finished);
      const updateTask = jest.fn();

      render(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 1000 })]}
          getTask={getTask}
          updateTask={updateTask}
        />,
      );

      await jest.advanceTimersByTimeAsync(1000);
      expect(updateTask).toHaveBeenCalledWith(finished);
    });
  });

  describe("per-task poll gating", () => {
    it("does not start tasks/get polling when the tasks tab is not active", () => {
      // Regression (owner review BLOCK on 1c0a8d7): the per-task poller had
      // no activeTab gate, so a working task was polled every 2 s forever
      // while the user was on another tab (60 s on Tools: 30 getTask).
      const getTask = jest.fn().mockResolvedValue(makeTask({ taskId: "t1" }));

      render(
        <TasksTab
          {...defaultProps}
          tasks={[makeTask({ taskId: "t1", status: "working", pollInterval: 1000 })]}
          getTask={getTask}
          activeTab="tools"
        />,
      );

      jest.advanceTimersByTime(60000);
      expect(getTask).not.toHaveBeenCalled();
    });

    it("stops all per-task pollers when the tab changes away and resumes them when it returns", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "working" }),
      );
      const tasks = [makeTask({ taskId: "t1", status: "working", pollInterval: 2000 })];

      const { rerender } = render(
        <TasksTab {...defaultProps} tasks={tasks} getTask={getTask} />,
      );

      jest.advanceTimersByTime(2000);
      expect(getTask).toHaveBeenCalledTimes(1);

      rerender(
        <TasksTab {...defaultProps} tasks={tasks} getTask={getTask} activeTab="tools" />,
      );

      jest.advanceTimersByTime(60000);
      expect(getTask).toHaveBeenCalledTimes(1); // pollers stopped on leave

      rerender(
        <TasksTab {...defaultProps} tasks={tasks} getTask={getTask} />,
      );

      jest.advanceTimersByTime(2000);
      expect(getTask).toHaveBeenCalledTimes(2); // resumed from the base interval
    });
  });

  describe("TTL formatting", () => {
    it("renders 'Invalid date' instead of NaN for an unparsable lastUpdatedAt", () => {
      const tasks = [
        makeTask({ taskId: "t1", status: "working", lastUpdatedAt: "not-a-date" }),
      ];

      render(<TasksTab {...defaultProps} tasks={tasks} />);

      expect(screen.getByText("TTL: Invalid date")).toBeInTheDocument();
      expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    });

    it("measures the countdown from lastUpdatedAt, so a keep-alive moves it", () => {
      // createdAt is 10 s old, lastUpdatedAt 1 s old, ttl 5 s: the countdown
      // must show 4 s from the last update. Measuring from createdAt instead
      // would show the terminal "Expired" although the task was just kept
      // alive by the backend.
      const tasks = [
        makeTask({
          taskId: "t1",
          status: "working",
          ttl: 5000,
          createdAt: new Date(Date.now() - 10000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 1000).toISOString(),
        }),
      ];

      render(<TasksTab {...defaultProps} tasks={tasks} />);

      expect(screen.getByText("TTL: 4s")).toBeInTheDocument();
      expect(screen.queryByText("Expired")).not.toBeInTheDocument();
    });

    it("shows '2m 30s' for 150 seconds remaining", () => {
      const now = new Date().toISOString();
      const tasks = [
        makeTask({
          taskId: "t1",
          status: "working",
          ttl: 150000,
          createdAt: now,
          lastUpdatedAt: now,
        }),
      ];

      render(<TasksTab {...defaultProps} tasks={tasks} />);

      // Math.round on the minutes produced a bogus "3m 30s"
      expect(screen.getByText("TTL: 2m 30s")).toBeInTheDocument();
    });

    it("shows 'Past TTL' for an active task whose TTL elapsed and 'Expired' only for a terminal one", () => {
      // The backend (#196 TaskRegistry) never evicts working tasks, so an
      // active task past its TTL is still alive; "Expired" is reserved for
      // terminal tasks, which the sweeper removes after their TTL.
      const old = new Date(Date.now() - 10000).toISOString();
      const active = [
        makeTask({
          taskId: "t1",
          status: "working",
          ttl: 5000,
          createdAt: old,
          lastUpdatedAt: old,
        }),
      ];

      const { rerender } = render(
        <TasksTab {...defaultProps} tasks={active} />,
      );
      expect(screen.getByText("TTL: Past TTL")).toBeInTheDocument();

      rerender(
        <TasksTab
          {...defaultProps}
          tasks={[
            makeTask({
              taskId: "t1",
              status: "completed",
              ttl: 5000,
              createdAt: old,
              lastUpdatedAt: old,
            }),
          ]}
          selectedTask={active[0]}
        />,
      );
      expect(screen.getByText("Expired")).toBeInTheDocument();
      expect(screen.queryByText("Past TTL")).not.toBeInTheDocument();
    });

    it("advances the TTL countdown once per second while the tasks tab is active", () => {
      const tasks = [
        makeTask({
          taskId: "t1",
          status: "working",
          ttl: 5000,
          createdAt: new Date(Date.now() - 1000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 1000).toISOString(),
        }),
      ];

      render(<TasksTab {...defaultProps} tasks={tasks} />);
      expect(screen.getByText("TTL: 4s")).toBeInTheDocument();

      act(() => {
        jest.advanceTimersByTime(1000);
      });
      expect(screen.getByText("TTL: 3s")).toBeInTheDocument();
    });

    it("does not tick the TTL countdown while another tab is active", () => {
      const tasks = [
        makeTask({
          taskId: "t1",
          status: "working",
          ttl: 5000,
          createdAt: new Date(Date.now() - 1000).toISOString(),
          lastUpdatedAt: new Date(Date.now() - 1000).toISOString(),
        }),
      ];

      render(
        <TasksTab {...defaultProps} tasks={tasks} activeTab="tools" />,
      );
      expect(screen.getByText("TTL: 4s")).toBeInTheDocument();

      act(() => {
        jest.advanceTimersByTime(3000);
      });
      // No 1 Hz ticker off the tasks tab: the shown value stays stale on
      // purpose instead of re-rendering the hidden tab every second.
      expect(screen.getByText("TTL: 4s")).toBeInTheDocument();
    });
  });

  describe("terminal statuses", () => {
    it.each([
      ["completed"],
      ["failed"],
      ["cancelled"],
    ] as const)("stops per-task poll when task status is '%s'", (status) => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: status as Task["status"] }),
      );
      const tasks = [makeTask({ taskId: "t1", status: status as Task["status"] })];

      render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(10000);
      expect(getTask).not.toHaveBeenCalled();
    });

    it("stops polling when task changes from working to completed", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "completed" }),
      );
      const workingTasks = [makeTask({ taskId: "t1", status: "working", pollInterval: 2000 })];

      const { rerender } = render(
        <TasksTab
          {...defaultProps}
          tasks={workingTasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(2000);
      expect(getTask).toHaveBeenCalledTimes(1);

      // Task transitions to completed (terminal in the SDK enum; the backend
      // of #196 emits this status)
      const doneTasks = [makeTask({ taskId: "t1", status: "completed" })];
      rerender(
        <TasksTab
          {...defaultProps}
          tasks={doneTasks}
          getTask={getTask}
        />,
      );

      jest.advanceTimersByTime(10000);
      expect(getTask).toHaveBeenCalledTimes(1); // no more polls
    });
  });

  describe("cancel", () => {
    it("calls cancelTask when cancel button is clicked", () => {
      const cancelTask = jest.fn().mockResolvedValue(undefined);
      const tasks = [makeTask({ taskId: "t1", status: "working" })];

      render(
        <TasksTab
          {...defaultProps}
          tasks={tasks}
          cancelTask={cancelTask}
        />,
      );

      const cancelButton = screen.getByLabelText("Cancel task t1");
      fireEvent.click(cancelButton);

      expect(cancelTask).toHaveBeenCalledWith("t1");
    });
  });
});
