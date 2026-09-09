import { loginEditor } from "./support/auth";
import { test, expect } from "@playwright/test";

test("EDITOR knowledge base and document CRUD", async ({ page }, info) => {
  await loginEditor(page, info);
  if (info.project.name === "mobile") await page.getByRole("button", { name: "打开导航" }).click();
  await page.getByRole("menuitem", { name: /知识库/ }).click();
  await expect(page).toHaveURL(/\/knowledge-bases/);
  await page.getByRole("button", { name: /创建知识库/ }).click();
  // 后端当前知识库名称上限为 16 个 Unicode 码点。
  const name = `E2E-${crypto.randomUUID().slice(0, 6)}`;
  await page.getByLabel("名称").fill(name);
  await page.getByRole("button", { name: /保\s*存/ }).click();
  await expect(page.getByText(name)).toBeVisible();
  await page.getByRole("link", { name }).click();
  await page.getByRole("button", { name: /新建文档/ }).click();
  await page.getByLabel("文档标题").fill("文档A");
  await page.getByLabel("正文").fill("这是文档正文内容");
  await page.getByRole("button", { name: /保\s*存/ }).click();
  await expect(page.getByText("文档A", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看", exact: true }).click();
  await expect(page.getByText("这是文档正文内容", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Close|关闭/ }).click();
  await page.getByRole("button", { name: /编\s*辑/ }).click();
  await page.getByLabel("文档标题").fill("文档B");
  await page.getByLabel("正文").fill("更新后的正文");
  await page.getByRole("button", { name: /保\s*存/ }).click();
  await expect(page.getByText("文档B", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /删\s*除/ }).click();
  await page.getByRole("button", { name: /确\s*认/ }).click();
  await expect(page.getByText("文档B", { exact: true })).toHaveCount(0);
  if (info.project.name === "mobile") await page.getByRole("button", { name: "打开导航" }).click();
  await page.getByRole("menuitem", { name: /知识库/ }).click();
  const card = page
    .locator(".ant-card")
    .filter({ has: page.getByRole("link", { name, exact: true }) });
  await card.getByRole("button", { name: /编\s*辑/ }).click();
  await page.getByLabel("名称").fill(name + "-改");
  await page.getByRole("button", { name: /保\s*存/ }).click();
  const updated = page
    .locator(".ant-card")
    .filter({ has: page.getByRole("link", { name: name + "-改", exact: true }) });
  await updated.getByRole("button", { name: /删\s*除/ }).click();
  await page.getByRole("button", { name: /确\s*认/ }).click();
  await expect(page.getByRole("link", { name: name + "-改", exact: true })).toHaveCount(0);
});
