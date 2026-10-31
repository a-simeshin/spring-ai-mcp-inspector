/* [spring-ai-mcp-inspector PATCH] Unit tests for the client-side protocol
   revision matrix. Mirrors the cases of the server-side
   ProtocolRevisionTests so the badge and the timeline enrichment cannot
   drift apart silently. */
import { checkProtocolRevision } from "../protocolRevision";

const REV_2025_11_25 = "2025-11-25";
const REV_2026_07_28 = "2026-07-28";

const ALL_REMOVED_2026_07_28 = [
  "initialize",
  "logging/setLevel",
  "notifications/elicitation/complete",
  "notifications/initialized",
  "notifications/roots/list_changed",
  "ping",
  "resources/subscribe",
  "resources/unsubscribe",
  "tasks/list",
  "tasks/result",
];

describe("checkProtocolRevision", () => {
  describe("downgrade: client requests newer revision than server negotiated", () => {
    it("reports DOWNGRADE with no affected methods", () => {
      const result = checkProtocolRevision(REV_2026_07_28, REV_2025_11_25);

      expect(result.severity).toBe("DOWNGRADE");
      expect(result.affectedMethods).toEqual([]);
      expect(result.summary).toContain("2026-07-28");
      expect(result.summary).toContain("2025-11-25");
      expect(result.summary).toContain("unavailable");
    });
  });

  describe("incompatible: client requests older revision than server negotiated", () => {
    it("reports INCOMPATIBLE with all methods removed by newer revisions", () => {
      const result = checkProtocolRevision(REV_2025_11_25, REV_2026_07_28);

      expect(result.severity).toBe("INCOMPATIBLE");
      expect(result.affectedMethods).toEqual(
        expect.arrayContaining(ALL_REMOVED_2026_07_28),
      );
      expect(result.affectedMethods).toHaveLength(
        ALL_REMOVED_2026_07_28.length,
      );
      expect(result.summary).toContain("2025-11-25");
      expect(result.summary).toContain("2026-07-28");
      expect(result.summary).toContain("MethodNotFound");
    });
  });

  describe("same revision on both sides", () => {
    it("reports OK for every known revision", () => {
      for (const revision of [REV_2025_11_25, REV_2026_07_28]) {
        const result = checkProtocolRevision(revision, revision);

        expect(result.severity).toBe("OK");
        expect(result.affectedMethods).toEqual([]);
        expect(result.summary).toContain(revision);
      }
    });
  });

  describe("unknown revisions", () => {
    it("reports UNKNOWN when only the requested revision is unrecognised", () => {
      const result = checkProtocolRevision("2099-01-01", REV_2025_11_25);

      expect(result.severity).toBe("UNKNOWN");
      expect(result.affectedMethods).toEqual([]);
      expect(result.summary).toContain("2099-01-01");
    });

    it("reports UNKNOWN when only the negotiated revision is unrecognised", () => {
      const result = checkProtocolRevision(REV_2025_11_25, "not-a-date");

      expect(result.severity).toBe("UNKNOWN");
      expect(result.summary).toContain("not-a-date");
    });

    it("reports UNKNOWN mentioning both revisions when neither is recognised", () => {
      const result = checkProtocolRevision("2024-01-01", "2023-06-01");

      expect(result.severity).toBe("UNKNOWN");
      expect(result.summary).toContain("2024-01-01");
      expect(result.summary).toContain("2023-06-01");
    });
  });

  describe("null revisions", () => {
    it("reports UNKNOWN without throwing", () => {
      expect(checkProtocolRevision(null, REV_2025_11_25).severity).toBe(
        "UNKNOWN",
      );
      expect(checkProtocolRevision(REV_2025_11_25, null).severity).toBe(
        "UNKNOWN",
      );
      expect(checkProtocolRevision(null, null).severity).toBe("UNKNOWN");
    });
  });

  describe("affected methods ordering", () => {
    it("returns the affected methods sorted", () => {
      const result = checkProtocolRevision(REV_2025_11_25, REV_2026_07_28);

      const sorted = [...result.affectedMethods].sort();
      expect(result.affectedMethods).toEqual(sorted);
    });
  });
});
