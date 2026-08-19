import { describe, expect, it } from "vitest";
import { isEditor } from "./auth-store";

describe("isEditor", () => {
  it("returns true only for EDITOR", () => {
    expect(isEditor("EDITOR")).toBe(true);
    expect(isEditor("CUSTOMER")).toBe(false);
    expect(isEditor(null)).toBe(false);
  });
});
