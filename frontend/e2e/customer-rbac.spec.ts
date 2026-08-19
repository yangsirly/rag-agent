import { test, expect } from "@playwright/test";

test("CUSTOMER has no knowledge base menu and gets 403 on direct access", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("customer@example.com");
  await page.locator('input[type="password"]').fill("password1");
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat/);
  await expect(page.getByRole("menuitem", { name: /知识库/ })).toHaveCount(0);
  await page.goto("/knowledge-bases");
  await expect(page.getByText("403")).toBeVisible();
});
