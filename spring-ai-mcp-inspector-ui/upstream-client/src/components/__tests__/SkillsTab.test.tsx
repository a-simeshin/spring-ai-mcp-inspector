import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import { Tabs } from "@/components/ui/tabs";
import SkillsTab from "../SkillsTab";
import type { SkillEntry, DirectoryResource, ReadContent } from "../SkillsTab";

const MOCK_SKILLS: SkillEntry[] = [
  {
    uri: "skill://say-hello/SKILL.md",
    frontmatter: {
      name: "say-hello",
      description: "Greet a user and introduce yourself as an MCP skill server",
      license: "MIT",
    },
    resources: [
      { uri: "skill://say-hello/SKILL.md", digest: "sha256:abc123", size: 580 },
      {
        uri: "skill://say-hello/scripts/greet.sh",
        digest: "sha256:def456",
        size: 120,
      },
    ],
  },
  {
    uri: "skill://data-analysis/SKILL.md",
    frontmatter: {
      name: "data-analysis",
      description: "Analyze data using statistical methods",
    },
    resources: [
      { uri: "skill://data-analysis/SKILL.md", digest: "sha256:789ghi", size: 988 },
    ],
  },
];

const MOCK_DIRECTORY: DirectoryResource[] = [
  { uri: "skill://say-hello/SKILL.md", name: "SKILL.md", mimeType: "text/markdown" },
  { uri: "skill://say-hello/scripts/greet.sh", name: "greet.sh", mimeType: "text/x-shellscript" },
];

const renderSkillsTab = (overrides: Record<string, unknown> = {}) => {
  const defaultProps = {
    listSkills: jest.fn<() => Promise<SkillEntry[]>>().mockRejectedValue(new Error("not called")),
    getSkill: jest.fn<(uri: string) => Promise<SkillEntry>>().mockRejectedValue(new Error("not called")),
    readDirectory: jest.fn<(uri: string) => Promise<DirectoryResource[]>>().mockRejectedValue(new Error("not called")),
    readResource: jest.fn<(uri: string) => Promise<ReadContent>>().mockRejectedValue(new Error("not called")),
    error: null,
    clearError: jest.fn<() => void>(),
  };

  const props = { ...defaultProps, ...overrides };

  return render(
    <Tabs defaultValue="skills">
      <SkillsTab
        listSkills={props.listSkills}
        getSkill={props.getSkill}
        readDirectory={props.readDirectory}
        readResource={props.readResource}
        error={props.error as string | null}
        clearError={props.clearError}
      />
    </Tabs>,
  );
};

describe("SkillsTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should render the no skill selected state by default", () => {
    renderSkillsTab();
    expect(screen.getByText("No Skill Selected")).toBeInTheDocument();
    expect(screen.getByText("List Skills")).toBeInTheDocument();
  });

  it("should list skills when List Skills is clicked", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);

    renderSkillsTab({ listSkills });

    expect(screen.getByText("No Skill Selected")).toBeInTheDocument();

    fireEvent.click(screen.getByText("List Skills"));

    await waitFor(() => {
      expect(screen.getByText("say-hello")).toBeInTheDocument();
      expect(screen.getByText("data-analysis")).toBeInTheDocument();
    });
  });

  it("should show error when listSkills fails", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockRejectedValue(
      new Error("Failed to fetch skills"),
    );

    renderSkillsTab({ listSkills });

    fireEvent.click(screen.getByText("List Skills"));

    await waitFor(() => {
      expect(screen.getByText("Failed to fetch skills")).toBeInTheDocument();
    });
  });

  it("should show frontmatter detail when a skill is selected", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);
    const getSkill = jest.fn<(uri: string) => Promise<SkillEntry>>().mockResolvedValue(MOCK_SKILLS[0]);
    const readDirectory = jest.fn<(uri: string) => Promise<DirectoryResource[]>>().mockResolvedValue(MOCK_DIRECTORY);

    renderSkillsTab({ listSkills, getSkill, readDirectory });

    fireEvent.click(screen.getByText("List Skills"));
    await waitFor(() => expect(screen.getByText("say-hello")).toBeInTheDocument());

    fireEvent.click(screen.getByText("say-hello"));

    await waitFor(() => {
      expect(screen.getByText("Frontmatter")).toBeInTheDocument();
      expect(screen.getByText("Supporting Files (2)")).toBeInTheDocument();
      expect(screen.getByText("Digest Verification")).toBeInTheDocument();
    });
  });

  it("should show refresh button after skills are listed", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);

    renderSkillsTab({ listSkills });

    fireEvent.click(screen.getByText("List Skills"));

    await waitFor(() => {
      expect(screen.getByText("Refresh")).toBeInTheDocument();
    });
  });

  it("should render read buttons for supporting files", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);
    const getSkill = jest.fn<(uri: string) => Promise<SkillEntry>>().mockResolvedValue(MOCK_SKILLS[0]);
    const readDirectory = jest.fn<(uri: string) => Promise<DirectoryResource[]>>().mockResolvedValue(MOCK_DIRECTORY);

    renderSkillsTab({ listSkills, getSkill, readDirectory });

    fireEvent.click(screen.getByText("List Skills"));
    await waitFor(() => expect(screen.getByText("say-hello")).toBeInTheDocument());

    fireEvent.click(screen.getByText("say-hello"));

    await waitFor(() => {
      const readButtons = screen.getAllByText("Read");
      expect(readButtons.length).toBeGreaterThanOrEqual(2);
    });
  });

  it("should render error alert for external errors", () => {
    renderSkillsTab({ error: "External error occurred" });
    expect(screen.getByText("External error occurred")).toBeInTheDocument();
  });

  it("should show skill description in list items", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);

    renderSkillsTab({ listSkills });

    fireEvent.click(screen.getByText("List Skills"));

    await waitFor(() => {
      expect(
        screen.getByText("Greet a user and introduce yourself as an MCP skill server"),
      ).toBeInTheDocument();
    });
  });

  it("should show digest verification section with expected digests", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);
    const getSkill = jest.fn<(uri: string) => Promise<SkillEntry>>().mockResolvedValue(MOCK_SKILLS[0]);
    const readDirectory = jest.fn<(uri: string) => Promise<DirectoryResource[]>>().mockResolvedValue([]);

    renderSkillsTab({ listSkills, getSkill, readDirectory });

    fireEvent.click(screen.getByText("List Skills"));
    await waitFor(() => expect(screen.getByText("say-hello")).toBeInTheDocument());

    fireEvent.click(screen.getByText("say-hello"));

    await waitFor(() => {
      expect(screen.getByText("Digest Verification")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Digest Verification"));

    await waitFor(() => {
      expect(
        screen.getByText("Expected digests (SHA-256, as advertised by server):"),
      ).toBeInTheDocument();
    });
  });

  it("should clear previous state when listing skills again", async () => {
    const listSkills = jest.fn<() => Promise<SkillEntry[]>>().mockResolvedValue(MOCK_SKILLS);

    renderSkillsTab({ listSkills });

    fireEvent.click(screen.getByText("List Skills"));
    await waitFor(() => expect(screen.getByText("say-hello")).toBeInTheDocument());

    // Click again to refresh
    fireEvent.click(screen.getByText("Refresh"));

    await waitFor(() => {
      expect(screen.getByText("say-hello")).toBeInTheDocument();
    });
  });
});