import { describe, expect, it } from "vitest";
import { mergeMessagePages, sortMessages } from "./api";
import type { Message } from "@/shared/api/schemas";

const m = (id: string, createdAt: string): Message => ({
  id,
  conversationId: "1",
  role: "USER",
  content: id,
  createdAt,
});

describe("message pagination helpers", () => {
  it("sorts by createdAt then id", () => {
    const sorted = sortMessages([
      m("3", "2026-01-01T00:00:02Z"),
      m("2", "2026-01-01T00:00:01Z"),
      m("10", "2026-01-01T00:00:01Z"),
    ]);
    expect(sorted.map((x) => x.id)).toEqual(["2", "10", "3"]);
  });

  it("merges pages and deduplicates by id", () => {
    const merged = mergeMessagePages([
      [m("1", "2026-01-01T00:00:01Z"), m("2", "2026-01-01T00:00:02Z")],
      [m("2", "2026-01-01T00:00:02Z"), m("3", "2026-01-01T00:00:03Z")],
    ]);
    expect(merged.map((x) => x.id)).toEqual(["1", "2", "3"]);
  });
});
