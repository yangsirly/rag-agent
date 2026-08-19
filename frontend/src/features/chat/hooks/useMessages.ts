import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { listMessages, mergeMessagePages, sendMessage } from "@/features/chat/api";
import type { Message } from "@/shared/api/schemas";

export function useMessages(conversationId: string | undefined) {
  return useInfiniteQuery({
    queryKey: ["messages", conversationId],
    enabled: Boolean(conversationId),
    queryFn: ({ pageParam }) => listMessages(conversationId!, pageParam, 50),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      // page=0 最新；page=1 更早。加载旧页 = 增大 page
      if (last.page + 1 >= last.totalPages) return undefined;
      return last.page + 1;
    },
    select: (data) => {
      // pages[0] 是最新页；合并后按时间升序渲染
      const merged = mergeMessagePages(data.pages.map((p) => p.items));
      return {
        messages: merged,
        pageParams: data.pageParams,
        pages: data.pages,
        hasOlder: data.pages[data.pages.length - 1]
          ? data.pages[data.pages.length - 1].page + 1 <
            data.pages[data.pages.length - 1].totalPages
          : false,
      };
    },
  });
}

export type PendingSend = {
  clientMessageId: string;
  content: string;
  status: "sending" | "failed";
};

export function useSendMessage(conversationId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      clientMessageId,
      content,
    }: {
      clientMessageId: string;
      content: string;
    }) => {
      if (!conversationId) throw new Error("missing conversationId");
      // 重试必须复用同一 clientMessageId（由调用方保证）
      return sendMessage(conversationId, clientMessageId, content);
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ["messages", conversationId] });
      await qc.invalidateQueries({ queryKey: ["conversations"] });
    },
  });
}

export function toOptimisticUserMessage(
  conversationId: string,
  clientMessageId: string,
  content: string,
): Message {
  return {
    id: `pending-${clientMessageId}`,
    conversationId,
    clientMessageId,
    role: "USER",
    content,
    createdAt: new Date().toISOString(),
  };
}
