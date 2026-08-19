import { z } from "zod";

/** 后端 JSON 中 ID 统一为十进制字符串，避免超过 Number.MAX_SAFE_INTEGER */
export const IdSchema = z.string().regex(/^\d+$/, "id must be decimal string");

export const RoleSchema = z.enum(["CUSTOMER", "EDITOR"]);
export type Role = z.infer<typeof RoleSchema>;

export const MessageRoleSchema = z.enum(["USER", "ASSISTANT"]);
export type MessageRole = z.infer<typeof MessageRoleSchema>;

export const ApiErrorSchema = z.object({
  statusCode: z.number(),
  code: z.string(),
  message: z.string(),
});
export type ApiErrorBody = z.infer<typeof ApiErrorSchema>;

export function pageResponseSchema<T extends z.ZodType>(itemSchema: T) {
  return z.object({
    statusCode: z.number(),
    items: z.array(itemSchema),
    page: z.number(),
    size: z.number(),
    totalElements: z.number(),
    totalPages: z.number(),
  });
}

export const StatusOnlySchema = z.object({
  statusCode: z.number(),
});

export const LoginResponseSchema = z.object({
  statusCode: z.number(),
  role: RoleSchema,
});
export type LoginResponse = z.infer<typeof LoginResponseSchema>;

export const MeResponseSchema = z.object({
  statusCode: z.number(),
  userId: IdSchema,
  email: z.string().email(),
  role: RoleSchema,
});
export type MeResponse = z.infer<typeof MeResponseSchema>;

export const ConversationSchema = z.object({
  statusCode: z.number().optional(),
  id: IdSchema,
  title: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Conversation = z.infer<typeof ConversationSchema>;

export const ConversationResponseSchema = ConversationSchema.extend({
  statusCode: z.number(),
});

export const ConversationListSchema = pageResponseSchema(
  ConversationSchema.omit({ statusCode: true }),
);

export const MessageSchema = z.object({
  id: IdSchema,
  conversationId: IdSchema,
  clientMessageId: z
    .string()
    .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    .optional(),
  replyToMessageId: IdSchema.optional(),
  role: MessageRoleSchema,
  content: z.string(),
  createdAt: z.string(),
});
export type Message = z.infer<typeof MessageSchema>;

export const SendMessageResponseSchema = z.object({
  statusCode: z.number(),
  userMessage: MessageSchema,
  assistantMessage: MessageSchema,
});
export type SendMessageResponse = z.infer<typeof SendMessageResponseSchema>;

export const MessageListSchema = pageResponseSchema(MessageSchema);

export const KnowledgeBaseSchema = z.object({
  statusCode: z.number().optional(),
  id: IdSchema,
  name: z.string(),
  description: z.string().nullable().optional(),
  creatorId: IdSchema.optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type KnowledgeBase = z.infer<typeof KnowledgeBaseSchema>;

export const KnowledgeBaseResponseSchema = KnowledgeBaseSchema.extend({
  statusCode: z.number(),
});

export const KnowledgeBaseListSchema = pageResponseSchema(
  KnowledgeBaseSchema.omit({ statusCode: true }),
);

export const DocumentListItemSchema = z.object({
  id: IdSchema,
  knowledgeBaseId: IdSchema,
  title: z.string(),
  summary: z.string().nullable().optional(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type DocumentListItem = z.infer<typeof DocumentListItemSchema>;

export const DocumentSchema = DocumentListItemSchema.extend({
  statusCode: z.number().optional(),
  creatorId: IdSchema.optional(),
  content: z.string(),
});
export type Document = z.infer<typeof DocumentSchema>;

export const DocumentResponseSchema = DocumentSchema.extend({
  statusCode: z.number(),
});

export const DocumentListSchema = pageResponseSchema(DocumentListItemSchema);

export const MemberSchema = z.object({
  id: IdSchema.optional(),
  knowledgeBaseId: IdSchema.optional(),
  userId: IdSchema,
  email: z.string().email(),
  permission: z.literal("EDIT"),
  grantedBy: IdSchema.optional(),
  grantedAt: z.string().optional(),
  createdAt: z.string().optional(),
});
export type Member = z.infer<typeof MemberSchema>;

export const MemberResponseSchema = MemberSchema.extend({
  statusCode: z.number(),
  knowledgeBaseId: IdSchema,
  id: IdSchema,
  grantedBy: IdSchema,
  createdAt: z.string(),
});

export const MemberListSchema = z.object({
  statusCode: z.number(),
  items: z.array(MemberSchema),
});
