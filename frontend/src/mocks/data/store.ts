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

export type MockStore = {
  users: MockUser[];
  sessions: Record<string, string>;
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

let store: MockStore = seed();

export function getStore(): MockStore {
  return store;
}

export function resetStore() {
  store = seed();
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
  const token = `mock-token-${userId}-${Math.random().toString(36).slice(2)}`;
  store.sessions[token] = userId;
  return token;
}

export function clearSession(token: string | null) {
  if (!token) return;
  delete store.sessions[token];
}

export function userIdFromToken(token: string | null): string | null {
  if (!token) return null;
  return store.sessions[token] ?? null;
}

export function parseCookieToken(cookieHeader: string | null): string | null {
  if (!cookieHeader) return null;
  const parts = cookieHeader.split(";").map((p) => p.trim());
  for (const p of parts) {
    if (p.startsWith("access_token=")) {
      return decodeURIComponent(p.slice("access_token=".length));
    }
  }
  return null;
}

export function cookieHeader(token: string, maxAge = 1800): string {
  return `access_token=${encodeURIComponent(token)}; Path=/; SameSite=Lax; Max-Age=${maxAge}`;
}

export function clearCookieHeader(): string {
  return "access_token=; Path=/; SameSite=Lax; Max-Age=0";
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
