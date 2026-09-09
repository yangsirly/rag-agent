import { expect, it } from "vitest";
import { parseApiMode } from "./env";
it("accepts only explicit modes outside tests", () => {
  expect(parseApiMode("real", "production")).toBe("real");
  expect(parseApiMode("mock", "development")).toBe("mock");
  expect(parseApiMode(undefined, "test")).toBe("mock");
  for (const value of [undefined, "", "REAL", "mokc"]) {
    expect(() => parseApiMode(value, "production")).toThrow("VITE_API_MODE");
  }
});
