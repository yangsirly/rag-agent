import {
  createSession,
  findUserByEmail,
  getStore,
  resetStore,
  sessionTokens,
  setFault,
  type MockFault,
} from "@/mocks/data/store";

export function resetMockStore() {
  resetStore();
}

export function setMockFault(fault: string) {
  setFault((fault as MockFault) || "none");
}

export async function switchMockUser(email: string) {
  const user = findUserByEmail(email);
  if (!user) throw new Error("用户不存在");
  const store = getStore();
  store.sessions = {};
  const token = createSession(user.id);
  const tokens = sessionTokens(token)!;
  document.cookie = `access_token=${encodeURIComponent(tokens.accessToken)}; Path=/; SameSite=Lax; Max-Age=900`;
  document.cookie = `refresh_token=${encodeURIComponent(tokens.refreshToken)}; Path=/; SameSite=Lax; Max-Age=604800`;
}
