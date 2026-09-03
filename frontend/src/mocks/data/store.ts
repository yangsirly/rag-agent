import { normalizeEmail } from "@/shared/lib/validation";

export type MockRole = "CUSTOMER" | "EDITOR";
export type MockUserStatus = "ACTIVE" | "DISABLED";

export type MockUser = {
  id: string;
  email: string;
  password: string;
  role: MockRole;
  status: MockUserStatus;
};

export type MockConversation = {
  id: string;
  userId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};

export type MockMessage = {
  id: string;
  conversationId: string;
  clientMessageId?: string;
  replyToMessageId?: string;
  role: "USER" | "ASSISTANT";
  content: string;
  createdAt: string;
};

export type MockKnowledgeBase = {
  id: string;
  creatorId: string;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
};

export type MockDocument = {
  id: string;
  knowledgeBaseId: string;
  creatorId: string;
  title: string;
  summary: string | null;
  content: string;
  createdAt: string;
  updatedAt: string;
};

export type MockMember = {
  id: string;
  knowledgeBaseId: string;
  userId: string;
  email: string;
  permission: "EDIT";
  grantedBy: string;
  createdAt: string;
};

export type MockFault =
  | "none"
  | "delay"
  | "timeout"
  | "400"
  | "401"
  | "403"
  | "404"
  | "409"
  | "500";

export type MockSession = {
  userId: string;
  email: string;
  role: MockRole;
  accessToken: string;
  refreshToken: string;
  revoked: boolean;
  /** 轮换或撤销后仍需识别的旧 Access Token，避免快照回退重新放行。 */
  invalidatedAccessTokens: string[];
};

export type MockStore = {
  users: MockUser[];
  sessions: Record<string, MockSession>;
  conversations: MockConversation[];
  messages: MockMessage[];
  knowledgeBases: MockKnowledgeBase[];
  documents: MockDocument[];
  members: MockMember[];
  seq: number;
  fault: MockFault;
};

export const TEMPLATE_REPLY =
  "已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。";

const SESSION_STORAGE_KEY = "rag-agent-mock-sessions";

export function now() {
  return new Date().toISOString();
}

function seed(): MockStore {
  const createdAt = now();
  return {
    users: [
      {
        id: "1",
        email: "customer@example.com",
        password: "password1",
        role: "CUSTOMER",
        status: "ACTIVE",
      },
      {
        id: "2",
        email: "editor@example.com",
        password: "password1",
        role: "EDITOR",
        status: "ACTIVE",
      },
      {
        id: "3",
        email: "editor.b@example.com",
        password: "password1",
        role: "EDITOR",
        status: "ACTIVE",
      },
      {
        id: "4",
        email: "disabled@example.com",
        password: "password1",
        role: "CUSTOMER",
        status: "DISABLED",
      },
    ],
    sessions: {},
    conversations: [],
    messages: [],
    knowledgeBases: [
      {
        id: "100",
        creatorId: "2",
        name: "产品 FAQ",
        description: "示例知识库",
        createdAt,
        updatedAt: createdAt,
      },
    ],
    documents: [
      {
        id: "1000",
        knowledgeBaseId: "100",
        creatorId: "2",
        title: "退货政策",
        summary: "七天无理由",
        content: "支持七天无理由退货，详情以订单页为准。",
        createdAt,
        updatedAt: createdAt,
      },
    ],
    members: [],
    seq: 2000,
    fault: "none",
  };
}

function loadPersistedSessions(): Record<string, MockSession> {
  try {
    if (typeof localStorage === "undefined") return {};
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, MockSession>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function persistSessions() {
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(store.sessions));
    }
  } catch {
    // localStorage 在隐私模式/服务端测试中可能不可用，内存 Mock 仍可继续工作。
  }
}

let store: MockStore = seed();
store.sessions = loadPersistedSessions();

export function getStore(): MockStore {
  return store;
}

