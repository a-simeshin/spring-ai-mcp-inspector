// [spring-ai-mcp-inspector PATCH] Common Spring AI 2.0 pitfalls hint panel.
// Reachable from Timeline and tool-detail views. Lists three known silent
// failure modes for Spring AI 2.0 with links to upstream references.
import { useState } from "react";
import { ChevronDown, ChevronRight, ExternalLink } from "lucide-react";
import { cn } from "@/lib/utils";

interface PitfallEntry {
  symptom: string;
  link: string;
  linkText: string;
  detectorNote?: string;
  detectorActive?: boolean;
}

interface SpringAiPitfallsPanelProps {
  hasUnresolvedRefWarning?: boolean;
  hasIsErrorWarning?: boolean;
  className?: string;
}

const SPRING_AI_VALIDATION_MEDIUM_URL =
  "https://medium.com/javarevisited/spring-ai-2-tool-calling-not-working-9f06e76b1a32";
const SPRING_AI_5888_URL =
  "https://github.com/spring-projects/spring-ai/issues/5888";
const SPRING_AI_6773_URL =
  "https://github.com/spring-projects/spring-ai/issues/6773";

// [spring-ai-mcp-inspector PATCH] Common Spring AI 2.0 pitfalls hint panel.
// Collapsible panel with three entries, each linking to the upstream reference.
// When a live detector warning exists in the UI, the entry points to it.
export function SpringAiPitfallsPanel({
  hasUnresolvedRefWarning = false,
  hasIsErrorWarning = false,
  className,
}: SpringAiPitfallsPanelProps) {
  const [expanded, setExpanded] = useState(false);

  const entries: PitfallEntry[] = [
    {
      symptom:
        "Server-side validation is on by default in 2.0; failures return silent isError=true results with no exception.",
      link: SPRING_AI_VALIDATION_MEDIUM_URL,
      linkText: "Medium: production tools failing with zero exceptions",
      detectorNote: hasIsErrorWarning
        ? "Detector active: check Timeline for isError=true rows"
        : undefined,
      detectorActive: hasIsErrorWarning,
    },
    {
      symptom:
        "Recursive parameter types emit unresolvable $ref pointers that break tool schemas.",
      link: SPRING_AI_5888_URL,
      linkText: "spring-projects/spring-ai#5888",
      detectorNote: hasUnresolvedRefWarning
        ? "Detector active: see Schema Warning above"
        : undefined,
      detectorActive: hasUnresolvedRefWarning,
    },
    {
      symptom:
        "Schema generation silently skips primitive/simple return types, leaving tools without an output schema.",
      link: SPRING_AI_6773_URL,
      linkText: "spring-projects/spring-ai#6773",
      // No live detector reference available for this entry yet.
    },
  ];

  return (
    <div
      className={cn(
        "border border-amber-700/50 rounded bg-amber-950/20",
        className,
      )}
      data-testid="spring-ai-pitfalls-panel"
    >
      <button
        type="button"
        className="flex items-center gap-1 w-full text-left px-2 py-1.5 text-xs font-semibold text-amber-200 hover:bg-amber-950/30 rounded"
        onClick={() => setExpanded(!expanded)}
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDown className="w-3.5 h-3.5 shrink-0" />
        ) : (
          <ChevronRight className="w-3.5 h-3.5 shrink-0" />
        )}
        Common Spring AI 2.0 pitfalls
      </button>
      {expanded && (
        <ul className="px-2 pb-2 pt-0.5 space-y-1.5 text-[11px] text-amber-100/90">
          {entries.map((entry) => (
            <li key={entry.link} className="flex flex-col gap-0.5">
              <span>{entry.symptom}</span>
              <a
                href={entry.link}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-0.5 text-blue-400 hover:text-blue-300 underline"
                onClick={(e) => e.stopPropagation()}
              >
                {entry.linkText}
                <ExternalLink className="w-3 h-3" />
              </a>
              {entry.detectorNote && (
                <span
                  className={cn(
                    "text-[10px] italic",
                    entry.detectorActive
                      ? "text-amber-300"
                      : "text-amber-100/50",
                  )}
                >
                  {entry.detectorNote}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
