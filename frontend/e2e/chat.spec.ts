import { test, expect } from "@playwright/test";

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("customer@example.com");
  await page.locator('input[type="password"]').fill("password1");
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat/);
}

test.describe("chat", () => {
  test("create conversation, send, history", async ({ page }) => {
    await login(page);
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
