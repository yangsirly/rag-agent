import { Button, Card, Form, Input, List, Modal, Pagination, Typography, message } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  updateKnowledgeBase,
} from "@/features/knowledge-base/api";
import { ConfirmDeleteButton } from "@/shared/ui/ConfirmDeleteButton";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import { kbDescriptionField, kbNameField } from "@/shared/lib/validation";
import { z } from "zod";
import styles from "./kb.module.css";

export function KnowledgeBaseListPage() {
  const i18n = t();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm<{ name: string; description?: string }>();

  const query = useQuery({
    queryKey: ["knowledge-bases", page],
    queryFn: () => listKnowledgeBases(page, 20),
  });

  const saveMut = useMutation({
    mutationFn: async (values: { name: string; description?: string }) => {
      const name = kbNameField.parse(values.name);
      const description = values.description?.trim() || undefined;
      if (editingId) {
        return updateKnowledgeBase(editingId, { name, description: description ?? null });
      }
      return createKnowledgeBase(name, description);
    },
    onSuccess: async () => {
      message.success(i18n.common.success);
      setOpen(false);
      setEditingId(null);
      form.resetFields();
      await qc.invalidateQueries({ queryKey: ["knowledge-bases"] });
    },
    onError: (e: Error) => message.error(e.message || i18n.common.unknownError),
  });

  const deleteMut = useMutation({
    mutationFn: deleteKnowledgeBase,
    onSuccess: async () => {
      message.success(i18n.common.success);
      await qc.invalidateQueries({ queryKey: ["knowledge-bases"] });
    },
  });

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {i18n.kb.title}
        </Typography.Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditingId(null);
            form.resetFields();
            setOpen(true);
          }}
        >
          {i18n.kb.create}
        </Button>
      </div>

      <PageState
        loading={query.isLoading}
        error={query.isError ? i18n.common.unknownError : null}
        onRetry={() => void query.refetch()}
        empty={!query.isLoading && (query.data?.items.length ?? 0) === 0}
        emptyDescription={i18n.kb.empty}
      >
        <List
          grid={{ gutter: 16, xs: 1, sm: 1, md: 2, lg: 3 }}
          dataSource={query.data?.items ?? []}
          renderItem={(item) => (
            <List.Item>
              <Card
                title={<Link to={`/knowledge-bases/${item.id}`}>{item.name}</Link>}
                actions={[
                  <Button
                    key="edit"
                    type="link"
                    onClick={() => {
                      setEditingId(item.id);
                      form.setFieldsValue({
                        name: item.name,
                        description: item.description ?? "",
                      });
                      setOpen(true);
                    }}
                  >
                    {i18n.common.edit}
                  </Button>,
                  <ConfirmDeleteButton
                    key="del"
                    title={i18n.kb.deleteConfirm}
                    onConfirm={() => deleteMut.mutateAsync(item.id)}
                    loading={deleteMut.isPending}
                  />,
                ]}
              >
                <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }}>
                  {item.description || "—"}
                </Typography.Paragraph>
                <Typography.Text type="secondary">
                  {new Date(item.updatedAt).toLocaleString()}
                </Typography.Text>
              </Card>
            </List.Item>
          )}
        />
        <div className={styles.pager}>
          <Pagination
            current={(query.data?.page ?? 0) + 1}
            pageSize={query.data?.size ?? 20}
            total={query.data?.totalElements ?? 0}
            onChange={(p) => setPage(p - 1)}
            showSizeChanger={false}
          />
        </div>
      </PageState>

      <Modal
        title={editingId ? i18n.kb.edit : i18n.kb.create}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMut.isPending}
        okText={i18n.common.save}
        cancelText={i18n.common.cancel}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) => {
            try {
              kbNameField.parse(values.name);
              if (values.description) kbDescriptionField.parse(values.description);
              saveMut.mutate(values);
            } catch (e) {
              if (e instanceof z.ZodError) {
                message.error(e.issues[0]?.message ?? i18n.common.unknownError);
              }
            }
          }}
        >
          <Form.Item name="name" label={i18n.kb.name} rules={[{ required: true, message: i18n.common.required }]}>
            <Input maxLength={100} showCount />
          </Form.Item>
          <Form.Item name="description" label={i18n.kb.description}>
            <Input.TextArea maxLength={1000} showCount rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
