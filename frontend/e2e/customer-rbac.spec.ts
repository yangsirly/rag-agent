import { registerAndLoginCustomer } from "./support/auth";
import { test, expect } from "@playwright/test";

test("CUSTOMER has no knowledge base menu and gets 403 on direct access", async ({ page }) => {
  await registerAndLoginCustomer(page);
  await expect(page.getByRole("menuitem", { name: /知识库/ })).toHaveCount(0);
  await page.goto("/knowledge-bases");
  await expect(page.getByText("403")).toBeVisible();
});
