import { http } from "msw";
import {
  clearSession,
  clearCookieHeaders,
  cookieHeaders,
  createSession,
  findUserByEmail,
  getStore,
  nextId,
  rotateSession,
  sessionTokens,
} from "@/mocks/data/store";
import { normalizeEmail } from "@/shared/lib/validation";
import { unicodeLength } from "@/shared/lib/unicode";
import { applyFault, err, getRefreshToken, getToken, json, requireUser } from "./utils";

export const authHandlers = [
  http.post("/api/register", async ({ request }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const body = (await request.json()) as { email?: string; password?: string };
    if (!body.email?.trim() || !body.password) {
      return err(400, "INVALID_REGISTER_REQUEST", "Registration request fields must not be empty");
    }
    const email = normalizeEmail(body.email);
    const pwdLen = unicodeLength(body.password);
    if (!email.includes("@") || email.length > 254 || pwdLen < 8 || pwdLen > 64) {
      return err(400, "INVALID_REGISTER_REQUEST", "邮箱或密码不合法");
    }
    if (findUserByEmail(email)) {
      return err(409, "EMAIL_ALREADY_REGISTERED", "邮箱已注册");
    }
    getStore().users.push({
      id: nextId(),
      email,
      password: body.password,
      role: "CUSTOMER",
      status: "ACTIVE",
    });
    return json({ statusCode: 201 }, { status: 201 });
  }),

  http.post("/api/login", async ({ request }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const body = (await request.json()) as { email?: string; password?: string };
    if (!body.email?.trim() || !body.password) {
      return err(400, "INVALID_LOGIN_REQUEST", "Login request fields must not be empty");
    }
    const user = findUserByEmail(body.email);
    if (!user || user.password !== body.password) {
      return err(401, "INVALID_CREDENTIALS", "邮箱或密码错误");
    }
    if (user.status === "DISABLED") {
      return err(401, "USER_DISABLED", "账号已禁用");
    }
    const token = createSession(user.id);
    const tokens = sessionTokens(token)!;
    return json(
      { statusCode: 200, role: user.role },
      { status: 200, headers: cookieHeaders(tokens.accessToken, tokens.refreshToken) },
    );
  }),

  http.post("/api/refresh", async ({ request, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const rotated = rotateSession(getRefreshToken(request, cookies));
    if (!rotated) {
      return json(
        { statusCode: 401, code: "UNAUTHORIZED", message: "未登录或凭证无效" },
        { status: 401, headers: clearCookieHeaders() },
      );
    }
    const user = findUserByEmail(rotated.email) ?? {
      id: rotated.userId,
      email: rotated.email,
      password: "",
      role: rotated.role,
      status: "ACTIVE" as const,
    };
    if (!user || user.status !== "ACTIVE") {
      rotated.revoked = true;
      return json(
        { statusCode: 401, code: "UNAUTHORIZED", message: "未登录或凭证无效" },
        { status: 401, headers: clearCookieHeaders() },
      );
    }
    return json(
      { statusCode: 200 },
      { status: 200, headers: cookieHeaders(rotated.accessToken, rotated.refreshToken) },
    );
  }),

  http.post("/api/logout", async ({ request, cookies }) => {
    const token = getToken(request, cookies);
    clearSession(token);
    clearSession(getRefreshToken(request, cookies));
    return json(
      { statusCode: 200 },
      { status: 200, headers: clearCookieHeaders() },
    );
  }),

  http.get("/api/me", async ({ request, cookies }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request, cookies);
    if ("error" in auth && auth.error) return auth.error;
    const user = auth.user!;
    return json({
      statusCode: 200,
      userId: user.id,
      email: user.email,
      role: user.role,
    });
  }),
];
