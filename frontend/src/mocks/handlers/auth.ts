import { http } from "msw";
import {
  clearCookieHeader,
  clearSession,
  cookieHeader,
  createSession,
  findUserByEmail,
  findUserById,
  getStore,
  nextId,
} from "@/mocks/data/store";
import { normalizeEmail } from "@/shared/lib/validation";
import { unicodeLength } from "@/shared/lib/unicode";
import { applyFault, err, getToken, json, requireUser } from "./utils";

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
    return json(
      { statusCode: 200, role: user.role },
      { status: 200, headers: { "Set-Cookie": cookieHeader(token) } },
    );
  }),

  http.post("/api/logout", async ({ request }) => {
    const token = getToken(request);
    clearSession(token);
    return json(
      { statusCode: 200 },
      { status: 200, headers: { "Set-Cookie": clearCookieHeader() } },
    );
  }),

  http.get("/api/me", async ({ request }) => {
    const fault = await applyFault();
    if (fault) return fault;
    const auth = requireUser(request);
    if ("error" in auth && auth.error) return auth.error;
    const user = findUserById(auth.user!.id)!;
    return json({
      statusCode: 200,
      userId: user.id,
      email: user.email,
      role: user.role,
    });
  }),
];
