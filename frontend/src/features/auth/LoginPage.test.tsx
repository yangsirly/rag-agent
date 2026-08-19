import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LoginPage } from "./pages/LoginPage";
import { renderWithProviders } from "@/test/utils";

describe("LoginPage", () => {
  it("shows credential error without logging out semantics", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: "/login" });

    await user.type(screen.getByLabelText("邮箱"), "customer@example.com");
    await user.type(screen.getByLabelText("密码"), "wrong-password");
    // Ant Design 中文按钮文案可能插入空格，如「登 录」
    await user.click(screen.getByRole("button", { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByText(/邮箱或密码错误/)).toBeInTheDocument();
    });
  });
});
