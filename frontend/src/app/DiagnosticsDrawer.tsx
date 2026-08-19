import { Button, Descriptions, Drawer, Select, Space, Table, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import {
  clearDiagnostics,
  getDiagnosticsEntries,
  subscribeDiagnostics,
  type DiagnosticsEntry,
} from "@/shared/api/diagnostics-log";
import { appEnv } from "@/shared/lib/env";
import { useAuthStore } from "@/features/auth/auth-store";
import { useUiStore } from "@/shared/store/ui-store";
import { t } from "@/shared/i18n";

/**
 * 开发诊断抽屉：API 模式、用户、开关、请求日志。
 * 密码/Cookie/Token 在写入日志前已脱敏；不进入默认生产构建（env 控制）。
 */
export function DiagnosticsDrawer() {
  const i18n = t();
  const open = useUiStore((s) => s.diagnosticsOpen);
  const setOpen = useUiStore((s) => s.setDiagnosticsOpen);
  const user = useAuthStore((s) => s.user);
  const [entries, setEntries] = useState<DiagnosticsEntry[]>(getDiagnosticsEntries());

  useEffect(() => subscribeDiagnostics(setEntries), []);

  const onResetMock = async () => {
    if (appEnv.apiMode !== "mock") {
      message.warning("仅 Mock 模式可用");
      return;
    }
    const mod = await import("@/mocks/runtime");
    mod.resetMockStore();
    message.success("Mock 数据已重置");
  };

  const onSwitchUser = async (email: string) => {
    if (appEnv.apiMode !== "mock") {
      message.warning("仅 Mock 模式可用");
      return;
    }
    const mod = await import("@/mocks/runtime");
    await mod.switchMockUser(email);
    window.location.reload();
  };

  const onInject = async (fault: string) => {
    if (appEnv.apiMode !== "mock") {
      message.warning("仅 Mock 模式可用");
      return;
    }
    const mod = await import("@/mocks/runtime");
    mod.setMockFault(fault);
    message.success(`已注入故障: ${fault}`);
  };

  return (
    <Drawer
      title={i18n.diagnostics.title}
      open={open}
      onClose={() => setOpen(false)}
      width={560}
    >
      <Descriptions column={1} size="small" bordered style={{ marginBottom: 16 }}>
        <Descriptions.Item label={i18n.diagnostics.apiMode}>
          <Tag color={appEnv.apiMode === "mock" ? "purple" : "blue"}>{appEnv.apiMode}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label={i18n.diagnostics.user}>
          {user ? `${user.email} (${user.role})` : "未登录"}
        </Descriptions.Item>
        <Descriptions.Item label={i18n.diagnostics.flags}>
          KB_MEMBERSHIP={String(appEnv.enableKbMembership)}; DIAGNOSTICS=
          {String(appEnv.enableDiagnostics)}
        </Descriptions.Item>
      </Descriptions>

      {appEnv.apiMode === "mock" ? (
        <Space wrap style={{ marginBottom: 16 }}>
          <Button onClick={() => void onResetMock()}>{i18n.diagnostics.resetMock}</Button>
          <Select
            placeholder={i18n.diagnostics.switchUser}
            style={{ width: 220 }}
            onChange={(v) => void onSwitchUser(v)}
            options={[
              { value: "customer@example.com", label: "CUSTOMER" },
              { value: "editor@example.com", label: "EDITOR" },
              { value: "editor.b@example.com", label: "EDITOR B" },
            ]}
          />
          <Select
            placeholder={i18n.diagnostics.injectFault}
            style={{ width: 180 }}
            onChange={(v) => void onInject(v)}
            options={[
              { value: "none", label: "清除故障" },
              { value: "delay", label: "延迟 3s" },
              { value: "timeout", label: "超时" },
              { value: "400", label: "400" },
              { value: "401", label: "401" },
              { value: "403", label: "403" },
              { value: "404", label: "404" },
              { value: "409", label: "409" },
              { value: "500", label: "500" },
            ]}
          />
          <Button onClick={() => clearDiagnostics()}>{i18n.diagnostics.clear}</Button>
        </Space>
      ) : (
        <Button style={{ marginBottom: 16 }} onClick={() => clearDiagnostics()}>
          {i18n.diagnostics.clear}
        </Button>
      )}

      <Table
        size="small"
        rowKey="id"
        pagination={{ pageSize: 8 }}
        dataSource={entries}
        columns={[
          {
            title: "方法",
            dataIndex: "method",
            width: 70,
          },
          {
            title: "路径",
            dataIndex: "path",
            ellipsis: true,
          },
          {
            title: "状态",
            dataIndex: "status",
            width: 90,
            render: (s: DiagnosticsEntry["status"]) => <Tag>{String(s)}</Tag>,
          },
          {
            title: "耗时",
            dataIndex: "durationMs",
            width: 70,
            render: (v?: number) => (v != null ? `${v}ms` : "—"),
          },
        ]}
        expandable={{
          expandedRowRender: (record) => (
            <Typography.Paragraph style={{ margin: 0, whiteSpace: "pre-wrap" }}>
              requestId: {record.requestId}
              {"\n"}
              {record.errorCode ? `code: ${record.errorCode}\n` : ""}
              {record.note ? `note: ${record.note}\n` : ""}
              {record.summary}
            </Typography.Paragraph>
          ),
        }}
      />
    </Drawer>
  );
}
