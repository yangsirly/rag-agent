import { test, expect } from "@playwright/test";

test.describe("auth flow", () => {
  test("register -> login -> refresh recovery -> logout", async ({ page }) => {
    const email = `e2e_${Date.now()}@example.com`;
    await page.goto("/register");
    await page.getByLabel("邮箱").fill(email);
    await page.locator('input[type="password"]').fill("password1");
    await page.getByRole("button", { name: /注\s*册/ }).click();
    await expect(page).toHaveURL(/\/login/);

    await page.getByLabel("邮箱").fill(email);
    await page.locator('input[type="password"]').fill("password1");
    await page.getByRole("button", { name: /登\s*录/ }).click();
    await expect(page).toHaveURL(/\/chat/);

    await page.reload();
    await expect(page).toHaveURL(/\/chat/);
    await expect(page.getByText(email)).toBeVisible();

    await page.getByRole("button", { name: /退出登录/ }).click();
    await expect(page).toHaveURL(/\/login/);
  });
});
