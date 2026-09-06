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
    id,
    placeholder,
  }: {
    value: string;
    onChange: (value: string) => void;
    onInputChange: (value: string) => void;
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
});