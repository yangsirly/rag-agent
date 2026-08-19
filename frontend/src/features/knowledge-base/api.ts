import { requestAndParse, requestNoContent } from "@/shared/api/client";
import {
  DocumentListSchema,
  DocumentResponseSchema,
  KnowledgeBaseListSchema,
  KnowledgeBaseResponseSchema,
  MemberListSchema,
  MemberResponseSchema,
  type Document,
  type KnowledgeBase,
  type Member,
} from "@/shared/api/schemas";

export async function listKnowledgeBases(page = 0, size = 20) {
  return requestAndParse(
    { method: "GET", url: "/knowledge-bases", params: { page, size } },
    KnowledgeBaseListSchema,
  );
}

export async function createKnowledgeBase(name: string, description?: string): Promise<KnowledgeBase> {
  return requestAndParse(
    {
      method: "POST",
      url: "/knowledge-bases",
      data: { name, description: description || null },
    },
    KnowledgeBaseResponseSchema,
  );
}

export async function getKnowledgeBase(id: string): Promise<KnowledgeBase> {
  return requestAndParse(
    { method: "GET", url: `/knowledge-bases/${id}` },
    KnowledgeBaseResponseSchema,
  );
}

export async function updateKnowledgeBase(
  id: string,
  data: { name?: string; description?: string | null },
): Promise<KnowledgeBase> {
  return requestAndParse(
    { method: "PATCH", url: `/knowledge-bases/${id}`, data },
    KnowledgeBaseResponseSchema,
  );
}

export async function deleteKnowledgeBase(id: string): Promise<void> {
  await requestNoContent({ method: "DELETE", url: `/knowledge-bases/${id}` });
}

export async function listDocuments(kbId: string, page = 0, size = 20) {
  return requestAndParse(
    {
      method: "GET",
      url: `/knowledge-bases/${kbId}/documents`,
      params: { page, size },
    },
    DocumentListSchema,
  );
}

export async function getDocument(kbId: string, docId: string): Promise<Document> {
  return requestAndParse(
    { method: "GET", url: `/knowledge-bases/${kbId}/documents/${docId}` },
    DocumentResponseSchema,
  );
}

export async function createDocument(
  kbId: string,
  data: { title: string; summary?: string; content: string },
): Promise<Document> {
  return requestAndParse(
    { method: "POST", url: `/knowledge-bases/${kbId}/documents`, data },
    DocumentResponseSchema,
  );
}

export async function updateDocument(
  kbId: string,
  docId: string,
  data: { title?: string; summary?: string | null; content?: string },
): Promise<Document> {
  return requestAndParse(
    { method: "PATCH", url: `/knowledge-bases/${kbId}/documents/${docId}`, data },
    DocumentResponseSchema,
  );
}

export async function deleteDocument(kbId: string, docId: string): Promise<void> {
  await requestNoContent({
    method: "DELETE",
    url: `/knowledge-bases/${kbId}/documents/${docId}`,
  });
}

export async function listMembers(kbId: string) {
  return requestAndParse(
    { method: "GET", url: `/knowledge-bases/${kbId}/members` },
    MemberListSchema,
  );
}

export async function grantMember(kbId: string, email: string): Promise<Member> {
  return requestAndParse(
    { method: "POST", url: `/knowledge-bases/${kbId}/members`, data: { email } },
    MemberResponseSchema,
  );
}

export async function revokeMember(kbId: string, userId: string): Promise<void> {
  await requestNoContent({
    method: "DELETE",
    url: `/knowledge-bases/${kbId}/members/${userId}`,
  });
}
