import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { meApi } from "@/features/auth/api";
import { useAuthStore } from "@/features/auth/auth-store";
import { AppApiError } from "@/shared/api/errors";

/**
 * 应用启动：先请求 /me，完成前显示启动页，避免未登录闪到受保护路由。
 * 学习笔记：docs/learning/milestone-frontend-phase1.md#httponly-cookie与启动恢复
 */
export function useAuthBootstrap() {
  const setUser = useAuthStore((s) => s.setUser);
  const setStatus = useAuthStore((s) => s.setStatus);
  const status = useAuthStore((s) => s.status);

  const query = useQuery({
    queryKey: ["auth", "me"],
    queryFn: meApi,
    enabled: status === "bootstrapping",
    retry: false,
    staleTime: 60_000,
  });

  useEffect(() => {
    if (query.isSuccess) {
      setUser({
        userId: query.data.userId,
        email: query.data.email,
        role: query.data.role,
      });
      return;
    }
    if (query.isError) {
      const err = query.error;
      if (err instanceof AppApiError && (err.isUnauthorized || err.statusCode === 401)) {
        setUser(null);
      } else setStatus("error");
    }
  }, [query.isSuccess, query.isError, query.data, query.error, setUser, setStatus]);

  return {
    status,
    isLoading: status === "bootstrapping" || query.isFetching,
    error: query.isError ? query.error : null,
    refetch: query.refetch,
  };
}
