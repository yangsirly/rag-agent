import { requestAndParse, requestNoContent } from "@/shared/api/client";
import {
  ConversationListSchema,
  ConversationResponseSchema,
  MessageListSchema,
  SendMessageResponseSchema,
  type Conversation,
  type Message,
  type SendMessageResponse,
} from "@/shared/api/schemas";

export async function listConversations(page = 0, size = 20) {
  return requestAndParse(
    { method: "GET", url: "/conversations", params: { page, size } },
    ConversationListSchema,
  );
}

export async function createConversation(title?: string): Promise<Conversation> {
  return requestAndParse(
    {
      method: "POST",
      url: "/conversations",
      data: title ? { title } : {},
    },
    ConversationResponseSchema,
  );
}

export async function getConversation(id: string): Promise<Conversation> {
  return requestAndParse(
    { method: "GET", url: `/conversations/${id}` },
    ConversationResponseSchema,
  );
}

export async function renameConversation(id: string, title: string): Promise<Conversation> {
  return requestAndParse(
    { method: "PATCH", url: `/conversations/${id}`, data: { title } },
    ConversationResponseSchema,
  );
}

export async function deleteConversation(id: string): Promise<void> {
  await requestNoContent({ method: "DELETE", url: `/conversations/${id}` });
}

export async function listMessages(conversationId: string, page = 0, size = 50) {
  return requestAndParse(
    {
      method: "GET",
      url: `/conversations/${conversationId}/messages`,
      params: { page, size },
    },
    MessageListSchema,
  );
}

export async function sendMessage(
  conversationId: string,
  clientMessageId: string,
  content: string,
): Promise<SendMessageResponse> {
  return requestAndParse(
    {
      method: "POST",
      url: `/conversations/${conversationId}/messages`,
      data: { clientMessageId, content },
    },
    SendMessageResponseSchema,
  );
}

/** 稳定排序：createdAt ASC, id ASC */
export function sortMessages(messages: Message[]): Message[] {
  return [...messages].sort((a, b) => {
    const t = a.createdAt.localeCompare(b.createdAt);
    if (t !== 0) return t;
    return a.id.localeCompare(b.id, undefined, { numeric: true });
  });
}

/** 合并消息页：去重后稳定排序 */
export function mergeMessagePages(pages: Message[][]): Message[] {
  const map = new Map<string, Message>();
  for (const page of pages) {
    for (const m of page) map.set(m.id, m);
  }
  return sortMessages([...map.values()]);
}
