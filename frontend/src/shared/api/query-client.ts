import { QueryClient } from "@tanstack/react-query";
import { AppApiError } from "./errors";

/**
 * 服务端状态唯一来源：TanStack Query Cache。
 * 主题/诊断抽屉等纯客户端状态才进 Zustand。
 */
export function createAppQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => {
          if (error instanceof AppApiError) {
            if (error.statusCode >= 400 && error.statusCode < 500) return false;
            if (error.isNetwork) return failureCount < 2;
          }
          return failureCount < 1;
        },
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