export function resetStore() {
  store = seed();
  try {
    if (typeof localStorage !== "undefined") localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // ignore unavailable browser storage
  }
}

export function setFault(fault: MockFault) {
  store.fault = fault;
}

export function nextId(): string {
  store.seq += 1;
  return String(store.seq);
}

export function findUserByEmail(email: string): MockUser | undefined {
  const normalized = normalizeEmail(email);
  return store.users.find((u) => u.email === normalized);
}

export function findUserById(id: string): MockUser | undefined {
  return store.users.find((u) => u.id === id);
}

export function createSession(userId: string): string {
  const user = findUserById(userId);
  const sessionId = `${userId}-${Math.random().toString(36).slice(2)}`;
  const snapshot = encodeUserSnapshot({
    userId,
    email: user?.email ?? "mock@example.com",
    role: user?.role ?? "CUSTOMER",
  });
  const accessToken = `mock-access-${snapshot}-${Math.random().toString(36).slice(2)}`;
  const refreshToken = `mock-refresh-${snapshot}.${sessionId}.${Math.random().toString(36).slice(2)}`;
  store.sessions[sessionId] = {
    userId,
    email: user?.email ?? "mock@example.com",
    role: user?.role ?? "CUSTOMER",
    accessToken,
    refreshToken,
    revoked: false,
    invalidatedAccessTokens: [],
  };
  persistSessions();
  return accessToken;
}

export function clearSession(token: string | null) {
  if (!token) return;
  const parsedRefresh = refreshSnapshot(token);
  const session = findSession(token) ?? (parsedRefresh ? store.sessions[parsedRefresh.sessionId] : undefined);
  if (session) {
    session.revoked = true;
    persistSessions();
  }
}

export function userIdFromToken(token: string | null): string | null {
  if (!token) return null;
  const session = findSession(token);
  if (session) {
    return !session.revoked && session.accessToken === token ? session.userId : null;
  }
  return accessSnapshot(token)?.userId ?? null;
}

/**
 * 返回当前 Mock Access Token 对应的用户；页面重载导致内存用户表丢失时，
 * 从 Token 中的非敏感快照恢复最小用户信息，保持认证链路可继续验证。
 */
export function userFromToken(token: string | null): MockUser | null {
  if (!token) return null;
  const session = findSession(token);
  if (session) {
    if (session.revoked || session.accessToken !== token) return null;
    return (
      findUserById(session.userId) ?? {
        id: session.userId,
        email: session.email,
        password: "",
        role: session.role,
        status: "ACTIVE",
      }
    );
  }
  const snapshot = accessSnapshot(token);
  if (!snapshot) return null;
  return {
    id: snapshot.userId,
    email: snapshot.email,
    password: "",
    role: snapshot.role,
    status: "ACTIVE",
  };
}

function findSession(token: string): MockSession | undefined {
  return Object.values(store.sessions).find(
    (session) =>
      session.accessToken === token ||
      session.refreshToken === token ||
      session.invalidatedAccessTokens?.includes(token),
  );
}

export function sessionTokens(accessToken: string | null) {
  if (!accessToken) return null;
  const session = findSession(accessToken);
  return session ? { accessToken: session.accessToken, refreshToken: session.refreshToken } : null;
}

export function rotateSession(refreshToken: string | null) {
  if (!refreshToken) return null;
  const parsed = refreshSnapshot(refreshToken);
  if (!parsed) return null;
  const sessionId = parsed.sessionId;
  // 页面 reload 后应从 localStorage 载入已知会话；不能仅凭 Token 中的
  // 非敏感快照创建新会话，否则任意伪造 sessionId/secret 都会被接受。
  const session = store.sessions[sessionId];
  if (!session || session.revoked) {
    return null;
  }
  if (session.refreshToken !== refreshToken) {
    session.revoked = true;
    persistSessions();
    return null;
  }
  if (session.accessToken) {
    session.invalidatedAccessTokens = [
      ...(session.invalidatedAccessTokens ?? []),
      session.accessToken,
    ];
  }
  const snapshot = encodeUserSnapshot(session);
  session.accessToken = `mock-access-${snapshot}-${Math.random().toString(36).slice(2)}`;
  session.refreshToken = `mock-refresh-${snapshot}.${sessionId}.${Math.random().toString(36).slice(2)}`;
  persistSessions();
  return { ...session };
}

