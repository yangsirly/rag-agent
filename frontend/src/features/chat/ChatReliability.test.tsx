import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Routes, Route } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { mockServer } from "@/mocks/server";
import { renderWithProviders } from "@/test/utils";
import { Composer } from "./components/Composer";
import { ChatPage } from "./pages/ChatPage";
import { ConversationSidebar } from "./components/ConversationSidebar";
import { message } from "antd";

afterEach(() => {
  cleanup();
  message.destroy();
});
const list = { statusCode: 200, items: [], page: 0, size: 20, totalPages: 0, totalElements: 0 };

it("shows over-limit content and does not send", async () => {
  const send = vi.fn();
  renderWithProviders(<Composer onSend={send} />);
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "a".repeat(10001) } });
  expect(screen.getByText("消息内容须为 1～10000 字")).toBeVisible();
  expect(screen.getByRole("button", { name: /发送/ })).toBeDisabled();
  fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
  expect(send).not.toHaveBeenCalled();
});

it("preserves failed messages and retries with original id", async () => {
  const ids: string[] = [];
  mockServer.use(
    http.get("/api/conversations", () => HttpResponse.json(list)),
    http.get("/api/conversations/1/messages", () => HttpResponse.json(list)),
    http.post("/api/conversations/1/messages", async ({ request }) => {
      ids.push(((await request.json()) as { clientMessageId: string }).clientMessageId);
      return HttpResponse.error();
    }),
  );
  renderWithProviders(
    <Routes>
      <Route path="/chat/:conversationId" element={<ChatPage />} />
    </Routes>,
    { route: "/chat/1" },
  );
  const input = screen.getByRole("textbox");
  await userEvent.type(input, "first");
  await userEvent.click(screen.getByRole("button", { name: /发送/ }));
  await screen.findByRole("button", { name: /重\s*试/ });
  await userEvent.type(input, "second");
  await userEvent.click(screen.getByRole("button", { name: /发送/ }));
  await waitFor(() => expect(screen.getAllByRole("button", { name: /重\s*试/ })).toHaveLength(2));
  expect(screen.getByText("first")).toBeVisible();
  expect(screen.getByText("second")).toBeVisible();
  await userEvent.click(screen.getAllByRole("button", { name: /重\s*试/ })[0]);
  await waitFor(() => expect(ids).toHaveLength(3));
  expect(ids[0]).not.toBe(ids[1]);
  expect(ids[2]).toBe(ids[0]);
});

it("429 disables sending and retry during cooldown", async () => {
  mockServer.use(
    http.get("/api/conversations", () => HttpResponse.json(list)),
    http.get("/api/conversations/1/messages", () => HttpResponse.json(list)),
    http.post("/api/conversations/1/messages", () =>
      HttpResponse.json(
        { statusCode: 429, code: "RATE_LIMITED", message: "rate" },
        { status: 429, headers: { "Retry-After": "10" } },
      ),
    ),
  );
  renderWithProviders(
    <Routes>
      <Route path="/chat/:conversationId" element={<ChatPage />} />
    </Routes>,
    { route: "/chat/1" },
  );
  await userEvent.type(screen.getByRole("textbox"), "limited");
  await userEvent.click(screen.getByRole("button", { name: /发送/ }));
  expect(await screen.findByRole("button", { name: /秒后可重试/ })).toBeDisabled();
  expect(screen.getByRole("button", { name: /^重\s*试$/ })).toBeDisabled();
});

it("create failure is visible and rename failure preserves dialog input", async () => {
  mockServer.use(
    http.get("/api/conversations", () =>
      HttpResponse.json({
        ...list,
        items: [{ id: "1", title: "existing", createdAt: "2026-09-07", updatedAt: "2026-09-07" }],
      }),
    ),
    http.post("/api/conversations", () => HttpResponse.error()),
    http.patch("/api/conversations/1", () => HttpResponse.error()),
  );
  renderWithProviders(<ConversationSidebar />);
  await userEvent.click(screen.getByRole("button", { name: "plus 新会话" }));
  await waitFor(() => expect(screen.getByText("网络连接失败，请检查连接后重试")).toBeVisible());
  await userEvent.click(await screen.findByRole("button", { name: "编辑「existing」" }));
  await userEvent.clear(screen.getByRole("textbox", { name: "会话标题" }));
  await userEvent.type(screen.getByRole("textbox", { name: "会话标题" }), "new title");
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: /保\s*存/ }),
  );
  expect(screen.getByRole("textbox", { name: "会话标题" })).toHaveValue("new title");
  expect(screen.getByRole("dialog")).toBeVisible();
});
