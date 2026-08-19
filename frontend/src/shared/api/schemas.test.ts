import { describe, expect, it } from "vitest";
import {
  LoginResponseSchema,
  MeResponseSchema,
  MessageListSchema,
  SendMessageResponseSchema,
} from "./schemas";

describe("zod contracts", () => {
  it("parses login response", () => {
    const r = LoginResponseSchema.parse({ statusCode: 200, role: "CUSTOMER" });
    expect(r.role).toBe("CUSTOMER");
  });

  it("requires string ids in /me", () => {
    expect(() =>
      MeResponseSchema.parse({
        statusCode: 200,
        userId: 1,
        email: "a@b.com",
        role: "CUSTOMER",
      }),
    ).toThrow();
    const ok = MeResponseSchema.parse({
      statusCode: 200,
      userId: "1",
      email: "a@b.com",
      role: "EDITOR",
    });
    expect(ok.userId).toBe("1");
  });

  it("parses send message pair", () => {
    const data = SendMessageResponseSchema.parse({
      statusCode: 201,
      userMessage: {
        id: "1",
        conversationId: "10",
        clientMessageId: "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21",
        role: "USER",
        content: "hi",
        createdAt: "2026-07-17T08:05:00Z",
      },
      assistantMessage: {
        id: "2",
        conversationId: "10",
        replyToMessageId: "1",
        role: "ASSISTANT",
        content: "reply",
        createdAt: "2026-07-17T08:05:00.010Z",
      },
    });
    expect(data.userMessage.role).toBe("USER");
  });

  it("parses message list page shape", () => {
    const page = MessageListSchema.parse({
      statusCode: 200,
      items: [],
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });
    expect(page.items).toEqual([]);
  });
});
