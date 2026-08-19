import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { Result, Button, Spin } from "antd";
import { LoginPage } from "@/features/auth/pages/LoginPage";
import { RegisterPage } from "@/features/auth/pages/RegisterPage";
import { useAuthBootstrap } from "@/features/auth/hooks/useAuthBootstrap";
import { isEditor, useAuthStore } from "@/features/auth/auth-store";
import { AppLayout } from "@/app/AppLayout";
import { ChatPage } from "@/features/chat/pages/ChatPage";
import { KnowledgeBaseListPage } from "@/features/knowledge-base/pages/KnowledgeBaseListPage";
import { KnowledgeBaseDetailPage } from "@/features/knowledge-base/pages/KnowledgeBaseDetailPage";
import { MembersPage } from "@/features/knowledge-base/pages/MembersPage";
import { t } from "@/shared/i18n";
import { appEnv } from "@/shared/lib/env";
import { setUnauthorizedHandler } from "@/shared/api/client";
import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";

function BootstrapGate({ children }: { children: React.ReactNode }) {
  const { isLoading, bootstrapped } = useAuthBootstrap();
  if (!bootstrapped || isLoading) {
    return (
      <div style={{ minHeight: "100vh", display: "grid", placeItems: "center" }}>
        <Spin size="large" tip={t().common.loading} />
      </div>
    );
  }
  return <>{children}</>;
}

function RequireAuth() {
  const user = useAuthStore((s) => s.user);
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <Outlet />;
}

function RequireGuest() {
  const user = useAuthStore((s) => s.user);
  if (user) return <Navigate to="/chat" replace />;
  return <Outlet />;
}

/** 路由层角色校验：CUSTOMER 直接访问知识库 → 403 页 */
function RequireEditor() {
  const user = useAuthStore((s) => s.user);
  const i18n = t();
  if (!isEditor(user?.role)) {
    return (
      <Result
        status="403"
        title="403"
        subTitle={i18n.common.forbidden}
        extra={
          <Button type="primary" href="/chat">
            {i18n.nav.chat}
          </Button>
        }
      />
    );
  }
  return <Outlet />;
}

function UnauthorizedBridge() {
  const navigate = useNavigate();
  const clear = useAuthStore((s) => s.clear);
  const qc = useQueryClient();

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clear();
      void qc.clear();
      navigate("/login", { replace: true });
    });
    return () => setUnauthorizedHandler(null);
  }, [clear, navigate, qc]);

  return null;
}

export function AppRouter() {
  const i18n = t();
  return (
    <BootstrapGate>
      <UnauthorizedBridge />
      <Routes>
        <Route element={<RequireGuest />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
        </Route>

        <Route element={<RequireAuth />}>
          <Route element={<AppLayout />}>
            <Route path="/" element={<Navigate to="/chat" replace />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/chat/:conversationId" element={<ChatPage />} />

            <Route element={<RequireEditor />}>
              <Route path="/knowledge-bases" element={<KnowledgeBaseListPage />} />
              <Route path="/knowledge-bases/:id" element={<KnowledgeBaseDetailPage />} />
              {appEnv.enableKbMembership ? (
                <Route path="/knowledge-bases/:id/members" element={<MembersPage />} />
              ) : null}
            </Route>
          </Route>
        </Route>

        <Route
          path="*"
          element={
            <Result
              status="404"
              title="404"
              subTitle={i18n.common.notFound}
              extra={
                <Button type="primary" href="/chat">
                  {i18n.nav.chat}
                </Button>
              }
            />
          }
        />
      </Routes>
    </BootstrapGate>
  );
}
