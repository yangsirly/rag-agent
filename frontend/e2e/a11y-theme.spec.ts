import { registerAndLoginCustomer, loginEditor } from "./support/auth";
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
  await registerAndLoginCustomer(page);
  await page.getByRole("button", { name: /主\s*题/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", /light|dark/);
});

test("authenticated chat and mobile navigation have no serious axe violations", async ({
  page,
}, info) => {
  await registerAndLoginCustomer(page);
  await page.getByRole("button", { name: /新会话/ }).click();
  await expect(page.getByRole("textbox", { name: "消息内容" })).toBeVisible();
  if (info.project.name === "mobile") await page.getByRole("button", { name: "打开导航" }).click();
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  expect(
    results.violations.filter((v) => ["serious", "critical"].includes(v.impact || "")),
  ).toEqual([]);
});
test("knowledge base list and detail have no serious axe violations", async ({ page }, info) => {
  await loginEditor(page, info);
  if (info.project.name === "mobile") await page.getByRole("button", { name: "打开导航" }).click();
  await page.getByRole("menuitem", { name: /知识库/ }).click();
  await expect(page.getByRole("link", { name: "产品 FAQ" })).toBeVisible();
  const list = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  expect(list.violations.filter((v) => ["serious", "critical"].includes(v.impact || ""))).toEqual(
    [],
  );
  await page.getByRole("link", { name: "产品 FAQ" }).click();
  await expect(page.getByText("退货政策", { exact: true })).toBeVisible();
  const detail = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  expect(detail.violations.filter((v) => ["serious", "critical"].includes(v.impact || ""))).toEqual(
    [],
  );
});
