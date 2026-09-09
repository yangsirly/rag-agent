import { afterEach, beforeEach, expect, it } from "vitest";
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Routes, Route } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { message } from "antd";
import { mockServer } from "@/mocks/server";
import { renderWithProviders } from "@/test/utils";
import { useAuthStore } from "@/features/auth/auth-store";
import { KnowledgeBaseListPage } from "./pages/KnowledgeBaseListPage";
import { KnowledgeBaseDetailPage } from "./pages/KnowledgeBaseDetailPage";
const kb = {
  id: "1",
  name: "Test KB",
  creatorId: "2",
  createdAt: "2026-09-07",
  updatedAt: "2026-09-07",
};
const page = { statusCode: 200, items: [kb], page: 0, size: 20, totalPages: 1, totalElements: 1 };
beforeEach(() => {
  useAuthStore.getState().setUser({ userId: "2", email: "editor@example.com", role: "EDITOR" });
});
afterEach(() => {
  cleanup();
  message.destroy();
});

it.each([409, 500, 0])("save failure %s leaves modal and input intact", async (status) => {
  mockServer.use(
    http.get("/api/knowledge-bases", () => HttpResponse.json(page)),
    http.post("/api/knowledge-bases", () =>
      status
        ? HttpResponse.json({ statusCode: status, code: "ERROR", message: "failed" }, { status })
        : HttpResponse.error(),
    ),
  );
  renderWithProviders(<KnowledgeBaseListPage />);
  await userEvent.click(screen.getByRole("button", { name: /创建知识库/ }));
  await userEvent.type(screen.getByLabelText("名称"), "new kb");
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: /保\s*存/ }),
  );
  const text = status === 409 ? /名称重复/ : status === 500 ? /服务暂时不可用/ : /网络连接失败/;
  await waitFor(() => expect(screen.getByText(text)).toBeVisible());
  expect(screen.getByRole("dialog")).toBeVisible();
  expect(screen.getByLabelText("名称")).toHaveValue("new kb");
});

it("only the creator sees delete", async () => {
  useAuthStore.getState().setUser({ userId: "3", email: "other@example.com", role: "EDITOR" });
  mockServer.use(http.get("/api/knowledge-bases", () => HttpResponse.json(page)));
  renderWithProviders(<KnowledgeBaseListPage />);
  await screen.findByRole("link", { name: "Test KB" });
  expect(screen.queryByRole("button", { name: /删除/ })).not.toBeInTheDocument();
});

it("document detail failure does not open an empty editor", async () => {
  mockServer.use(
    http.get("/api/knowledge-bases/1", () => HttpResponse.json({ ...kb, statusCode: 200 })),
    http.get("/api/knowledge-bases/1/documents", () =>
      HttpResponse.json({
        ...page,
        items: [
          {
            id: "4",
            knowledgeBaseId: "1",
            title: "Doc",
            createdAt: "2026-09-07",
            updatedAt: "2026-09-07",
          },
        ],
      }),
    ),
    http.get("/api/knowledge-bases/1/documents/4", () => HttpResponse.error()),
  );
  renderWithProviders(
    <Routes>
      <Route path="/knowledge-bases/:id" element={<KnowledgeBaseDetailPage />} />
    </Routes>,
    { route: "/knowledge-bases/1" },
  );
  await userEvent.click(await screen.findByRole("button", { name: /编\s*辑/ }));
  await waitFor(() => expect(screen.getByText(/网络连接失败/)).toBeVisible());
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

it("delete failure keeps the resource visible", async () => {
  mockServer.use(
    http.get("/api/knowledge-bases", () => HttpResponse.json(page)),
    http.delete("/api/knowledge-bases/1", () => HttpResponse.error()),
  );
  renderWithProviders(<KnowledgeBaseListPage />);
  await userEvent.click(await screen.findByRole("button", { name: /删除/ }));
  await userEvent.click(screen.getByRole("button", { name: /确\s*认/ }));
  await waitFor(() => expect(screen.getByText(/网络连接失败/)).toBeVisible());
  expect(screen.getByRole("link", { name: "Test KB" })).toBeVisible();
});
it("deleting the final item on page two returns to page one", async () => {
  let deleted = false;
  mockServer.use(
    http.get("/api/knowledge-bases", ({ request }) => {
      const current = Number(new URL(request.url).searchParams.get("page"));
      return HttpResponse.json({
        ...page,
        page: current,
        totalPages: deleted ? 1 : 2,
        totalElements: deleted ? 20 : 21,
        items: [{ ...kb, name: current ? "Last KB" : "First KB" }],
      });
    }),
    http.delete("/api/knowledge-bases/1", () => {
      deleted = true;
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderWithProviders(<KnowledgeBaseListPage />);
  await screen.findByRole("link", { name: "First KB" });
  await userEvent.click(screen.getByTitle("2"));
  await screen.findByRole("link", { name: "Last KB" });
  await userEvent.click(screen.getByRole("button", { name: /删除/ }));
  await userEvent.click(screen.getByRole("button", { name: /确\s*认/ }));
  expect(await screen.findByRole("link", { name: "First KB" })).toBeVisible();
});
