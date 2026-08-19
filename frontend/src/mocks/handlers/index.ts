import { authHandlers } from "./auth";
import { conversationHandlers } from "./conversations";
import { knowledgeBaseHandlers } from "./knowledge-bases";

export const handlers = [
  ...authHandlers,
  ...conversationHandlers,
  ...knowledgeBaseHandlers,
];
