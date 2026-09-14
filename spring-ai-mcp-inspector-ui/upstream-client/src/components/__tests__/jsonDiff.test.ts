// [spring-ai-mcp-inspector PATCH] jsonDiff test: structural JSON diff utility.
import { diffJson, formatDiffValue } from "@/utils/jsonDiff";

describe("diffJson", () => {
  it("returns empty array for identical values", () => {
    expect(diffJson({ a: 1 }, { a: 1 })).toEqual([]);
    expect(diffJson("same", "same")).toEqual([]);
    expect(diffJson(null, null)).toEqual([]);
    expect(diffJson([1, 2], [1, 2])).toEqual([]);
  });

  it("detects primitive changes at root", () => {
    expect(diffJson(1, 2)).toEqual([
      { path: "(root)", type: "changed", oldValue: 1, newValue: 2 },
    ]);
  });

  it("detects added keys", () => {
    expect(diffJson({ a: 1 }, { a: 1, b: 2 })).toEqual([
      { path: "b", type: "added", newValue: 2 },
    ]);
  });

  it("detects removed keys", () => {
    expect(diffJson({ a: 1, b: 2 }, { a: 1 })).toEqual([
      { path: "b", type: "removed", oldValue: 2 },
    ]);
  });

  it("detects changed values", () => {
    expect(diffJson({ a: 1 }, { a: 2 })).toEqual([
      { path: "a", type: "changed", oldValue: 1, newValue: 2 },
    ]);
  });

  it("recurses into nested objects", () => {
    expect(diffJson({ a: { b: 1 } }, { a: { b: 2 } })).toEqual([
      { path: "a.b", type: "changed", oldValue: 1, newValue: 2 },
    ]);
  });

  it("compares arrays element-wise", () => {
    expect(diffJson([1, 2], [1, 3])).toEqual([
      { path: "[1]", type: "changed", oldValue: 2, newValue: 3 },
    ]);
  });

  it("detects added array elements", () => {
    expect(diffJson([1], [1, 2])).toEqual([
      { path: "[1]", type: "added", newValue: 2 },
    ]);
  });

  it("detects removed array elements", () => {
    expect(diffJson([1, 2], [1])).toEqual([
      { path: "[1]", type: "removed", oldValue: 2 },
    ]);
  });

  it("handles type changes", () => {
    expect(diffJson({ a: 1 }, { a: "string" })).toEqual([
      { path: "a", type: "changed", oldValue: 1, newValue: "string" },
    ]);
    expect(diffJson({ a: 1 }, { a: [1] })).toEqual([
      { path: "a", type: "changed", oldValue: 1, newValue: [1] },
    ]);
  });

  it("handles null vs object", () => {
    expect(diffJson({ a: null }, { a: { b: 1 } })).toEqual([
      { path: "a", type: "changed", oldValue: null, newValue: { b: 1 } },
    ]);
  });

  it("handles deeply nested mixed changes", () => {
    const oldVal = {
      content: [{ type: "text", text: "old" }],
      isError: true,
    };
    const newVal = {
      content: [{ type: "text", text: "new" }],
      isError: false,
      extra: "field",
    };
    const entries = diffJson(oldVal, newVal);
    expect(entries).toContainEqual({
      path: "content[0].text",
      type: "changed",
      oldValue: "old",
      newValue: "new",
    });
    expect(entries).toContainEqual({
      path: "isError",
      type: "changed",
      oldValue: true,
      newValue: false,
    });
    expect(entries).toContainEqual({
      path: "extra",
      type: "added",
      newValue: "field",
    });
  });
});

describe("formatDiffValue", () => {
  it("formats strings with quotes", () => {
    expect(formatDiffValue("hello")).toBe('"hello"');
  });
  it("formats null as null", () => {
    expect(formatDiffValue(null)).toBe("null");
  });
  it("formats undefined as undefined", () => {
    expect(formatDiffValue(undefined)).toBe("undefined");
  });
  it("formats numbers as-is", () => {
    expect(formatDiffValue(42)).toBe("42");
  });
  it("formats objects as compact JSON", () => {
    expect(formatDiffValue({ a: 1 })).toBe('{"a":1}');
  });
});
