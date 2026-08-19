import { test, expect } from "@playwright/test";

test("EDITOR knowledge base and document CRUD", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("邮箱").fill("editor@example.com");
  await page.locator('input[type="password"]').fill("password1");
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat/);
  await page.getByRole("menuitem", { name: /知识库/ }).click();
  await expect(page).toHaveURL(/\/knowledge-bases/);
  await page.getByRole("button", { name: /创建知识库/ }).click();
  const name = `E2E库-${Date.now()}`;
  await page.getByLabel("名称").fill(name);
  await page.getByRole("button", { name: /保\s*存/ }).click();
  await expect(page.getByText(name)).toBeVisible();
  await page.getByRole("link", { name }).click();
  await page.getByRole("button", { name: /新建文档/ }).click();
  await page.getByLabel("文档标题").fill("文档A");
  await page.getByLabel("正文").fill("这是文档正文内容");
  await page.getByRole("button", { name: /保\s*存/ }).click();
  await expect(page.getByText("文档A")).toBeVisible();
});
