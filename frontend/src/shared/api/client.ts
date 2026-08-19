import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from "axios";
import { z } from "zod";
import { createClientRequestId } from "@/shared/lib/id";
import {
  clearDiagnostics,
  pushDiagnostics,
  sanitizeForDiagnostics,
  updateDiagnostics,
} from "./diagnostics-log";
import {
  ContractValidationError,
  toAppApiError,
} from "./errors";

export type UnauthorizedHandler = () => void;

let onUnauthorized: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null) {
  onUnauthorized = handler;
}

type RetryConfig = InternalAxiosRequestConfig & {
  __retryCount?: number;
  __diagId?: string;
  __startedAt?: number;
};

export const apiClient: AxiosInstance = axios.create({
  baseURL: "/api",
  timeout: 15_000,
  withCredentials: true,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use((config: RetryConfig) => {
  const requestId = createClientRequestId();
  config.headers.set("X-Client-Request-Id", requestId);
  config.__startedAt = Date.now();
  const path = `${config.baseURL ?? ""}${config.url ?? ""}`;
  const diagId = createClientRequestId();
  config.__diagId = diagId;
  pushDiagnostics({
    id: diagId,
    at: new Date().toISOString(),
    method: (config.method ?? "get").toUpperCase(),
    path,
    status: "pending",
    requestId,
    summary: sanitizeForDiagnostics({
      params: config.params,
      data: config.data,
    }),
  });
  return config;
});

apiClient.interceptors.response.use(
  (response) => {
    const cfg = response.config as RetryConfig;
    if (cfg.__diagId) {
      updateDiagnostics(cfg.__diagId, {
        status: response.status,
        durationMs: cfg.__startedAt ? Date.now() - cfg.__startedAt : undefined,
        summary: sanitizeForDiagnostics(response.data),
      });
    }
    return response;
  },
  async (error: AxiosError) => {
    const cfg = (error.config ?? {}) as RetryConfig;
    const method = (cfg.method ?? "get").toUpperCase();
    const isGet = method === "GET";
    const retryCount = cfg.__retryCount ?? 0;
    const noResponse = !error.response;

    // GET 网络错误最多重试 2 次；业务 4xx 与写请求不自动重试
    if (isGet && noResponse && retryCount < 2) {
      cfg.__retryCount = retryCount + 1;
      return apiClient.request(cfg);
    }

    if (cfg.__diagId) {
      const status = error.response?.status ?? "network";
      const body = error.response?.data;
      const code =
        body && typeof body === "object" && "code" in body
          ? String((body as { code: string }).code)
          : undefined;
      updateDiagnostics(cfg.__diagId, {
        status: typeof status === "number" ? status : "network",
        durationMs: cfg.__startedAt ? Date.now() - cfg.__startedAt : undefined,
        errorCode: code,
        summary: sanitizeForDiagnostics(body ?? error.message),
      });
    }

    const appError = toAppApiError(error);
    // 只有 401 + UNAUTHORIZED 才清登录态；INVALID_CREDENTIALS / USER_DISABLED 留给表单
    if (appError.isUnauthorized) {
      onUnauthorized?.();
    }
    return Promise.reject(appError);
  },
);

export async function requestAndParse<T>(
  config: AxiosRequestConfig,
  schema: z.ZodType<T>,
): Promise<T> {
  const response = await apiClient.request(config);
  const result = schema.safeParse(response.data);
  if (!result.success) {
    const issues = result.error.issues.map(
      (i) => `${i.path.join(".") || "(root)"}: ${i.message}`,
    );
    const path = `${config.baseURL ?? "/api"}${config.url ?? ""}`;
    const method = (config.method ?? "get").toUpperCase();
    pushDiagnostics({
      id: createClientRequestId(),
      at: new Date().toISOString(),
      method,
      path,
      status: "contract",
      requestId: String(response.headers["x-client-request-id"] ?? ""),
      note: issues.join("; "),
      summary: sanitizeForDiagnostics(response.data),
    });
    throw new ContractValidationError(path, method, issues);
  }
  return result.data;
}

export async function requestNoContent(config: AxiosRequestConfig): Promise<void> {
  await apiClient.request({ ...config, validateStatus: (s) => s === 204 || s === 200 });
}

// 便于测试重置诊断
export { clearDiagnostics };
