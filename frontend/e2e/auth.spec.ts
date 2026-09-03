import { test, expect } from "@playwright/test";

test.describe("auth flow", () => {
  test("register -> login -> refresh recovery -> logout", async ({ page }) => {
    const email = `e2e_${crypto.randomUUID()}@example.com`;
    await page.goto("/register");
    await page.locator("#register-email").fill(email);
    await page.locator("#register-password").fill("password1");
    await page.getByRole("button", { name: /注\s*册/ }).click();
    await expect(page).toHaveURL(/\/login/);

    await expect(page.locator("#login-email")).toBeVisible();
    await page.locator("#login-email").fill(email);
    await page.locator("#login-password").fill("password1");
    await page.getByRole("button", { name: /登\s*录/ }).click();
    await expect(page).toHaveURL(/\/chat/, { timeout: 15_000 });

    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(500);
    // 保留 Refresh Cookie，手动替换 Access Cookie，验证页面刷新会自动续期。
    await page.context().addCookies([
      { name: "access_token", value: "expired-access", url: "http://127.0.0.1:5173" },
    ]);
    await page.reload();
    await expect(page).toHaveURL(/\/chat/);
    await expect(page.getByText(email)).toBeVisible();

    await page.getByRole("button", { name: /退出登录/ }).click();
    await expect(page).toHaveURL(/\/login/);
  });
});
