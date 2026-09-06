// [spring-ai-mcp-inspector PATCH] Client-side required/type validation for
// prompt forms. The Get Prompt button is derived-disabled on every render
// via validatePromptArgs(), and submit is blocked by validateAll().
// fieldErrors state is only for showing hints after blur/submit.
// [spring-ai-mcp-inspector PATCH] Component tests for PromptsTab validation
// (see NOTICE.d/param-validation.txt).
import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest } from "@jest/globals";
import PromptsTab, { Prompt } from "../PromptsTab";
import { Tabs } from "../ui/tabs";

// Mock Combobox to render a simple input for testing
jest.mock("@/components/ui/combobox", () => ({
  Combobox: ({
    value,
    onChange,
    onInputChange,
    onBlur,
    id,
    placeholder,
  }: {
    value: string;
    onChange: (value: string) => void;
    onInputChange: (value: string) => void;
    onBlur?: () => void;
    id?: string;
    placeholder?: string;
  }) => (
    <input
      data-testid={`combobox-${id}`}
      value={value}
      onChange={(e) => {
        onChange(e.target.value);
        onInputChange(e.target.value);
      }}
      onBlur={onBlur}
      placeholder={placeholder}
    />
  ),
}));

describe("PromptsTab", () => {
  const mockPrompts: Prompt[] = [
    {
      name: "greeting",
      description: "Generates a greeting",
      arguments: [
        { name: "name", description: "Name to greet", required: true },
        { name: "suffix", description: "Optional suffix", required: false },
      ],
    },
    {
      name: "echo",
      description: "Echoes the input",
      arguments: [],
    },
  ];

  const defaultProps = {
    prompts: mockPrompts,
    listPrompts: jest.fn(),
    clearPrompts: jest.fn(),
    getPrompt: jest.fn(),
    selectedPrompt: null,
    setSelectedPrompt: jest.fn(),
    handleCompletion: jest.fn(async () => []),
    completionsSupported: true,
    promptContent: "",
    nextCursor: "",
    error: null,
  };

  const renderPromptsTab = (props = {}) => {
    return render(
      <Tabs defaultValue="prompts">
        <PromptsTab {...defaultProps} {...props} />
      </Tabs>,
    );
  };

  it("should disable Get Prompt on untouched form with required argument", () => {
    renderPromptsTab({ selectedPrompt: mockPrompts[0] });

    const getPromptButton = screen.getByRole("button", {
      name: /get prompt/i,
    });
    expect(getPromptButton).toBeDisabled();
  });

  it("should enable Get Prompt after filling required argument", async () => {
    const mockGetPrompt = jest.fn();
    renderPromptsTab({
      selectedPrompt: mockPrompts[0],
      getPrompt: mockGetPrompt,
    });

    const getPromptButton = screen.getByRole("button", {
      name: /get prompt/i,
    });
    expect(getPromptButton).toBeDisabled();

    // Fill the required argument via the mocked combobox input
    const nameInput = screen.getByTestId("combobox-name");
    await act(async () => {
      fireEvent.change(nameInput, { target: { value: "World" } });
    });

    expect(getPromptButton).not.toBeDisabled();

    await act(async () => {
      fireEvent.click(getPromptButton);
    });

    expect(mockGetPrompt).toHaveBeenCalledWith(
      mockPrompts[0].name,
      { name: "World" },
    );
  });

  it("should keep Get Prompt disabled when only optional argument is filled", async () => {
    renderPromptsTab({ selectedPrompt: mockPrompts[0] });

    const getPromptButton = screen.getByRole("button", {
      name: /get prompt/i,
    });
    expect(getPromptButton).toBeDisabled();

    // Fill only the optional argument (suffix)
    const suffixInput = screen.getByTestId("combobox-suffix");
    await act(async () => {
      fireEvent.change(suffixInput, { target: { value: "!!" } });
    });

    expect(getPromptButton).toBeDisabled();
  });

  it("should enable Get Prompt for prompts without required arguments", () => {
    const mockGetPrompt = jest.fn();
    renderPromptsTab({
      selectedPrompt: mockPrompts[1],
      getPrompt: mockGetPrompt,
    });

    const getPromptButton = screen.getByRole("button", {
      name: /get prompt/i,
    });
    expect(getPromptButton).not.toBeDisabled();
  });

  it("should disable Get Prompt when required argument is cleared", async () => {
    renderPromptsTab({ selectedPrompt: mockPrompts[0] });

    const getPromptButton = screen.getByRole("button", {
      name: /get prompt/i,
    });
    expect(getPromptButton).toBeDisabled();

    const nameInput = screen.getByTestId("combobox-name");
    await act(async () => {
      fireEvent.change(nameInput, { target: { value: "World" } });
    });

    expect(getPromptButton).not.toBeDisabled();

    // Clear the field
    await act(async () => {
      fireEvent.change(nameInput, { target: { value: "" } });
    });

    expect(getPromptButton).toBeDisabled();
  });

  it("should show required field error on blur when required argument is empty", async () => {
    renderPromptsTab({ selectedPrompt: mockPrompts[0] });

    // Focus on the required name field
    const nameInput = screen.getByTestId("combobox-name");
    await act(async () => {
      fireEvent.focus(nameInput);
    });

    // Blur without filling - should trigger validation error
    await act(async () => {
      fireEvent.blur(nameInput);
    });

    // Verify error message appears
    expect(screen.getByText("This field is required")).toBeInTheDocument();
  });

  it("should clear field error on blur when required argument is filled", async () => {
    renderPromptsTab({ selectedPrompt: mockPrompts[0] });

    const nameInput = screen.getByTestId("combobox-name");

    // Blur empty first to trigger error
    await act(async () => {
      fireEvent.blur(nameInput);
    });

    // Verify error is shown
    expect(screen.getByText("This field is required")).toBeInTheDocument();

    // Fill the field
    await act(async () => {
      fireEvent.change(nameInput, { target: { value: "World" } });
    });

    // Blur again - error should clear since field is now filled
    await act(async () => {
      fireEvent.blur(nameInput);
    });

    // Verify error message is gone
    expect(
      screen.queryByText("This field is required"),
    ).not.toBeInTheDocument();
  });
});