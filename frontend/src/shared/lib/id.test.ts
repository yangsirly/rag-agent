import { describe, expect, it } from "vitest";
import { createClientMessageId, createClientRequestId } from "./id";

describe("id helpers", () => {
  it("creates uuid-like client message ids", () => {
    const a = createClientMessageId();
    const b = createClientMessageId();
    expect(a).not.toBe(b);
    expect(a).toMatch(/^[0-9a-f-]{36}$/i);
  });

  it("creates request ids", () => {
    expect(createClientRequestId()).toMatch(/^[0-9a-f-]{36}$/i);
  });
});
