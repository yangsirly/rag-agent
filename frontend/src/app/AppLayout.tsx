import {
  BookOutlined,
  BugOutlined,
  LogoutOutlined,
  MenuOutlined,
  MessageOutlined,
  MoonOutlined,
  SunOutlined,
  DesktopOutlined,
} from "@ant-design/icons";
import { Button, Drawer, Layout, Menu, Space, Typography, Grid, theme } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { logoutApi } from "@/features/auth/api";
import { isEditor, useAuthStore } from "@/features/auth/auth-store";
import { appEnv } from "@/shared/lib/env";
import { t } from "@/shared/i18n";
import { useUiStore } from "@/shared/store/ui-store";
import { cycleTheme } from "@/shared/theme/useResolvedTheme";
import { ConversationSidebar } from "@/features/chat/components/ConversationSidebar";
import styles from "./layout.module.css";
import { lazy, Suspense } from "react";

const DiagnosticsDrawer = lazy(async () => {
  const m = await import("@/app/DiagnosticsDrawer");
  return { default: m.DiagnosticsDrawer };
});

const { Header, Content, Sider } = Layout;

export function AppLayout() {
  const i18n = t();
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const clear = useAuthStore((s) => s.clear);
  const queryClient = useQueryClient();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;
  const isTablet = screens.md && !screens.lg;
  const mobileNavOpen = useUiStore((s) => s.mobileNavOpen);
  const setMobileNavOpen = useUiStore((s) => s.setMobileNavOpen);
  const themePreference = useUiStore((s) => s.themePreference);
  const setThemePreference = useUiStore((s) => s.setThemePreference);
  const setDiagnosticsOpen = useUiStore((s) => s.setDiagnosticsOpen);
  const { token } = theme.useToken();

  const logoutMut = useMutation({
    mutationFn: logoutApi,
    onSettled: async () => {
      clear();
      await queryClient.clear();
      navigate("/login", { replace: true });
    },
  });

  const selected = location.pathname.startsWith("/knowledge-bases")
    ? "kb"
    : "chat";

  const menuItems = [
    { key: "chat", icon: <MessageOutlined />, label: i18n.nav.chat },
    ...(isEditor(user?.role)
      ? [{ key: "kb", icon: <BookOutlined />, label: i18n.nav.knowledgeBases }]
      : []),
  ];

  const onMenuClick = ({ key }: { key: string }) => {
    if (key === "chat") navigate("/chat");
    if (key === "kb") navigate("/knowledge-bases");
    setMobileNavOpen(false);
  };

  const ThemeIcon =
    themePreference === "light" ? SunOutlined : themePreference === "dark" ? MoonOutlined : DesktopOutlined;

  const navMenu = (
    <Menu
      mode="inline"
      selectedKeys={[selected]}
      items={menuItems}
      onClick={onMenuClick}
      style={{ borderInlineEnd: 0, background: "transparent" }}
    />
  );

  return (
    <Layout className={styles.root} style={{ background: token.colorBgLayout }}>
      {!isMobile ? (
        <Sider
          width={isTablet ? 72 : 220}
          collapsed={isTablet}
          collapsedWidth={72}
          className={styles.sider}
          theme="light"
        >
          <div className={styles.brand}>
            <Typography.Text strong>{isTablet ? "RA" : i18n.appName}</Typography.Text>
          </div>
          {navMenu}
        </Sider>
      ) : null}

      <Layout style={{ background: "transparent" }}>
        <Header className={styles.header} style={{ background: token.colorBgContainer }}>
          <Space>
            {isMobile ? (
              <Button icon={<MenuOutlined />} onClick={() => setMobileNavOpen(true)} />
            ) : null}
            <Typography.Text strong>
              {selected === "kb" ? i18n.nav.knowledgeBases : i18n.nav.chat}
            </Typography.Text>
          </Space>
          <Space wrap>
            <Typography.Text type="secondary">{user?.email}</Typography.Text>
            <Button
              icon={<ThemeIcon />}
              onClick={() => setThemePreference(cycleTheme(themePreference))}
            >
              {i18n.nav.theme}
            </Button>
            {appEnv.enableDiagnostics ? (
              <Button icon={<BugOutlined />} onClick={() => setDiagnosticsOpen(true)}>
                {i18n.nav.diagnostics}
              </Button>
            ) : null}
            <Button
              icon={<LogoutOutlined />}
              loading={logoutMut.isPending}
              onClick={() => logoutMut.mutate()}
            >
              {i18n.common.logout}
            </Button>
          </Space>
        </Header>
        <Content className={styles.content}>
          <Outlet />
        </Content>
      </Layout>

      <Drawer
        title={i18n.appName}
        placement="left"
        open={mobileNavOpen}
        onClose={() => setMobileNavOpen(false)}
        width={300}
      >
        {navMenu}
        {selected === "chat" ? (
          <div style={{ marginTop: 12, height: "70vh" }}>
            <ConversationSidebar onNavigate={() => setMobileNavOpen(false)} />
          </div>
        ) : null}
      </Drawer>

      {appEnv.enableDiagnostics ? (
        <Suspense fallback={null}>
          <DiagnosticsDrawer />
        </Suspense>
      ) : null}
    </Layout>
  );
}
