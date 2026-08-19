import { requestAndParse } from "@/shared/api/client";
import {
  LoginResponseSchema,
  MeResponseSchema,
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
  return requestAndParse(
    { method: "POST", url: "/login", data: { email, password } },
    LoginResponseSchema,
  );
}

export async function logoutApi(): Promise<void> {
  await requestAndParse({ method: "POST", url: "/logout" }, StatusOnlySchema);
}

export async function meApi(): Promise<MeResponse> {
  return requestAndParse({ method: "GET", url: "/me" }, MeResponseSchema);
}
