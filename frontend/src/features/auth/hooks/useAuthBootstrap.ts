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
  const setBootstrapped = useAuthStore((s) => s.setBootstrapped);
  const bootstrapped = useAuthStore((s) => s.bootstrapped);

  const query = useQuery({
    queryKey: ["auth", "me"],
    queryFn: meApi,
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
      setBootstrapped(true);
      return;
    }
    if (query.isError) {
      const err = query.error;
      if (err instanceof AppApiError && (err.isUnauthorized || err.statusCode === 401)) {
        setUser(null);
      }
      // 网络错误时也结束启动，交给页面展示重试；不把用户当已登录
      setBootstrapped(true);
    }
  }, [query.isSuccess, query.isError, query.data, query.error, setUser, setBootstrapped]);

  return {
    bootstrapped,
    isLoading: !bootstrapped || query.isLoading,
    error: query.isError ? query.error : null,
    refetch: query.refetch,
  };
}
