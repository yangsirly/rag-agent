import { http, HttpResponse } from "msw";
import {
  findUserByEmail,
  getStore,
  nextId,
  now,
  pageSlice,
  type MockDocument,
} from "@/mocks/data/store";
import { appEnv } from "@/shared/lib/env";
import { unicodeLength } from "@/shared/lib/unicode";
import { applyFault, canSeeKb, err, json, parsePage, requireUser } from "./utils";

export const knowledgeBaseHandlers = [
  http.get("/api/knowledge-bases", async ({ request }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    const user = auth.user!;
    if (user.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    const page = parsePage(new URL(request.url), 20);
    if ("error" in page && page.error) return page.error;
    const store = getStore();
    let items = store.knowledgeBases.filter((k) => k.creatorId === user.id);
    if (appEnv.enableKbMembership) {
      const memberKbIds = new Set(
        store.members.filter((m) => m.userId === user.id).map((m) => m.knowledgeBaseId),
      );
      const map = new Map(items.map((k) => [k.id, k]));
      for (const k of store.knowledgeBases) {
        if (memberKbIds.has(k.id)) map.set(k.id, k);
      }
      items = [...map.values()];
    }
    items.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || Number(b.id) - Number(a.id));
    return json({ statusCode: 200, ...pageSlice(items, page.page!, page.size!) });
  }),

  http.post("/api/knowledge-bases", async ({ request }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    const user = auth.user!;
    if (user.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    const body = (await request.json()) as { name?: string; description?: string | null };
    const name = body.name?.trim() ?? "";
    if (!name || unicodeLength(name) > 100) {
      return err(400, "INVALID_KNOWLEDGE_BASE_REQUEST", "名称非法");
    }
    const store = getStore();
    if (store.knowledgeBases.some((k) => k.creatorId === user.id && k.name === name)) {
      return err(409, "CONFLICT", "知识库名称重复");
    }
    const ts = now();
    const kb = {
      id: nextId(),
      creatorId: user.id,
      name,
      description: body.description?.trim() || null,
      createdAt: ts,
      updatedAt: ts,
    };
    store.knowledgeBases.unshift(kb);
    return json({ statusCode: 201, ...kb }, { status: 201 });
  }),

  http.get("/api/knowledge-bases/:id", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.id)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const kb = getStore().knowledgeBases.find((k) => k.id === params.id)!;
    return json({ statusCode: 200, ...kb });
  }),

  http.patch("/api/knowledge-bases/:id", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.id)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const kb = getStore().knowledgeBases.find((k) => k.id === params.id)!;
    const body = (await request.json()) as { name?: string; description?: string | null };
    if (body.name === undefined && body.description === undefined) {
      return err(400, "INVALID_KNOWLEDGE_BASE_REQUEST", "空更新");
    }
    if (body.name !== undefined) {
      const name = body.name.trim();
      if (!name || unicodeLength(name) > 100) {
        return err(400, "INVALID_KNOWLEDGE_BASE_REQUEST", "名称非法");
      }
      kb.name = name;
    }
    if (body.description !== undefined) {
      kb.description = body.description?.trim() ? body.description.trim() : null;
    }
    kb.updatedAt = now();
    return json({ statusCode: 200, ...kb });
  }),

  http.delete("/api/knowledge-bases/:id", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    const store = getStore();
    const kb = store.knowledgeBases.find((k) => k.id === params.id);
    if (!kb) return err(404, "NOT_FOUND", "知识库不存在");
    if (kb.creatorId !== auth.user!.id) {
      if (canSeeKb(auth.user!.id, kb.id) === "ok") {
        return err(403, "FORBIDDEN", "仅创建者可删除");
      }
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    store.knowledgeBases = store.knowledgeBases.filter((k) => k.id !== kb.id);
    store.documents = store.documents.filter((d) => d.knowledgeBaseId !== kb.id);
    store.members = store.members.filter((m) => m.knowledgeBaseId !== kb.id);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get("/api/knowledge-bases/:id/documents", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.id)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const page = parsePage(new URL(request.url), 20);
    if ("error" in page && page.error) return page.error;
    const items = getStore()
      .documents.filter((d) => d.knowledgeBaseId === params.id)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || Number(b.id) - Number(a.id))
      .map(({ content: _c, creatorId: _cr, ...rest }) => rest);
    return json({ statusCode: 200, ...pageSlice(items, page.page!, page.size!) });
  }),

  http.post("/api/knowledge-bases/:id/documents", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.id)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const body = (await request.json()) as {
      title?: string;
      summary?: string;
      content?: string;
    };
    const title = body.title?.trim() ?? "";
    if (!title || unicodeLength(title) > 200) {
      return err(400, "INVALID_DOCUMENT_REQUEST", "标题非法");
    }
    if (!body.content || body.content.trim().length === 0 || unicodeLength(body.content) > 100000) {
      return err(400, "INVALID_DOCUMENT_REQUEST", "正文非法");
    }
    if (body.summary && unicodeLength(body.summary) > 500) {
      return err(400, "INVALID_DOCUMENT_REQUEST", "摘要过长");
    }
    const ts = now();
    const doc: MockDocument = {
      id: nextId(),
      knowledgeBaseId: String(params.id),
      creatorId: auth.user!.id,
      title,
      summary: body.summary?.trim() || null,
      content: body.content,
      createdAt: ts,
      updatedAt: ts,
    };
    getStore().documents.unshift(doc);
    return json({ statusCode: 201, ...doc }, { status: 201 });
  }),

  http.get("/api/knowledge-bases/:kbId/documents/:docId", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.kbId)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const doc = getStore().documents.find(
      (d) => d.id === params.docId && d.knowledgeBaseId === params.kbId,
    );
    if (!doc) return err(404, "NOT_FOUND", "文档不存在");
    return json({ statusCode: 200, ...doc });
  }),

  http.patch("/api/knowledge-bases/:kbId/documents/:docId", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.kbId)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const doc = getStore().documents.find(
      (d) => d.id === params.docId && d.knowledgeBaseId === params.kbId,
    );
    if (!doc) return err(404, "NOT_FOUND", "文档不存在");
    const body = (await request.json()) as {
      title?: string;
      summary?: string | null;
      content?: string;
    };
    if (body.title === undefined && body.summary === undefined && body.content === undefined) {
      return err(400, "INVALID_DOCUMENT_REQUEST", "空更新");
    }
    if (body.title !== undefined) {
      const title = body.title.trim();
      if (!title || unicodeLength(title) > 200) {
        return err(400, "INVALID_DOCUMENT_REQUEST", "标题非法");
      }
      doc.title = title;
    }
    if (body.content !== undefined) {
      if (!body.content.trim() || unicodeLength(body.content) > 100000) {
        return err(400, "INVALID_DOCUMENT_REQUEST", "正文非法");
      }
      doc.content = body.content;
    }
    if (body.summary !== undefined) {
      doc.summary = body.summary?.trim() ? body.summary.trim() : null;
    }
    doc.updatedAt = now();
    return json({ statusCode: 200, ...doc });
  }),

  http.delete("/api/knowledge-bases/:kbId/documents/:docId", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问知识库");
    if (canSeeKb(auth.user!.id, String(params.kbId)) !== "ok") {
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const store = getStore();
    const idx = store.documents.findIndex(
      (d) => d.id === params.docId && d.knowledgeBaseId === params.kbId,
    );
    if (idx < 0) return err(404, "NOT_FOUND", "文档不存在");
    store.documents.splice(idx, 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get("/api/knowledge-bases/:id/members", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问");
    const kb = getStore().knowledgeBases.find((k) => k.id === params.id);
    if (!kb) return err(404, "NOT_FOUND", "知识库不存在");
    if (kb.creatorId !== auth.user!.id) {
      if (canSeeKb(auth.user!.id, kb.id) === "ok") {
        return err(403, "FORBIDDEN", "仅创建者可查看成员");
      }
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const items = getStore()
      .members.filter((m) => m.knowledgeBaseId === kb.id)
      .map((m) => ({
        userId: m.userId,
        email: m.email,
        permission: m.permission,
        grantedAt: m.createdAt,
      }));
    return json({ statusCode: 200, items });
  }),

  http.post("/api/knowledge-bases/:id/members", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问");
    const kb = getStore().knowledgeBases.find((k) => k.id === params.id);
    if (!kb) return err(404, "NOT_FOUND", "知识库不存在");
    if (kb.creatorId !== auth.user!.id) {
      if (canSeeKb(auth.user!.id, kb.id) === "ok") {
        return err(403, "FORBIDDEN", "仅创建者可授权");
      }
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const body = (await request.json()) as { email?: string };
    if (!body.email?.trim()) return err(400, "INVALID_MEMBER_REQUEST", "邮箱非法");
    const target = findUserByEmail(body.email);
    if (!target || target.role !== "EDITOR") {
      return err(400, "INVALID_MEMBER_REQUEST", "目标用户不存在或不是编辑者");
    }
    if (target.id === auth.user!.id) {
      return err(400, "INVALID_MEMBER_REQUEST", "不能授权自己");
    }
    const store = getStore();
    if (store.members.some((m) => m.knowledgeBaseId === kb.id && m.userId === target.id)) {
      return err(409, "CONFLICT", "已授权");
    }
    const member = {
      id: nextId(),
      knowledgeBaseId: kb.id,
      userId: target.id,
      email: target.email,
      permission: "EDIT" as const,
      grantedBy: auth.user!.id,
      createdAt: now(),
    };
    store.members.push(member);
    return json({ statusCode: 201, ...member }, { status: 201 });
  }),

  http.delete("/api/knowledge-bases/:kbId/members/:userId", async ({ request, params }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    if (auth.user!.role !== "EDITOR") return err(403, "FORBIDDEN", "无权访问");
    const kb = getStore().knowledgeBases.find((k) => k.id === params.kbId);
    if (!kb) return err(404, "NOT_FOUND", "知识库不存在");
    if (kb.creatorId !== auth.user!.id) {
      if (canSeeKb(auth.user!.id, kb.id) === "ok") {
        return err(403, "FORBIDDEN", "仅创建者可取消授权");
      }
      return err(404, "NOT_FOUND", "知识库不存在");
    }
    const store = getStore();
    const idx = store.members.findIndex(
      (m) => m.knowledgeBaseId === kb.id && m.userId === params.userId,
    );
    if (idx < 0) return err(404, "NOT_FOUND", "成员不存在");
    store.members.splice(idx, 1);
    return new HttpResponse(null, { status: 204 });
  }),
];
