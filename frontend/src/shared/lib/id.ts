/** 为一次「用户主动发送」生成 clientMessageId；超时重试必须复用，不可重新调用。 */
export function createClientMessageId(): string {
  return crypto.randomUUID();
}

export function createClientRequestId(): string {
  return crypto.randomUUID();
}
