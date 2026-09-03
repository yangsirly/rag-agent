import { delay, HttpResponse } from "msw";
import {
  getStore,
  parseCookieToken,
  userFromToken,
} from "@/mocks/data/store";
import { appEnv } from "@/shared/lib/env";

export function json(
  data: Record<string, unknown> | unknown[] | null,
  init?: { status?: number; headers?: HeadersInit },
) {
  return HttpResponse.json(data as never, {
    status: init?.status ?? 200,
    headers: init?.headers,
  });
}

export function err(status: number, code: string, message: string) {
  return json({ statusCode: status, code, message }, { status });
}

function decodeCookieValue(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

export function getToken(request: Request, cookies?: Record<string, string>): string | null {
  return cookies?.access_token
    ? decodeCookieValue(cookies.access_token)
    : parseCookieToken(request.headers.get("cookie"));
}

export function getRefreshToken(request: Request, cookies?: Record<string, string>): string | null {
  return cookies?.refresh_token
    ? decodeCookieValue(cookies.refresh_token)
    : parseCookieToken(request.headers.get("cookie"), "refresh_token");
}

export function requireUser(request: Request, cookies?: Record<string, string>) {
  const token = getToken(request, cookies);
  const user = userFromToken(token);
  if (!user || user.status !== "ACTIVE") {
    return { error: err(401, "UNAUTHORIZED", "未登录或凭证无效") };
  }
  return { user, token };
}

export async function applyFault() {
  const fault = getStore().fault;
  if (fault === "none") return null;
  if (fault === "delay") {
    await delay(3000);
    return null;
  }
  if (fault === "timeout") {
    await delay(20_000);
    return err(500, "INTERNAL_SERVER_ERROR", "模拟超时");
  }
  if (fault === "400") return err(400, "INVALID_REQUEST", "模拟 400");
  if (fault === "401") return err(401, "UNAUTHORIZED", "模拟 401");
  if (fault === "403") return err(403, "FORBIDDEN", "模拟 403");
  if (fault === "404") return err(404, "NOT_FOUND", "模拟 404");
  if (fault === "409") return err(409, "CONFLICT", "模拟 409");
  if (fault === "500") return err(500, "INTERNAL_SERVER_ERROR", "模拟 500");
  return null;
}

export function parsePage(url: URL, defaultSize = 20) {
  const page = Number(url.searchParams.get("page") ?? "0");
  const size = Number(url.searchParams.get("size") ?? String(defaultSize));
  if (!Number.isInteger(page) || page < 0 || !Number.isInteger(size) || size < 1 || size > 100) {
    return { error: err(400, "INVALID_REQUEST", "分页参数非法") };
  }
  return { page, size };
}

export function canSeeKb(userId: string, kbId: string): "ok" | "not_found" {
  const store = getStore();
  const kb = store.knowledgeBases.find((k) => k.id === kbId);
  if (!kb) return "not_found";
  if (kb.creatorId === userId) return "ok";
  if (appEnv.enableKbMembership) {
    const member = store.members.find((m) => m.knowledgeBaseId === kbId && m.userId === userId);
    if (member) return "ok";
  }
  return "not_found";
}

export const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
