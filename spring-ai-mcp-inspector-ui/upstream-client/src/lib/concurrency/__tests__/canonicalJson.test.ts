// [spring-ai-mcp-inspector PATCH] canonical JSON + hash tests (issue #237)
import { computeBodyHash, canonicalSerialize } from "../canonicalJson";

// In jest-fixed-jsdom, crypto.subtle is not available; use node:crypto webcrypto
import { webcrypto } from "node:crypto";
Object.defineProperty(globalThis, "crypto", { value: webcrypto });

describe("canonicalSerialize", () => {
  it("sorts object keys recursively", () => {
    const obj = { z: 1, a: { y: 2, b: 3 }, m: 4 };
    const result = canonicalSerialize(obj);
    const parsed = JSON.parse(result);
    expect(Object.keys(parsed)).toEqual(["a", "m", "z"]);
    expect(Object.keys(parsed.a)).toEqual(["b", "y"]);
  });

  it("serializes arrays in order", () => {
    expect(canonicalSerialize([3, 1, 2])).toBe("[3,1,2]");
  });

  it("serializes strings with JSON.stringify", () => {
    expect(canonicalSerialize('say "hi"')).toBe('"say \\"hi\\""');
  });

  it("serializes numbers per JSON rules", () => {
    expect(canonicalSerialize(42)).toBe("42");
    expect(canonicalSerialize(3.14)).toBe("3.14");
    expect(canonicalSerialize(0)).toBe("0");
    expect(canonicalSerialize(-1)).toBe("-1");
  });

  it("serializes booleans and null", () => {
    expect(canonicalSerialize(true)).toBe("true");
    expect(canonicalSerialize(false)).toBe("false");
    expect(canonicalSerialize(null)).toBe("null");
  });

  it("serializes undefined as null", () => {
    expect(canonicalSerialize(undefined)).toBe("null");
  });

  it("serializes nested structures", () => {
    const obj = { b: [2, { c: 3, a: 1 }], a: "x" };
    expect(canonicalSerialize(obj)).toBe('{"a":"x","b":[2,{"a":1,"c":3}]}');
  });

  it("handles empty object and array", () => {
    expect(canonicalSerialize({})).toBe("{}");
    expect(canonicalSerialize([])).toBe("[]");
  });
});

describe("computeBodyHash", () => {
  it("returns a 64-char hex string", async () => {
    const { hash, truncated } = await computeBodyHash({ foo: "bar" });
    expect(hash).toMatch(/^[0-9a-f]{64}$/);
    expect(truncated).toBe(false);
  });

  it("same input produces same hash", async () => {
    const a = await computeBodyHash({ x: 1, y: [2, 3] });
    const b = await computeBodyHash({ x: 1, y: [2, 3] });
    expect(a.hash).toBe(b.hash);
    expect(a.truncated).toBe(b.truncated);
  });

  it("different key order produces same hash (canonical)", async () => {
    const a = await computeBodyHash({ a: 1, b: 2 });
    const b = await computeBodyHash({ b: 2, a: 1 });
    expect(a.hash).toBe(b.hash);
  });

  it("different values produce different hashes", async () => {
    const a = await computeBodyHash({ x: 1 });
    const b = await computeBodyHash({ x: 2 });
    expect(a.hash).not.toBe(b.hash);
  });

  it("hash differs from JSON.stringify hash", async () => {
    const { createHash } = await import("node:crypto");
    const obj = { b: 2, a: 1 };
    const naive = createHash("sha256").update(JSON.stringify(obj)).digest("hex");
    const { hash } = await computeBodyHash(obj);
    // canonical sorts keys, so a≠b JSON.stringify order differs from a,b canonical order
    expect(hash).not.toBe(naive);
  });
});
