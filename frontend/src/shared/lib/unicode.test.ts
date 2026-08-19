import { describe, expect, it } from "vitest";
import { isWithinUnicodeLength, unicodeLength } from "./unicode";

describe("unicodeLength", () => {
  it("counts BMP characters as 1", () => {
    expect(unicodeLength("password1")).toBe(9);
  });

  it("counts emoji as single code points", () => {
    expect(unicodeLength("👍👍")).toBe(2);
    expect("👍👍".length).toBe(4);
  });

  it("validates password range by code points", () => {
    expect(isWithinUnicodeLength("1234567", 8, 64)).toBe(false);
    expect(isWithinUnicodeLength("12345678", 8, 64)).toBe(true);
  });
});
