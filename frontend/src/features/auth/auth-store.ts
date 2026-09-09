import { create } from "zustand";
import type { Role } from "@/shared/api/schemas";

export type AuthUser = {
  userId: string;
  email: string;
  role: Role;
};

type AuthState = {
  /** null = 未登录；bootstrap 完成前不要用此字段做路由闪烁决策 */
  user: AuthUser | null;
  status: "bootstrapping" | "authenticated" | "anonymous" | "error";
  setStatus: (status: AuthState["status"]) => void;
  setUser: (user: AuthUser | null) => void;
  clear: () => void;
};

/**
 * 内存中的认证视图。HttpOnly Cookie 存 token，此处不存 token，也不持久化。
 * 刷新后依赖 GET /me 恢复。
 */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  status: "bootstrapping",
  setStatus: (status) => set({ status }),
  setUser: (user) => set({ user, status: user ? "authenticated" : "anonymous" }),
  clear: () => set({ user: null, status: "anonymous" }),
}));

export function isEditor(role: Role | undefined | null): boolean {
  return role === "EDITOR";
}
