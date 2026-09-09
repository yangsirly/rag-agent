import { useAuthStore } from "@/features/auth/auth-store";
import { getUserFacingError } from "@/shared/api/errors";
import { unicodeLength } from "@/shared/lib/unicode";
import { Button, Card, Drawer, Form, Input, List, Modal, Space, Typography, App } from "antd";
import { PlusOutlined, TeamOutlined } from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  createDocument,
  deleteDocument,
  getDocument,
  getKnowledgeBase,
  listDocuments,
  updateDocument,
} from "@/features/knowledge-base/api";
import { appEnv } from "@/shared/lib/env";
import { ConfirmDeleteButton } from "@/shared/ui/ConfirmDeleteButton";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import {
  docContentField,
  docSummaryField,
  docTitleField,
  zodFormRule,
} from "@/shared/lib/validation";
import styles from "./kb.module.css";

export function KnowledgeBaseDetailPage() {
  const i18n = t();
  const { message } = App.useApp();
  const user = useAuthStore((s) => s.user);
  const { id = "" } = useParams();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [viewerOpen, setViewerOpen] = useState(false);
  const [viewDocId, setViewDocId] = useState<string | null>(null);
  const [form] = Form.useForm<{ title: string; summary?: string; content: string }>();

  const kbQuery = useQuery({
    queryKey: ["knowledge-base", id],
    queryFn: () => getKnowledgeBase(id),
    enabled: Boolean(id),
  });

  const docsQuery = useQuery({
    queryKey: ["documents", id, page],
    queryFn: () => listDocuments(id, page, 20),
    enabled: Boolean(id),
  });

  const detailQuery = useQuery({
    queryKey: ["document", id, viewDocId],
    queryFn: () => getDocument(id, viewDocId!),
    enabled: Boolean(id && viewDocId && viewerOpen),
  });

  // 用户主动请求编辑详情；Query 缓存负责资源，mutation 负责这次打开动作的加载/失败反馈。
  const editLoad = useMutation({
    mutationFn: (docId: string) =>
      qc.fetchQuery({
        queryKey: ["document", id, docId],
        queryFn: () => getDocument(id, docId),
        staleTime: 0,
      }),
    onSuccess: (full) => {
      setEditingId(full.id);
      form.setFieldsValue({
        title: full.title,
        summary: full.summary ?? "",
        content: full.content,
      });
      setEditorOpen(true);
    },
    onError: (error) => message.error(getUserFacingError(error)),
  });

  const saveMut = useMutation({
    mutationFn: async (values: { title: string; summary?: string; content: string }) => {
      const title = docTitleField.parse(values.title);
      const content = docContentField.parse(values.content);
      const summary = values.summary?.trim() || undefined;
      if (summary) docSummaryField.parse(summary);
      if (editingId) {
        return updateDocument(id, editingId, {
          title,
          content,
          summary: summary ?? null,
        });
      }
      return createDocument(id, { title, content, summary });
    },
    onSuccess: async () => {
      message.success(i18n.common.success);
      setEditorOpen(false);
      setEditingId(null);
      form.resetFields();
      await qc.invalidateQueries({ queryKey: ["documents", id] });
      await qc.invalidateQueries({ queryKey: ["document", id] });
    },
    onError: (e: Error) => message.error(getUserFacingError(e)),
  });

  const deleteMut = useMutation({
    mutationFn: (docId: string) => deleteDocument(id, docId),
    onError: (error) => message.error(getUserFacingError(error)),
    onSuccess: async () => {
      message.success(i18n.common.success);
      if (page > 0 && docsQuery.data?.items.length === 1) setPage(page - 1);
      await qc.invalidateQueries({ queryKey: ["documents", id] });
      await qc.invalidateQueries({ queryKey: ["document", id] });
    },
  });

  return (
    <div className={styles.page}>
      <Space direction="vertical" size="large" style={{ width: "100%" }}>
        <PageState
          onRetry={() => void kbQuery.refetch()}
          loading={kbQuery.isLoading}
          error={kbQuery.isError ? getUserFacingError(kbQuery.error) : null}
        >
          <Card>
            <div className={styles.header}>
              <div>
                <Typography.Title level={3} style={{ margin: 0 }}>
                  {kbQuery.data?.name}
                </Typography.Title>
                <Typography.Paragraph type="secondary">
                  {kbQuery.data?.description || "—"}
                </Typography.Paragraph>
              </div>
              <Space wrap>
                {appEnv.enableKbMembership && kbQuery.data?.creatorId === user?.userId ? (
                  <Link to={`/knowledge-bases/${id}/members`}>
                    <Button icon={<TeamOutlined />}>{i18n.kb.members}</Button>
                  </Link>
                ) : null}
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => {
                    setEditingId(null);
                    form.resetFields();
                    setEditorOpen(true);
                  }}
                >
                  {i18n.kb.createDoc}
                </Button>
              </Space>
            </div>
          </Card>
        </PageState>

        <Card title={i18n.kb.documents}>
          <PageState
            loading={docsQuery.isLoading}
            error={docsQuery.isError ? getUserFacingError(docsQuery.error) : null}
            onRetry={() => void docsQuery.refetch()}
            empty={!docsQuery.isLoading && (docsQuery.data?.items.length ?? 0) === 0}
            emptyDescription={i18n.kb.emptyDocs}
          >
            <List
              dataSource={docsQuery.data?.items ?? []}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    <Button
                      key="view"
                      type="link"
                      onClick={() => {
                        setViewDocId(item.id);
                        setViewerOpen(true);
                      }}
                    >
                      查看
                    </Button>,
                    <Button
                      key="edit"
                      type="link"
                      disabled={editLoad.isPending}
                      loading={editLoad.isPending && editLoad.variables === item.id}
                      onClick={() => editLoad.mutate(item.id)}
                    >
                      {i18n.common.edit}
                    </Button>,
                    <ConfirmDeleteButton
                      key="del"
                      title={i18n.kb.deleteDocConfirm}
                      onConfirm={() => deleteMut.mutate(item.id)}
                      loading={deleteMut.isPending}
                    />,
                  ]}
                >
                  <List.Item.Meta
                    title={item.title}
                    description={item.summary || new Date(item.updatedAt).toLocaleString()}
                  />
                </List.Item>
              )}
            />
            {(docsQuery.data?.totalPages ?? 0) > 1 ? (
              <div className={styles.pager}>
                <Button disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
                  上一页
                </Button>
                <Typography.Text>
                  {page + 1} / {docsQuery.data?.totalPages}
                </Typography.Text>
                <Button
                  disabled={page + 1 >= (docsQuery.data?.totalPages ?? 1)}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </PageState>
        </Card>
      </Space>

      <Modal
        title={editingId ? i18n.kb.editDoc : i18n.kb.createDoc}
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMut.isPending}
        width={720}
        destroyOnHidden
        okText={i18n.common.save}
        cancelText={i18n.common.cancel}
      >
        <Form form={form} layout="vertical" onFinish={(v) => saveMut.mutate(v)}>
          <Form.Item name="title" label={i18n.kb.docTitle} rules={[zodFormRule(docTitleField)]}>
            <Input count={{ show: true, max: 100, strategy: unicodeLength }} showCount />
          </Form.Item>
          <Form.Item name="summary" label={i18n.kb.summary} rules={[zodFormRule(docSummaryField)]}>
            <Input.TextArea
              count={{ show: true, max: 500, strategy: unicodeLength }}
              showCount
              rows={2}
            />
          </Form.Item>
          <Form.Item name="content" label={i18n.kb.content} rules={[zodFormRule(docContentField)]}>
            <Input.TextArea rows={10} count={{ show: true, max: 50000, strategy: unicodeLength }} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={detailQuery.data?.title ?? i18n.kb.documents}
        open={viewerOpen}
        onClose={() => {
          setViewerOpen(false);
          setViewDocId(null);
        }}
        width={560}
      >
        <PageState
          onRetry={() => void detailQuery.refetch()}
          loading={detailQuery.isLoading}
          error={detailQuery.isError ? getUserFacingError(detailQuery.error) : null}
        >
          {detailQuery.data?.summary ? (
            <Typography.Paragraph type="secondary">{detailQuery.data.summary}</Typography.Paragraph>
          ) : null}
          <Typography.Paragraph style={{ whiteSpace: "pre-wrap" }}>
            {detailQuery.data?.content}
          </Typography.Paragraph>
        </PageState>
      </Drawer>
    </div>
  );
}
