import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  createConversation,
  deleteConversation,
  listConversations,
  renameConversation,
} from "@/features/chat/api";

export function useConversations() {
  return useInfiniteQuery({
    queryKey: ["conversations"],
    queryFn: ({ pageParam }) => listConversations(pageParam, 20),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      if (last.page + 1 >= last.totalPages) return undefined;
      return last.page + 1;
    },
  });
}

export function useCreateConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (title?: string) => createConversation(title),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ["conversations"] });
    },
  });
}

export function useRenameConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => renameConversation(id, title),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ["conversations"] });
    },
  });
}

export function useDeleteConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteConversation(id),
    onSuccess: async (_data, id) => {
      await qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.removeQueries({ queryKey: ["messages", id] });
    },
  });
}
