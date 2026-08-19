import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { Grid, Typography } from "antd";
import { ConversationSidebar } from "@/features/chat/components/ConversationSidebar";
import { MessageList, type LocalBubble } from "@/features/chat/components/MessageList";
import { Composer } from "@/features/chat/components/Composer";
import { useMessages, useSendMessage } from "@/features/chat/hooks/useMessages";
import { createClientMessageId } from "@/shared/lib/id";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import { AppApiError } from "@/shared/api/errors";
import styles from "./chat.module.css";

/**
 * 发送消息：用户主动发送时生成一次 clientMessageId；
 * 超时/失败重试必须复用，不能重新 randomUUID。
 * 学习笔记：docs/learning/milestone-frontend-phase1.md#clientMessageId-重试
 */
export function ChatPage() {
  const i18n = t();
  const { conversationId } = useParams();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;
  const messagesQuery = useMessages(conversationId);
  const sendMut = useSendMessage(conversationId);

  // 按会话隔离本地发送态，切换会话时自动隔离，无需 effect 重置
  const [pendingMap, setPendingMap] = useState<Record<string, LocalBubble | null>>({});
  const pending = conversationId ? (pendingMap[conversationId] ?? null) : null;

  const setPendingFor = (bubble: LocalBubble | null) => {
    if (!conversationId) return;
    setPendingMap((prev) => ({ ...prev, [conversationId]: bubble }));
  };

  const messages = messagesQuery.data?.messages ?? [];

  const errorText = useMemo(() => {
    if (!messagesQuery.isError) return null;
    const err = messagesQuery.error;
    if (err instanceof AppApiError) return err.message;
    return i18n.common.unknownError;
  }, [messagesQuery.isError, messagesQuery.error, i18n.common.unknownError]);

  const doSend = async (clientMessageId: string, content: string) => {
    if (!conversationId) return;
    setPendingFor({
      key: `pending-${clientMessageId}`,
      role: "USER",
      content,
      status: "sending",
      onRetry: () => void doSend(clientMessageId, content),
    });
    try {
      await sendMut.mutateAsync({ clientMessageId, content });
      setPendingFor(null);
    } catch {
      setPendingFor({
        key: `pending-${clientMessageId}`,
        role: "USER",
        content,
        status: "failed",
        onRetry: () => void doSend(clientMessageId, content),
      });
    }
  };

  const onSend = (content: string) => {
    // 新消息：生成新 ID。若上一笔仍在发送中则忽略
    if (pending?.status === "sending") return;
    const id = createClientMessageId();
    void doSend(id, content);
  };

  return (
    <div className={styles.chatLayout}>
      {!isMobile ? (
        <aside className={styles.sidebarPane}>
          <ConversationSidebar activeId={conversationId} />
        </aside>
      ) : null}
      <section className={styles.mainPane}>
        {!conversationId ? (
          <div className={styles.placeholder}>
            {isMobile ? <ConversationSidebar /> : (
              <Typography.Text type="secondary">{i18n.chat.emptyMessages}</Typography.Text>
            )}
          </div>
        ) : (
          <>
            <PageState
              loading={messagesQuery.isLoading}
              error={errorText}
              onRetry={() => void messagesQuery.refetch()}
            >
              <MessageList
                messages={messages}
                pending={pending}
                hasOlder={messagesQuery.data?.hasOlder}
                loadingOlder={messagesQuery.isFetchingNextPage}
                onLoadOlder={() => void messagesQuery.fetchNextPage()}
              />
            </PageState>
            <Composer
              disabled={!conversationId || pending?.status === "sending"}
              sending={pending?.status === "sending"}
              onSend={onSend}
            />
          </>
        )}
      </section>
    </div>
  );
}
