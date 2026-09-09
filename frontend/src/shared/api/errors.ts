import { AxiosError } from "axios";
import { ApiErrorSchema, type ApiErrorBody } from "./schemas";

export class AppApiError extends Error {
  readonly statusCode: number;
  readonly code: string;
  readonly body: ApiErrorBody | null;

  constructor(
    statusCode: number,
    code: string,
    message: string,
    body: ApiErrorBody | null = null,
    readonly retryAfterSeconds?: number,
  ) {
    super(message);
    this.name = "AppApiError";
    this.statusCode = statusCode;
    this.code = code;
    this.body = body;
  }

  get isUnauthorized(): boolean {
    return this.statusCode === 401 && this.code === "UNAUTHORIZED";
  }

  get isInvalidCredentials(): boolean {
    return this.code === "INVALID_CREDENTIALS";
  }

  get isUserDisabled(): boolean {
    return this.code === "USER_DISABLED";
  }

  get isRateLimited() {
    return this.statusCode === 429;
  }
  get isNotFound() {
    return this.statusCode === 404;
  }
  get isConflict() {
    return this.statusCode === 409;
  }
  get isServerError() {
    return this.statusCode >= 500;
  }

  get isForbidden(): boolean {
    return this.statusCode === 403;
  }

  get isNetwork(): boolean {
    return this.code === "NETWORK_ERROR";
  }
}

export class ContractValidationError extends Error {
  readonly issues: string[];
  readonly path: string;
  readonly method: string;

  constructor(path: string, method: string, issues: string[]) {
    super("响应契约校验失败");
    this.name = "ContractValidationError";
    this.path = path;
    this.method = method;
    this.issues = issues;
  }
}

export function toAppApiError(error: unknown): AppApiError {
  if (error instanceof AppApiError) return error;

  if (error instanceof AxiosError) {
    if (!error.response) {
      return new AppApiError(0, "NETWORK_ERROR", "网络异常，请检查连接后重试");
    }
    const status = error.response.status;
    const retryAfter = parseRetryAfter(error.response.headers["retry-after"]);
    const parsed = ApiErrorSchema.safeParse(error.response.data);
    if (parsed.success) {
      return new AppApiError(
        status,
        parsed.data.code,
        parsed.data.message,
        parsed.data,
        retryAfter,
      );
    }
    return new AppApiError(status, "UNKNOWN_ERROR", "请求失败，请稍后重试", null, retryAfter);
  }

  if (error instanceof Error) {
    return new AppApiError(0, "UNKNOWN_ERROR", error.message);
  }

  return new AppApiError(0, "UNKNOWN_ERROR", "未知错误");
}

/** 登录页专用：把服务端错误码映射为表单可见文案 */
export function mapLoginError(error: AppApiError): string {
  if (error.isInvalidCredentials) return "邮箱或密码错误";
  if (error.isUserDisabled) return "账号已禁用，无法登录";
  if (error.isNetwork) return "网络异常，请稍后重试";
  return getUserFacingError(error);
}

export function mapRegisterError(error: AppApiError): string {
  if (error.code === "EMAIL_ALREADY_REGISTERED") return "该邮箱已注册";
  if (error.code === "INVALID_REGISTER_REQUEST") return error.message || "注册信息无效";
  if (error.isNetwork) return "网络异常，请稍后重试";
  return getUserFacingError(error);
}

export function parseRetryAfter(raw: unknown, now = Date.now()): number | undefined {
  if (typeof raw !== "string" && typeof raw !== "number") return undefined;
  const text = String(raw).trim();
  if (!text) return undefined;
  const seconds = /^\d+$/.test(text) ? Number(text) : (Date.parse(text) - now) / 1000;
  return Number.isFinite(seconds) ? Math.max(0, Math.ceil(seconds)) : undefined;
}
export function getUserFacingError(error: unknown): string {
  if (error instanceof ContractValidationError) return "服务响应格式异常";
  const e = toAppApiError(error);
  if (e.isNetwork) return "网络连接失败，请检查连接后重试";
  if (e.statusCode === 400) return "输入信息不符合要求，请检查后重试";
  if (e.isForbidden) return "你没有执行此操作的权限";
  if (e.isNotFound) return "资源不存在或你无权访问";
  if (e.isConflict) return "当前资源状态已变化或名称重复，请刷新后重试";
  if (e.isRateLimited)
    return e.retryAfterSeconds
      ? "请求过于频繁，请 " + e.retryAfterSeconds + " 秒后重试"
      : "请求过于频繁，请稍后重试";
  if (e.isServerError) return "服务暂时不可用，请稍后重试";
  if (e.statusCode === 401) return "登录状态已失效，请重新登录";
  return "操作未完成，请重试";
}
