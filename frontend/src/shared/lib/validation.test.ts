import { describe, expect, it } from "vitest";
import {
  emailField,
  messageContentField,
  normalizeEmail,
  passwordField,
  conversationTitleField,
  kbNameField,
  docTitleField,
} from "./validation";

describe("validation fields", () => {
  it("normalizes email", () => {
    expect(normalizeEmail("  Foo@Example.COM ")).toBe("foo@example.com");
  });

  it("accepts valid password by unicode length", () => {
    expect(passwordField.parse("password1")).toBe("password1");
    expect(() => passwordField.parse("short")).toThrow();
  });

  it("parses email field", () => {
    expect(emailField.parse("  a@b.com ")).toBe("a@b.com");
    expect(() => emailField.parse("bad")).toThrow();
  });

  it("validates conversation title trim", () => {
    expect(conversationTitleField.parse("  title  ")).toBe("title");
    expect(() => conversationTitleField.parse("   ")).toThrow();
  });

  it("rejects blank message content", () => {
    expect(() => messageContentField.parse("   ")).toThrow();
    expect(messageContentField.parse("hello")).toBe("hello");
  });

  it("validates kb and doc titles", () => {
    expect(kbNameField.parse(" product ")).toBe("product");
    expect(docTitleField.parse(" doc ")).toBe("doc");
  });
});
