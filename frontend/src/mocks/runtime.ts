import {
  createSession,
  findUserByEmail,
  getStore,
  resetStore,
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
  document.cookie = `access_token=${encodeURIComponent(token)}; Path=/; SameSite=Lax; Max-Age=1800`;
}
