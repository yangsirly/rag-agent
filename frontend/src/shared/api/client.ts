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
let authEpoch = 0;
let unauthorizedNotifiedEpoch = -1;
let refreshAbortController: AbortController | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null) {
  onUnauthorized = handler;
}

/** 使登录/登出前已经发出的匿名请求不再覆盖新的认证状态。 */
export function beginAuthTransition() {
  authEpoch += 1;
  unauthorizedNotifiedEpoch = -1;
  // 登录/登出开始后，取消此前匿名请求触发的刷新；否则其 401 清 Cookie
  // 响应可能在新的登录响应之后到达，覆盖刚建立的会话。
  const previousRefresh = refreshAbortController;
  refreshAbortController = null;
  refreshInFlight = null;
  previousRefresh?.abort();
}

function notifyUnauthorized() {
  if (!onUnauthorized || unauthorizedNotifiedEpoch === authEpoch) return;
  unauthorizedNotifiedEpoch = authEpoch;
  onUnauthorized();
}

type RetryConfig = InternalAxiosRequestConfig & {
  __retryCount?: number;
  __diagId?: string;
  __startedAt?: number;
  __authRetry?: boolean;
  __authEpoch?: number;
};

export const apiClient: AxiosInstance = axios.create({
  baseURL: "/api",
  timeout: 15_000,
  withCredentials: true,
  headers: {
    "Content-Type": "application/json",
  },
});

// 刷新请求使用独立实例，避免 refresh 自己收到 401 后再次触发刷新拦截器。
const refreshClient: AxiosInstance = axios.create({
  baseURL: "/api",
  timeout: 15_000,
  withCredentials: true,
  headers: {
    "Content-Type": "application/json",
  },
});

let refreshInFlight: Promise<void> | null = null;

function isAuthLifecycleRequest(config: RetryConfig): boolean {
  const path = (config.url ?? "")
    .split("?")[0]
    .replace(/^\//, "")
    .replace(/^api\//, "");
  return path === "login" || path === "refresh" || path === "logout";
}

async function refreshAccessToken(): Promise<void> {
  if (!refreshInFlight) {
    const abortController = new AbortController();
    refreshAbortController = abortController;
    const pending = refreshClient
      .post("/refresh", undefined, { signal: abortController.signal })
      .then(() => undefined);
    refreshInFlight = pending;
    const clearPending = () => {
      // 登录/登出可能已经开启了下一代刷新；旧请求完成时不能清掉新 Promise。
      if (refreshInFlight === pending) {
        refreshInFlight = null;
        if (refreshAbortController === abortController) refreshAbortController = null;
      }
    };
    pending.then(clearPending, clearPending);
  }
  return refreshInFlight!;
}

apiClient.interceptors.request.use((config: RetryConfig) => {
  config.__authEpoch = authEpoch;
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
    if (appError.isUnauthorized && cfg.__authEpoch !== undefined && cfg.__authEpoch !== authEpoch) {
      return Promise.reject(appError);
    }
    // 受保护接口的第一次 401 先尝试续期；登录生命周期接口本身不触发续期。
    if (appError.isUnauthorized && !cfg.__authRetry && !isAuthLifecycleRequest(cfg)) {
      try {
        await refreshAccessToken();
        // 刷新期间可能发生了登录/登出；旧请求不能在新的认证 epoch 下重放。
        if (cfg.__authEpoch !== authEpoch) return Promise.reject(appError);
        cfg.__authRetry = true;
        return apiClient.request(cfg);
      } catch {
        // Refresh 失败后继续走统一清理/跳转逻辑，不泄露失败细节。
        if (cfg.__authEpoch === authEpoch) {
          notifyUnauthorized();
        }
        return Promise.reject(appError);
      }
    }

    // 已重试仍为 401，或请求本身是认证生命周期接口：交给应用清理登录态。
    if (appError.isUnauthorized) {
      notifyUnauthorized();
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
