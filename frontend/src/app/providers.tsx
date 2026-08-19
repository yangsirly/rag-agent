import { QueryClientProvider } from "@tanstack/react-query";
import { App as AntApp, ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import { useMemo, type ReactNode } from "react";
import { createAppQueryClient } from "@/shared/api/query-client";
import { darkTheme, lightTheme } from "@/shared/theme/tokens";
import { useResolvedTheme } from "@/shared/theme/useResolvedTheme";

const queryClient = createAppQueryClient();

export function AppProviders({ children }: { children: ReactNode }) {
  const resolved = useResolvedTheme();
  const theme = useMemo(() => (resolved === "dark" ? darkTheme : lightTheme), [resolved]);

  return (
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN} theme={theme}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  );
}

