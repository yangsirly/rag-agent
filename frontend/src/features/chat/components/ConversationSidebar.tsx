import { Button, Input, List, Modal, Typography, message } from "antd";
import { EditOutlined, PlusOutlined } from "@ant-design/icons";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { Conversation } from "@/shared/api/schemas";
import { ConfirmDeleteButton } from "@/shared/ui/ConfirmDeleteButton";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import {
  useConversations,
  useCreateConversation,
  useDeleteConversation,
  useRenameConversation,
} from "@/features/chat/hooks/useConversations";
import styles from "../pages/chat.module.css";

type Props = {
  activeId?: string;
  onNavigate?: () => void;
};

export function ConversationSidebar({ activeId, onNavigate }: Props) {
  const i18n = t();
  const navigate = useNavigate();
  const listQuery = useConversations();
  const createMut = useCreateConversation();
  const renameMut = useRenameConversation();
  const deleteMut = useDeleteConversation();
  const [renameTarget, setRenameTarget] = useState<Conversation | null>(null);
  const [renameValue, setRenameValue] = useState("");

  const items = useMemo(() => {
    const pages = listQuery.data?.pages ?? [];
    const map = new Map<string, Conversation>();
    for (const p of pages) {
      for (const c of p.items) map.set(c.id, c);
    }
    return [...map.values()];
  }, [listQuery.data]);

  const onCreate = async () => {
    const created = await createMut.mutateAsync(undefined);
    navigate(`/chat/${created.id}`);
    onNavigate?.();
  };

  const onConfirmRename = async () => {
    if (!renameTarget) return;
    const title = renameValue.trim();
    if (!title || title.length > 100) {
      message.error(i18n.chat.titleLength);
      return;
    }
    await renameMut.mutateAsync({ id: renameTarget.id, title });
    setRenameTarget(null);
  };

  return (
    <div className={styles.sidebar}>
      <div className={styles.sidebarHeader}>
        <Typography.Text strong>{i18n.chat.title}</Typography.Text>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => void onCreate()}
          loading={createMut.isPending}
        >
          {i18n.chat.newConversation}
        </Button>
      </div>
      <PageState
        loading={listQuery.isLoading}
        error={listQuery.isError ? i18n.common.unknownError : null}
        onRetry={() => void listQuery.refetch()}
        empty={!listQuery.isLoading && items.length === 0}
        emptyDescription={i18n.chat.emptyConversations}
      >
        <List
          className={styles.conversationList}
          dataSource={items}
          renderItem={(item) => (
            <List.Item
              className={`${styles.conversationItem} ${item.id === activeId ? styles.active : ""}`}
              onClick={() => {
                navigate(`/chat/${item.id}`);
                onNavigate?.();
              }}
              actions={[
                <Button
                  key="edit"
                  size="small"
                  type="text"
                  icon={<EditOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    setRenameTarget(item);
                    setRenameValue(item.title);
                  }}
                />,
                <span key="del" onClick={(e) => e.stopPropagation()}>
                  <ConfirmDeleteButton
                    title={i18n.chat.deleteConfirm}
                    loading={deleteMut.isPending}
                    onConfirm={async () => {
                      await deleteMut.mutateAsync(item.id);
                      if (activeId === item.id) navigate("/chat");
                    }}
                  />
                </span>,
              ]}
            >
              <List.Item.Meta
                title={item.title}
                description={new Date(item.updatedAt).toLocaleString()}
              />
            </List.Item>
          )}
        />
        {listQuery.hasNextPage ? (
          <div className={styles.loadMore}>
            <Button onClick={() => void listQuery.fetchNextPage()} loading={listQuery.isFetchingNextPage}>
              加载更多会话
            </Button>
          </div>
        ) : null}
      </PageState>

      <Modal
        title={i18n.chat.rename}
        open={Boolean(renameTarget)}
        onOk={() => void onConfirmRename()}
        onCancel={() => setRenameTarget(null)}
        confirmLoading={renameMut.isPending}
        okText={i18n.common.save}
        cancelText={i18n.common.cancel}
      >
        <Input
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          maxLength={100}
          showCount
        />
      </Modal>
    </div>
  );
}
