// [spring-ai-mcp-inspector PATCH] TasksTab behavior tests: list poll, per-task poll, terminal-state stop (#182).
import { fireEvent, render, screen } from "@testing-library/react";
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
  });

  describe("terminal statuses", () => {
    it.each([
      ["completed", "completed"],
      ["success", "success"],
      ["failed", "failed"],
      ["cancelled", "cancelled"],
    ] as const)("stops per-task poll when task status is '%s'", (_label, status) => {
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

    it("stops polling when task changes from working to success", () => {
      const getTask = jest.fn().mockResolvedValue(
        makeTask({ taskId: "t1", status: "success" as Task["status"] }),
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

      // Task transitions to success (terminal)
      const successTasks = [makeTask({ taskId: "t1", status: "success" as Task["status"] })];
      rerender(
        <TasksTab
          {...defaultProps}
          tasks={successTasks}
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
