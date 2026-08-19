import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("login page has no serious axe violations", async ({ page }) => {
  await page.goto("/login");
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  const serious = results.violations.filter((v) =>
    ["serious", "critical"].includes(v.impact || ""),
  );
  expect(serious).toEqual([]);
});

test("theme toggle works after login", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("customer@example.com");
  await page.locator('input[type="password"]').fill("password1");
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat/);
  await page.getByRole("button", { name: /主\s*题/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", /light|dark/);
});
