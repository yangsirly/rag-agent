import { registerAndLoginCustomer } from "./support/auth";
import { test, expect } from "@playwright/test";

test.describe("chat", () => {
  test("create conversation, send, history", async ({ page }) => {
    await registerAndLoginCustomer(page);
    await page.getByRole("button", { name: /新会话/ }).click();
    await expect(page).toHaveURL(/\/chat\/\d+/);
    await page.getByPlaceholder(/输入消息/).fill("你好，系统现在能做什么？");
    await page.getByRole("button", { name: /发\s*送/ }).click();
    await expect(page.getByText("你好，系统现在能做什么？")).toBeVisible();
    await expect(
      page.getByText("已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。"),
    ).toBeVisible();
  });
});
