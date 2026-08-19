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
  bootstrapped: boolean;
  setUser: (user: AuthUser | null) => void;
  setBootstrapped: (value: boolean) => void;
  clear: () => void;
};

/**
 * 内存中的认证视图。HttpOnly Cookie 存 token，此处不存 token，也不持久化。
 * 刷新后依赖 GET /me 恢复。
 */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  bootstrapped: false,
  setUser: (user) => set({ user }),
  setBootstrapped: (bootstrapped) => set({ bootstrapped }),
  clear: () => set({ user: null }),
}));

export function isEditor(role: Role | undefined | null): boolean {
  return role === "EDITOR";
}
