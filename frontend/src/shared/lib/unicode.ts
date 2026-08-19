/**
 * 按 Unicode 码点计算长度，避免 surrogate pair 被 JS string.length 算成 2。
 * 与后端 v0.2「密码 8～64 个 Unicode 码点」对齐。
 * 学习笔记：docs/learning/milestone-frontend-phase1.md#unicode-长度
 */
export function unicodeLength(value: string): number {
  return [...value].length;
}

export function isWithinUnicodeLength(
  value: string,
  min: number,
  max: number,
): boolean {
  const len = unicodeLength(value);
  return len >= min && len <= max;
}

/** 名称/标题：trim 后按码点校验 */
export function trimmedUnicodeLength(value: string): number {
  return unicodeLength(value.trim());
}
