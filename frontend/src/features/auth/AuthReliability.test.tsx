import { RegisterPage } from "./pages/RegisterPage";
import { message } from "antd";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { mockServer } from "@/mocks/server";
import { renderWithProviders } from "@/test/utils";
import { AppRouter } from "@/app/router";
import { LoginPage } from "./pages/LoginPage";
import { AppLayout } from "@/app/AppLayout";
import { useAuthStore } from "./auth-store";
import { beginAuthTransition, setUnauthorizedHandler } from "@/shared/api/client";

const me = { statusCode: 200, userId: "1", email: "test@example.com", role: "CUSTOMER" };
beforeEach(() => {
  message.destroy();
  mockServer.use(
    http.get("/api/conversations", () =>
      HttpResponse.json({
        statusCode: 200,
        items: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      }),
    ),
  );
  beginAuthTransition();
  useAuthStore.setState({ user: null, status: "bootstrapping" });
});
afterEach(() => {
  cleanup();
  setUnauthorizedHandler(null);
});

it("bootstrap network error stays on recovery screen and can retry", async () => {
  mockServer.use(http.get("/api/me", () => HttpResponse.error()));
  renderWithProviders(<AppRouter />, { route: "/chat" });
  expect(await screen.findByText("无法恢复登录状态")).toBeVisible();
  expect(screen.queryByLabelText("密码")).not.toBeInTheDocument();
  mockServer.use(http.get("/api/me", () => HttpResponse.json(me)));
  await userEvent.click(screen.getByRole("button", { name: "重新尝试" }));
  expect(await screen.findByText(me.email)).toBeVisible();
  expect(useAuthStore.getState().status).toBe("authenticated");
});

it("bootstrap expired refresh resolves to anonymous", async () => {
  mockServer.use(
    http.get("/api/me", () =>
      HttpResponse.json(
        { statusCode: 401, code: "UNAUTHORIZED", message: "expired" },
        { status: 401 },
      ),
    ),
    http.post("/api/refresh", () =>
      HttpResponse.json(
        { statusCode: 401, code: "UNAUTHORIZED", message: "expired" },
        { status: 401 },
      ),
    ),
  );
  renderWithProviders(<AppRouter />, { route: "/chat" });
  expect(await screen.findByLabelText("密码")).toBeVisible();
  expect(useAuthStore.getState().status).toBe("anonymous");
});

it("successful login with failed profile retries only profile", async () => {
  const login = vi.fn(() => HttpResponse.json({ statusCode: 200, role: "CUSTOMER" }));
  mockServer.use(
    http.post("/api/login", login),
    http.get("/api/me", () => HttpResponse.error()),
  );
  renderWithProviders(<LoginPage />);
  await userEvent.type(screen.getByLabelText("邮箱"), me.email);
  await userEvent.type(screen.getByLabelText("密码"), "password1");
  await userEvent.click(screen.getByRole("button", { name: /登\s*录/ }));
  expect(await screen.findByText("登录成功，但无法加载用户信息。")).toBeVisible();
  expect(screen.queryByText("邮箱或密码错误")).not.toBeInTheDocument();
  mockServer.use(http.get("/api/me", () => HttpResponse.json(me)));
  await userEvent.click(screen.getByRole("button", { name: "重试加载用户信息" }));
  await waitFor(() => expect(useAuthStore.getState().status).toBe("authenticated"));
  expect(login).toHaveBeenCalledTimes(1);
});

it.each([0, 500])("logout failure %s preserves current user", async (status) => {
  useAuthStore.getState().setUser({ userId: "1", email: me.email, role: "CUSTOMER" });
  mockServer.use(
    http.post("/api/logout", () =>
      status ? HttpResponse.json({}, { status }) : HttpResponse.error(),
    ),
  );
  renderWithProviders(<AppLayout />);
  await userEvent.click(screen.getByRole("button", { name: /退出登录/ }));
  await waitFor(() => expect(screen.getByText("退出登录未完成，请重试")).toBeVisible());
  expect(useAuthStore.getState().user?.email).toBe(me.email);
});

it.each(["login", "register"])("%s honors Retry-After", async (kind) => {
  const handler = vi.fn(() =>
    HttpResponse.json(
      { statusCode: 429, code: "RATE_LIMITED", message: "limited" },
      { status: 429, headers: { "Retry-After": "10" } },
    ),
  );
  mockServer.use(http.post("/api/" + kind, handler));
  renderWithProviders(kind === "login" ? <LoginPage /> : <RegisterPage />);
  await userEvent.type(screen.getByLabelText("邮箱"), me.email);
  await userEvent.type(screen.getByLabelText("密码"), "password1");
  await userEvent.click(
    screen.getByRole("button", { name: kind === "login" ? /登\s*录/ : /注\s*册/ }),
  );
  expect(await screen.findByRole("button", { name: /秒后可重试/ })).toBeDisabled();
  expect(handler).toHaveBeenCalledTimes(1);
});
it.each([200, 401])("logout %s clears local authentication", async (status) => {
  useAuthStore.getState().setUser({ userId: "1", email: me.email, role: "CUSTOMER" });
  mockServer.use(
    http.post("/api/logout", () => HttpResponse.json({ statusCode: status }, { status })),
  );
  renderWithProviders(<AppLayout />);
  await userEvent.click(screen.getByRole("button", { name: /退出登录/ }));
  await waitFor(() => expect(useAuthStore.getState().user).toBeNull());
});
