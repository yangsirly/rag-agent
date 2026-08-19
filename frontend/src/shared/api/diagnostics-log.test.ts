import { describe, expect, it } from "vitest";
import { sanitizeForDiagnostics } from "./diagnostics-log";

describe("sanitizeForDiagnostics", () => {
  it("redacts password and token fields", () => {
    const text = sanitizeForDiagnostics({
      email: "a@b.com",
      password: "secret",
      access_token: "tok",
      nested: { authorization: "Bearer x" },
    });
    expect(text).toContain("a@b.com");
    expect(text).toContain("***");
    expect(text).not.toContain("secret");
    expect(text).not.toContain("Bearer x");
  });
});
