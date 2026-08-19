import { z } from "zod";
import { unicodeLength, trimmedUnicodeLength } from "./unicode";

const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

export const emailField = z
  .string()
  .trim()
  .min(1, "请输入邮箱")
  .max(254, "邮箱过长")
  .regex(emailRegex, "邮箱格式不正确")
  .transform(normalizeEmail);

export const passwordField = z
  .string()
  .min(1, "请输入密码")
  .refine((v) => {
    const len = unicodeLength(v);
    return len >= 8 && len <= 64;
  }, "密码须为 8～64 个字符");

export const conversationTitleField = z
  .string()
  .transform((v) => v.trim())
  .refine((v) => {
    const len = unicodeLength(v);
    return len >= 1 && len <= 100;
  }, "标题须为 1～100 字");

export const optionalConversationTitleField = z
  .string()
  .optional()
  .transform((v) => (v === undefined ? undefined : v.trim()))
  .refine((v) => {
    if (v === undefined || v === "") return true;
    const len = unicodeLength(v);
    return len >= 1 && len <= 100;
  }, "标题须为 1～100 字");

export const messageContentField = z.string().refine((v) => {
  // 正文保留原样；仅用 trim 后是否为空判断纯空白
  if (v.trim().length === 0) return false;
  return unicodeLength(v) <= 10000;
}, "消息内容须为 1～10000 字");

export const kbNameField = z
  .string()
  .transform((v) => v.trim())
  .refine((v) => {
    const len = unicodeLength(v);
    return len >= 1 && len <= 100;
  }, "名称须为 1～100 字");

export const kbDescriptionField = z
  .string()
  .max(1000, "描述最长 1000 字")
  .optional()
  .or(z.literal(""));

export const docTitleField = z
  .string()
  .transform((v) => v.trim())
  .refine((v) => {
    const len = unicodeLength(v);
    return len >= 1 && len <= 200;
  }, "标题须为 1～200 字");

export const docSummaryField = z
  .string()
  .refine((v) => unicodeLength(v) <= 500, "摘要最长 500 字")
  .optional()
  .or(z.literal(""));

export const docContentField = z.string().refine((v) => {
  if (v.trim().length === 0) return false;
  return unicodeLength(v) <= 100_000;
}, "正文须为 1～100000 字");

export function assertTrimmedTitle(value: string, max: number): boolean {
  return trimmedUnicodeLength(value) >= 1 && trimmedUnicodeLength(value) <= max;
}
