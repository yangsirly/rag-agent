import { Button, Card, Form, Input, List, Typography, message } from "antd";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { getKnowledgeBase, grantMember, listMembers, revokeMember } from "@/features/knowledge-base/api";
import { appEnv } from "@/shared/lib/env";
import { emailField } from "@/shared/lib/validation";
import { ConfirmDeleteButton } from "@/shared/ui/ConfirmDeleteButton";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import styles from "./kb.module.css";

export function MembersPage() {
  const i18n = t();
  const { id = "" } = useParams();
  const qc = useQueryClient();
  const [form] = Form.useForm<{ email: string }>();

  const kbQuery = useQuery({
    queryKey: ["knowledge-base", id],
    queryFn: () => getKnowledgeBase(id),
    enabled: Boolean(id) && appEnv.enableKbMembership,
  });

  const membersQuery = useQuery({
    queryKey: ["members", id],
    queryFn: () => listMembers(id),
    enabled: Boolean(id) && appEnv.enableKbMembership,
  });

  const grantMut = useMutation({
    mutationFn: (email: string) => grantMember(id, email),
    onSuccess: async () => {
      message.success(i18n.common.success);
      form.resetFields();
      await qc.invalidateQueries({ queryKey: ["members", id] });
    },
    onError: (e: Error) => message.error(e.message || i18n.common.unknownError),
  });

  const revokeMut = useMutation({
    mutationFn: (userId: string) => revokeMember(id, userId),
    onSuccess: async () => {
      message.success(i18n.common.success);
      await qc.invalidateQueries({ queryKey: ["members", id] });
    },
  });

  if (!appEnv.enableKbMembership) {
    return (
      <div className={styles.page}>
        <Card>
          <Typography.Text>{i18n.kb.membershipDisabled}</Typography.Text>
        </Card>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <Card title={`${i18n.kb.members} · ${kbQuery.data?.name ?? ""}`}>
        <Form
          form={form}
          layout="inline"
          onFinish={(v) => {
            const email = emailField.parse(v.email);
            grantMut.mutate(email);
          }}
          style={{ marginBottom: 16 }}
        >
          <Form.Item name="email" rules={[{ required: true, message: i18n.common.required }]}>
            <Input placeholder={i18n.kb.memberEmail} style={{ width: 280 }} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={grantMut.isPending}>
            {i18n.kb.grant}
          </Button>
        </Form>

        <PageState
          loading={membersQuery.isLoading}
          error={membersQuery.isError ? i18n.common.unknownError : null}
          onRetry={() => void membersQuery.refetch()}
          empty={!membersQuery.isLoading && (membersQuery.data?.items.length ?? 0) === 0}
          emptyDescription={i18n.kb.emptyMembers}
        >
          <List
            dataSource={membersQuery.data?.items ?? []}
            renderItem={(item) => (
              <List.Item
                actions={[
                  <ConfirmDeleteButton
                    key="rev"
                    title={i18n.kb.revokeConfirm}
                    label={i18n.kb.revoke}
                    onConfirm={() => revokeMut.mutateAsync(item.userId)}
                  />,
                ]}
              >
                <List.Item.Meta
                  title={item.email}
                  description={`permission=${item.permission} · userId=${item.userId}`}
                />
              </List.Item>
            )}
          />
        </PageState>
      </Card>
    </div>
  );
}
