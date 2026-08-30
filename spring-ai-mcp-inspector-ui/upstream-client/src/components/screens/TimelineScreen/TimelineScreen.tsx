// [spring-ai-mcp-inspector PATCH] TimelineScreen — MCP event timeline tab (#112,
// re-ported onto the v2.3.0 Mantine architecture in #78/PR #113).
//
// Polls ${inspectorPath}/api/timeline and renders a chronological, color-coded
// log of MCP JSON-RPC traffic and application log events. Replaces the v0.22.0
// Radix/Tailwind TimelineTab that was wiped by the v2 re-vendor.

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Box,
  Card,
  Checkbox,
  Group,
  ScrollArea,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";

type TimelineEventType =
  | "MCP_JSONRPC_REQUEST"
  | "MCP_JSONRPC_RESPONSE"
  | "MCP_JSONRPC_NOTIFICATION"
  | "MCP_STREAM_EVENT"
  | "APP_LOG";

interface TimelineEvent {
  id: string;
  correlationId: string;
  timestamp: string;
  eventType: TimelineEventType;
  sessionId: string | null;
  requestId: string | null;
  method: string | null;
  params: unknown | null;
  result: unknown | null;
  error: unknown | null;
  logLevel: string | null;
  loggerName: string | null;
  threadName: string | null;
  message: string | null;
  throwable: string | null;
}

const EVENT_COLORS: Record<TimelineEventType, string> = {
  MCP_JSONRPC_REQUEST: "blue",
  MCP_JSONRPC_RESPONSE: "green",
  MCP_JSONRPC_NOTIFICATION: "yellow",
  MCP_STREAM_EVENT: "grape",
  APP_LOG: "gray",
};

function formatTimestamp(ts: string): string {
  const d = new Date(ts);
  return (
    d.toLocaleTimeString("en-US", { hour12: false }) +
    "." +
    String(d.getMilliseconds()).padStart(3, "0")
  );
}

/**
 * Resolve the bearer token the Spring starter's InspectorAuthFilter expects in
 * the `X-MCP-Inspector-Auth` header. The starter injects
 * `window.__MCP_INSPECTOR_BOOTSTRAP` (BootstrapHtmlRenderer) into index.html;
 * the value is a plain token (the filter compares it with no `Bearer ` prefix).
 */
function getInspectorApiToken(): string | undefined {
  const boot = (
    window as unknown as {
      __MCP_INSPECTOR_BOOTSTRAP?: { authToken?: string };
    }
  ).__MCP_INSPECTOR_BOOTSTRAP;
  return boot?.authToken || undefined;
}

function TimelineEventRow({ event }: { event: TimelineEvent }) {
  const [expanded, { toggle }] = useDisclosure(false);
  const typeLabel = event.eventType.replace("MCP_", "").replace("_", " ");
  const label = event.method || event.logLevel || typeLabel;
  const detail = event.message ?? event.throwable ?? null;

  return (
    <Card
      withBorder
      radius="sm"
      px="sm"
      py={6}
      style={{ cursor: "pointer" }}
      onClick={toggle}
    >
      <Group gap="xs" wrap="nowrap">
        <Text size="xs" c="dimmed" ff="mono" w={90} flex="0 0 auto">
          {formatTimestamp(event.timestamp)}
        </Text>
        <Badge
          size="sm"
          color={EVENT_COLORS[event.eventType] ?? "gray"}
          variant="light"
          ff="mono"
          flex="0 0 auto"
        >
          {typeLabel}
        </Badge>
        <Text size="sm" ff="mono" truncate>
          {label}
        </Text>
        {event.correlationId ? (
          <Text size="xs" c="dimmed" ff="mono" ml="auto" flex="0 0 auto">
            {event.correlationId.substring(0, 8)}
          </Text>
        ) : null}
      </Group>
      {expanded ? (
        <Box
          mt="xs"
          p="xs"
          ff="mono"
          fz={11}
          style={{
            whiteSpace: "pre-wrap",
            wordBreak: "break-all",
            maxHeight: 220,
            overflowY: "auto",
            background: "var(--mantine-color-body)",
          }}
        >
          {event.params != null ? (
            <Text span>
              params: {JSON.stringify(event.params, null, 2)}
              {"\n"}
            </Text>
          ) : null}
          {event.result != null ? (
            <Text span>
              result: {JSON.stringify(event.result, null, 2)}
              {"\n"}
            </Text>
          ) : null}
          {event.error != null ? (
            <Text span>
              error: {JSON.stringify(event.error, null, 2)}
              {"\n"}
            </Text>
          ) : null}
          {detail != null ? <Text span>message: {detail}</Text> : null}
        </Box>
      ) : null}
    </Card>
  );
}

export function TimelineScreen() {
  const [events, setEvents] = useState<TimelineEvent[]>([]);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchTimeline = useCallback(async () => {
    const headers: Record<string, string> = {};
    const token = getInspectorApiToken();
    if (token) {
      headers["X-MCP-Inspector-Auth"] = token;
    }
    try {
      const res = await fetch("api/timeline?limit=200", { headers });
      if (res.ok) {
        setEvents((await res.json()) as TimelineEvent[]);
      }
    } catch {
      // Server-side timeline disabled or unreachable — tab stays empty.
    }
  }, []);

  useEffect(() => {
    // A single initial fetch is intentional; the 3s interval below owns the
    // steady-state polling so the refresh cadence is one mechanism, not two.
    void fetchTimeline();
    if (!autoRefresh) return;
    pollingRef.current = setInterval(() => void fetchTimeline(), 3000);
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [autoRefresh, fetchTimeline]);

  return (
    <Stack gap="sm" h="100%">
      <Group justify="space-between">
        <Title order={5}>Timeline</Title>
        <Group gap="xs">
          <Text size="xs" c="dimmed">
            {events.length} event{events.length !== 1 ? "s" : ""}
          </Text>
          <Checkbox
            size="xs"
            label="Auto-refresh (3s)"
            checked={autoRefresh}
            onChange={(e) => setAutoRefresh(e.currentTarget.checked)}
          />
        </Group>
      </Group>
      <ScrollArea flex={1} withBorder radius="sm" p="xs" mah="calc(100dvh - 220px)">
        {events.length === 0 ? (
          <Text ta="center" c="dimmed" size="sm" py="xl">
            No timeline events yet
          </Text>
        ) : (
          <Stack gap={4}>
            {events.map((event) => (
              <TimelineEventRow key={event.id} event={event} />
            ))}
          </Stack>
        )}
      </ScrollArea>
    </Stack>
  );
}

export default TimelineScreen;
