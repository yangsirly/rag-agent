import { http, HttpResponse } from "msw";
import {
  getStore,
  nextId,
  now,
  pageSlice,
  TEMPLATE_REPLY,
  type MockMessage,
} from "@/mocks/data/store";
import { unicodeLength } from "@/shared/lib/unicode";
import { applyFault, err, json, parsePage, requireUser, UUID_RE } from "./utils";

export const conversationHandlers = [
  http.get("/api/conversations", async ({ request, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const user = auth.user!;
    const page = parsePage(new URL(request.url), 20);
    if ("error" in page && page.error) return page.error;
    const items = getStore()
      .conversations.filter((c) => c.userId === user.id)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || Number(b.id) - Number(a.id));
    return json({ statusCode: 200, ...pageSlice(items, page.page!, page.size!) });
  }),

  http.post("/api/conversations", async ({ request, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const user = auth.user!;
    const body = (await request.json().catch(() => ({}))) as { title?: string };
    const title = body.title?.trim() || "新会话";
    if (unicodeLength(title) < 1 || unicodeLength(title) > 100) {
      return err(400, "INVALID_CONVERSATION_REQUEST", "标题非法");
    }
    const ts = now();
    const conv = { id: nextId(), userId: user.id, title, createdAt: ts, updatedAt: ts };
    getStore().conversations.unshift(conv);
    return json({ statusCode: 201, ...conv }, { status: 201 });
  }),

  http.get("/api/conversations/:id", async ({ request, params, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const conv = getStore().conversations.find(
      (c) => c.id === params.id && c.userId === auth.user!.id,
    );
    if (!conv) return err(404, "NOT_FOUND", "会话不存在");
    return json({ statusCode: 200, ...conv });
  }),

  http.patch("/api/conversations/:id", async ({ request, params, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const conv = getStore().conversations.find(
      (c) => c.id === params.id && c.userId === auth.user!.id,
    );
    if (!conv) return err(404, "NOT_FOUND", "会话不存在");
    const body = (await request.json()) as { title?: string };
    const title = body.title?.trim() ?? "";
    if (!title || unicodeLength(title) > 100) {
      return err(400, "INVALID_CONVERSATION_REQUEST", "标题非法");
    }
    conv.title = title;
    conv.updatedAt = now();
    return json({ statusCode: 200, ...conv });
  }),

  http.delete("/api/conversations/:id", async ({ request, params, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const store = getStore();
    const idx = store.conversations.findIndex(
      (c) => c.id === params.id && c.userId === auth.user!.id,
    );
    if (idx < 0) return err(404, "NOT_FOUND", "会话不存在");
    const id = store.conversations[idx].id;
    store.conversations.splice(idx, 1);
    store.messages = store.messages.filter((m) => m.conversationId !== id);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get("/api/conversations/:id/messages", async ({ request, params, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const conv = getStore().conversations.find(
      (c) => c.id === params.id && c.userId === auth.user!.id,
    );
    if (!conv) return err(404, "NOT_FOUND", "会话不存在");
    const page = parsePage(new URL(request.url), 50);
    if ("error" in page && page.error) return page.error;
    const all = getStore()
      .messages.filter((m) => m.conversationId === conv.id)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || Number(a.id) - Number(b.id));
    const totalElements = all.length;
    const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / page.size!);
    const p = page.page!;
    const size = page.size!;
    const end = totalElements - p * size;
    const start = Math.max(0, end - size);
    const items = end <= 0 ? [] : all.slice(start, end);
    return json({ statusCode: 200, items, page: p, size, totalElements, totalPages });
  }),

  http.post("/api/conversations/:id/messages", async ({ request, params, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const store = getStore();
    const conv = store.conversations.find(
      (c) => c.id === params.id && c.userId === auth.user!.id,
    );
    if (!conv) return err(404, "NOT_FOUND", "会话不存在");
    const body = (await request.json()) as { clientMessageId?: string; content?: string };
    if (!body.clientMessageId || !UUID_RE.test(body.clientMessageId)) {
      return err(400, "INVALID_MESSAGE_REQUEST", "clientMessageId 非法");
    }
    if (!body.content || body.content.trim().length === 0 || unicodeLength(body.content) > 10000) {
      return err(400, "INVALID_MESSAGE_REQUEST", "消息内容非法");
    }

    const existing = store.messages.find(
      (m) =>
        m.conversationId === conv.id &&
        m.role === "USER" &&
        m.clientMessageId === body.clientMessageId,
    );
    if (existing) {
      if (existing.content !== body.content) {
        return err(409, "IDEMPOTENCY_CONFLICT", "相同 clientMessageId 内容冲突");
      }
      const assistant = store.messages.find(
        (m) => m.replyToMessageId === existing.id && m.role === "ASSISTANT",
      )!;
      return json({ statusCode: 200, userMessage: existing, assistantMessage: assistant });
    }

    const ts = now();
    const userMessage: MockMessage = {
      id: nextId(),
      conversationId: conv.id,
      clientMessageId: body.clientMessageId,
      role: "USER",
      content: body.content,
      createdAt: ts,
    };
    const assistantMessage: MockMessage = {
      id: nextId(),
      conversationId: conv.id,
      replyToMessageId: userMessage.id,
      role: "ASSISTANT",
      content: TEMPLATE_REPLY,
      createdAt: new Date(Date.parse(ts) + 10).toISOString(),
    };
    store.messages.push(userMessage, assistantMessage);
    conv.updatedAt = assistantMessage.createdAt;
    return json(
      { statusCode: 201, userMessage, assistantMessage },
      { status: 201 },
    );
  }),
];
