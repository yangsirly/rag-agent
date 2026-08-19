import { describe, expect, it } from "vitest";
import { loginApi, meApi, registerApi } from "@/features/auth/api";
import {
  createConversation,
  listConversations,
  sendMessage,
  listMessages,
  deleteConversation,
} from "@/features/chat/api";
import {
  createKnowledgeBase,
  listKnowledgeBases,
  createDocument,
  listDocuments,
} from "@/features/knowledge-base/api";
import { AppApiError } from "@/shared/api/errors";

async function loginAs(email: string, password = "password1") {
  await loginApi(email, password);
}

describe("MSW contract smoke", () => {
  it("register -> login -> me", async () => {
    const email = `u${Date.now()}@example.com`;
    await registerApi(email, "password1");
    const login = await loginApi(email, "password1");
    expect(login.role).toBe("CUSTOMER");
    const me = await meApi();
    expect(me.email).toBe(email);
  });

  it("message idempotency reuses same pair", async () => {
    await loginAs("customer@example.com");
    const conv = await createConversation("测试");
    const clientMessageId = "018f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21";
    const first = await sendMessage(conv.id, clientMessageId, "你好");
    const second = await sendMessage(conv.id, clientMessageId, "你好");
    expect(second.statusCode).toBe(200);
    expect(second.userMessage.id).toBe(first.userMessage.id);
    expect(second.assistantMessage.id).toBe(first.assistantMessage.id);
  });

  it("idempotency conflict on different content", async () => {
    await loginAs("customer@example.com");
    const conv = await createConversation();
    const clientMessageId = "118f6f5a-7d5b-7c3a-a08f-5cf5b26a7a21";
    await sendMessage(conv.id, clientMessageId, "A");
    await expect(sendMessage(conv.id, clientMessageId, "B")).rejects.toMatchObject({
      code: "IDEMPOTENCY_CONFLICT",
    });
  });

  it("CUSTOMER cannot list knowledge bases", async () => {
    await loginAs("customer@example.com");
    await expect(listKnowledgeBases()).rejects.toBeInstanceOf(AppApiError);
    try {
      await listKnowledgeBases();
    } catch (e) {
      expect((e as AppApiError).statusCode).toBe(403);
    }
  });

  it("EDITOR can manage kb and documents", async () => {
    await loginAs("editor@example.com");
    const list = await listKnowledgeBases();
    expect(list.items.length).toBeGreaterThan(0);
    const kb = await createKnowledgeBase(`库-${Date.now()}`);
    const doc = await createDocument(kb.id, {
      title: "文档",
      content: "正文内容",
      summary: "摘要",
    });
    expect(doc.content).toContain("正文");
    const docs = await listDocuments(kb.id);
    expect(docs.items.some((d) => d.id === doc.id)).toBe(true);
  });

  it("delete conversation returns 404 on second delete", async () => {
    await loginAs("customer@example.com");
    const conv = await createConversation("to-delete");
    await deleteConversation(conv.id);
    await expect(deleteConversation(conv.id)).rejects.toMatchObject({ code: "NOT_FOUND" });
  });

  it("lists conversations for current user", async () => {
    await loginAs("customer@example.com");
    await createConversation("A");
    const list = await listConversations();
    expect(list.items.length).toBeGreaterThan(0);
  });

  it("lists messages newest page first semantics", async () => {
    await loginAs("customer@example.com");
    const conv = await createConversation();
    const id1 = crypto.randomUUID();
    const id2 = crypto.randomUUID();
    await sendMessage(conv.id, id1, "first");
    await sendMessage(conv.id, id2, "second");
    const page = await listMessages(conv.id, 0, 50);
    expect(page.items.map((m) => m.content)).toContain("first");
    expect(page.items.map((m) => m.content)).toContain("second");
  });
});
