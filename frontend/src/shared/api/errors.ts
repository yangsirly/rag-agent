import { AxiosError } from "axios";
import { ApiErrorSchema, type ApiErrorBody } from "./schemas";

export class AppApiError extends Error {
  readonly statusCode: number;
  readonly code: string;
  readonly body: ApiErrorBody | null;

  constructor(statusCode: number, code: string, message: string, body: ApiErrorBody | null = null) {
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
    const parsed = ApiErrorSchema.safeParse(error.response.data);
    if (parsed.success) {
      return new AppApiError(status, parsed.data.code, parsed.data.message, parsed.data);
    }
    return new AppApiError(status, "UNKNOWN_ERROR", "请求失败，请稍后重试");
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
  return error.message || "登录失败，请稍后重试";
}

export function mapRegisterError(error: AppApiError): string {
  if (error.code === "EMAIL_ALREADY_REGISTERED") return "该邮箱已注册";
  if (error.code === "INVALID_REGISTER_REQUEST") return error.message || "注册信息无效";
  if (error.isNetwork) return "网络异常，请稍后重试";
  return error.message || "注册失败，请稍后重试";
}
