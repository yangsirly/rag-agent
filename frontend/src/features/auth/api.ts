import { beginAuthTransition, requestAndParse } from "@/shared/api/client";
import {
  LoginResponseSchema,
  MeResponseSchema,
  RefreshResponseSchema,
  StatusOnlySchema,
  type LoginResponse,
  type MeResponse,
} from "@/shared/api/schemas";

export async function registerApi(email: string, password: string): Promise<void> {
  await requestAndParse(
    { method: "POST", url: "/register", data: { email, password } },
    StatusOnlySchema,
  );
}

export async function loginApi(email: string, password: string): Promise<LoginResponse> {
  beginAuthTransition();
  return requestAndParse(
    { method: "POST", url: "/login", data: { email, password } },
    LoginResponseSchema,
  );
}

export async function logoutApi(): Promise<void> {
  beginAuthTransition();
  await requestAndParse({ method: "POST", url: "/logout" }, StatusOnlySchema);
}

export async function refreshApi(): Promise<void> {
  await requestAndParse({ method: "POST", url: "/refresh" }, RefreshResponseSchema);
}

export async function meApi(): Promise<MeResponse> {
  return requestAndParse({ method: "GET", url: "/me" }, MeResponseSchema);
}