export function parseCookieToken(cookieHeader: string | null, name = "access_token"): string | null {
  if (!cookieHeader) return null;
  const parts = cookieHeader.split(";").map((p) => p.trim());
  for (const p of parts) {
    if (p.startsWith(`${name}=`)) {
      return decodeURIComponent(p.slice(name.length + 1));
    }
  }
  return null;
}

export function cookieHeaders(accessToken: string, refreshToken: string, accessMaxAge = 900, refreshMaxAge = 604800): Headers {
  const headers = new Headers();
  headers.append("Set-Cookie", `access_token=${encodeURIComponent(accessToken)}; Path=/; SameSite=Lax; HttpOnly; Max-Age=${accessMaxAge}`);
  headers.append("Set-Cookie", `refresh_token=${encodeURIComponent(refreshToken)}; Path=/; SameSite=Lax; HttpOnly; Max-Age=${refreshMaxAge}`);
  return headers;
}

function encodeUserSnapshot(snapshot: { userId: string; email: string; role: MockRole }): string {
  // URL 编码后再转义连字符，便于把快照安全地放入以连字符分隔的 Mock Token。
  return encodeURIComponent(`${snapshot.userId}|${snapshot.email}|${snapshot.role}`)
    .replaceAll("-", "%2D")
    .replaceAll(".", "%2E");
}

function decodeUserSnapshot(encoded: string): { userId: string; email: string; role: MockRole } | null {
  try {
    const [userId, email, role] = decodeURIComponent(encoded).split("|");
    if (!userId || !email || (role !== "CUSTOMER" && role !== "EDITOR")) return null;
    return { userId, email, role };
  } catch {
    return null;
  }
}

function accessSnapshot(token: string): { userId: string; email: string; role: MockRole } | null {
  if (!token.startsWith("mock-access-")) return null;
  const payload = token.slice("mock-access-".length);
  const separator = payload.lastIndexOf("-");
  return separator > 0 ? decodeUserSnapshot(payload.slice(0, separator)) : null;
}

function refreshSnapshot(refreshToken: string) {
  if (!refreshToken.startsWith("mock-refresh-")) return null;
  const payload = refreshToken.slice("mock-refresh-".length);
  const lastSeparator = payload.lastIndexOf(".");
  const sessionSeparator = payload.lastIndexOf(".", lastSeparator - 1);
  if (sessionSeparator <= 0 || lastSeparator <= sessionSeparator + 1) return null;
  const snapshot = decodeUserSnapshot(payload.slice(0, sessionSeparator));
  const sessionId = payload.slice(sessionSeparator + 1, lastSeparator);
  const secret = payload.slice(lastSeparator + 1);
  if (!snapshot || !sessionId || !secret) return null;
  return { ...snapshot, sessionId };
}

export function clearCookieHeaders(): Headers {
  const headers = new Headers();
  headers.append("Set-Cookie", "access_token=; Path=/; SameSite=Lax; HttpOnly; Max-Age=0");
  headers.append("Set-Cookie", "refresh_token=; Path=/; SameSite=Lax; HttpOnly; Max-Age=0");
  return headers;
}

export function pageSlice<T>(items: T[], page: number, size: number) {
  const totalElements = items.length;
  const safeTotalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size);
  const start = page * size;
  const slice = items.slice(start, start + size);
  return {
    items: slice,
    page,
    size,
    totalElements,
    totalPages: safeTotalPages,
  };
}
