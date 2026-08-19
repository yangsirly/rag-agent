import { create } from "zustand";
import { persist } from "zustand/middleware";

export type ThemePreference = "light" | "dark" | "system";

type UiState = {
  themePreference: ThemePreference;
  setThemePreference: (theme: ThemePreference) => void;
  diagnosticsOpen: boolean;
  setDiagnosticsOpen: (open: boolean) => void;
  mobileNavOpen: boolean;
  setMobileNavOpen: (open: boolean) => void;
};

/**
 * 仅管理客户端 UI 状态。用户身份、会话、知识库等服务端数据不得写入此处或 localStorage。
 * 学习笔记：docs/learning/milestone-frontend-phase1.md#服务端状态与客户端状态
 */
export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      themePreference: "system",
      setThemePreference: (themePreference) => set({ themePreference }),
      diagnosticsOpen: false,
      setDiagnosticsOpen: (diagnosticsOpen) => set({ diagnosticsOpen }),
      mobileNavOpen: false,
      setMobileNavOpen: (mobileNavOpen) => set({ mobileNavOpen }),
    }),
    {
      name: "rag-agent-ui",
      // 只持久化主题偏好，不落盘任何认证信息
      partialize: (state) => ({ themePreference: state.themePreference }),
    },
  ),
);
