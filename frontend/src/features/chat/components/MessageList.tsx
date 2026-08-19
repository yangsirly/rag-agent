import { Button, Space, Tag, Typography } from "antd";
import type { Message } from "@/shared/api/schemas";
import { t } from "@/shared/i18n";
import styles from "../pages/chat.module.css";

export type LocalBubble = {
  key: string;
  role: "USER" | "ASSISTANT";
  content: string;
  status?: "sending" | "failed" | "done";
  onRetry?: () => void;
};

type Props = {
  messages: Message[];
  pending?: LocalBubble | null;
  hasOlder?: boolean;
  loadingOlder?: boolean;
  onLoadOlder?: () => void;
};

export function MessageList({
  messages,
  pending,
  hasOlder,
  loadingOlder,
  onLoadOlder,
}: Props) {
  const i18n = t();
  const bubbles: LocalBubble[] = messages.map((m) => ({
    key: m.id,
    role: m.role,
    content: m.content,
    status: "done",
  }));
  if (pending) bubbles.push(pending);

  return (
    <div className={styles.messageList}>
      <div className={styles.loadOlder}>
        {hasOlder ? (
          <Button size="small" onClick={onLoadOlder} loading={loadingOlder}>
            {i18n.chat.loadOlder}
          </Button>
        ) : messages.length > 0 ? (
          <Typography.Text type="secondary">{i18n.chat.noMore}</Typography.Text>
        ) : null}
      </div>
      {bubbles.length === 0 ? (
        <div className={styles.emptyMessages}>
          <Typography.Text type="secondary">{i18n.chat.emptyMessages}</Typography.Text>
        </div>
      ) : (
        bubbles.map((b) => (
          <div
            key={b.key}
            className={`${styles.bubble} ${b.role === "USER" ? styles.user : styles.assistant}`}
          >
            <div className={styles.bubbleMeta}>
              <Tag color={b.role === "USER" ? "blue" : "purple"}>
                {b.role === "USER" ? "我" : "助手"}
              </Tag>
              {b.status === "sending" ? <Tag>{i18n.chat.sending}</Tag> : null}
              {b.status === "failed" ? <Tag color="error">{i18n.chat.sendFailed}</Tag> : null}
            </div>
            <div className={styles.bubbleContent}>{b.content}</div>
            {b.status === "failed" && b.onRetry ? (
              <Space style={{ marginTop: 8 }}>
                <Button size="small" type="primary" onClick={b.onRetry}>
                  {i18n.common.retry}
                </Button>
              </Space>
            ) : null}
          </div>
        ))
      )}
    </div>
  );
}
