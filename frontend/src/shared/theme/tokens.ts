import type { ThemeConfig } from "antd";
import { theme as antTheme } from "antd";

const shared: ThemeConfig = {
  token: {
    borderRadius: 10,
    fontFamily:
      '"Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif',
    colorPrimary: "#5B5CE2",
    colorInfo: "#5B5CE2",
    colorSuccess: "#16A34A",
    colorWarning: "#D97706",
    colorError: "#DC2626",
    controlHeight: 40,
  },
  components: {
    Layout: {
      headerHeight: 56,
      siderBg: "transparent",
      headerBg: "transparent",
      bodyBg: "transparent",
    },
    Button: {
      borderRadius: 10,
    },
    Card: {
      borderRadiusLG: 14,
    },
  },
};

export const lightTheme: ThemeConfig = {
  ...shared,
  algorithm: antTheme.defaultAlgorithm,
  token: {
    ...shared.token,
    colorBgLayout: "#F4F6FB",
    colorBgContainer: "#FFFFFF",
    colorText: "#0F172A",
  },
};

export const darkTheme: ThemeConfig = {
  ...shared,
  algorithm: antTheme.darkAlgorithm,
  token: {
    ...shared.token,
    colorBgLayout: "#0B1020",
    colorBgContainer: "#121A2B",
    colorText: "#E5E7EB",
    colorPrimary: "#818CF8",
  },
};
