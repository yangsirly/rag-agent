import { useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { Grid, Typography, App } from "antd";
import { ConversationSidebar } from "@/features/chat/components/ConversationSidebar";
import { MessageList, type LocalBubble } from "@/features/chat/components/MessageList";
import { Composer } from "@/features/chat/components/Composer";
import { useMessages, useSendMessage } from "@/features/chat/hooks/useMessages";
import { createClientMessageId } from "@/shared/lib/id";
import { PageState } from "@/shared/ui/PageState";
import { t } from "@/shared/i18n";
import { useRetryAfter } from "@/shared/hooks/useRetryAfter";
import { getUserFacingError } from "@/shared/api/errors";
import styles from "./chat.module.css";

/**
 * 发送消息：用户主动发送时生成一次 clientMessageId；
 * 超时/失败重试必须复用，不能重新 randomUUID。
 * 学习笔记：docs/learning/milestone-frontend-phase1.md#clientMessageId-重试
 */
export function ChatPage() {
  const i18n = t();
  const { message } = App.useApp();
  const { conversationId } = useParams();
  const screens = Grid.useBreakpoint();
  const isMobile = !screens.md;
  const messagesQuery = useMessages(conversationId);
  const sendMut = useSendMessage(conversationId);

  // 按会话隔离本地发送态，切换会话时自动隔离，无需 effect 重置
  const [pendingMap, setPendingMap] = useState<Record<string, LocalBubble[]>>({});
  const pending = conversationId ? (pendingMap[conversationId] ?? []) : [];
  const activeSends = useRef(new Set<string>());
  const cooldown = useRetryAfter();
  const sending = pending.some((bubble) => bubble.status === "sending");
  const messages = messagesQuery.data?.messages ?? [];

  const errorText = useMemo(() => {
    if (!messagesQuery.isError) return null;
    return getUserFacingError(messagesQuery.error);
  }, [messagesQuery.isError, messagesQuery.error]);

  const doSend = async (clientMessageId: string, content: string) => {
    if (!conversationId || activeSends.current.has(conversationId) || cooldown.seconds) return;
    const target = conversationId;
    activeSends.current.add(target);
    const bubble: LocalBubble = {
      key: "pending-" + clientMessageId,
      clientMessageId,
      role: "USER",
      content,
      status: "sending",
    };
    const update = (next: LocalBubble | null) =>
      setPendingMap((previous) => {
        const items = previous[target] ?? [];
        const exists = items.some((item) => item.key === bubble.key);
        return {
          ...previous,
          [target]: next
            ? exists
              ? items.map((item) => (item.key === bubble.key ? next : item))
              : [...items, next]
            : items.filter((item) => item.key !== bubble.key),
        };
      });
    update(bubble);
    try {
      await sendMut.mutateAsync({ clientMessageId, content });
      update(null);
    } catch (error) {
      cooldown.start(error);
      message.error(getUserFacingError(error));
      update({ ...bubble, status: "failed", error: getUserFacingError(error) });
    } finally {
      activeSends.current.delete(target);
    }
  };

  const onSend = (content: string) => {
    // 新消息：生成新 ID。若上一笔仍在发送中则忽略
    if (sending) return;
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
            {isMobile ? (
              <ConversationSidebar />
            ) : (
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
                pending={pending.map((bubble) => ({
                  ...bubble,
                  onRetry: () => void doSend(bubble.clientMessageId!, bubble.content),
                }))}
                retryDisabled={sending || cooldown.seconds > 0}
                hasOlder={messagesQuery.data?.hasOlder}
                loadingOlder={messagesQuery.isFetchingNextPage}
                onLoadOlder={() => void messagesQuery.fetchNextPage()}
              />
            </PageState>
            <Composer
              disabled={!conversationId || sending}
              sending={sending}
              cooldown={cooldown.seconds}
              onSend={onSend}
            />
          </>
        )}
      </section>
    </div>
  );
}
