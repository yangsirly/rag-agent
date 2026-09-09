import { expect, type Page, type TestInfo } from "@playwright/test";

export async function login(page: Page, email: string, password: string) {
  if (!page.url().endsWith("/login")) await page.goto("/login");
  await page.locator("#login-email").fill(email);
  await page.locator("#login-password").fill(password);
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat/);
}

export async function registerCustomer(page: Page) {
  const email = "e2e_" + crypto.randomUUID() + "@example.com";
  const password = "password1";
  await page.goto("/register");
  await page.locator("#register-email").fill(email);
  await page.locator("#register-password").fill(password);
  await page.getByRole("button", { name: /注\s*册/ }).click();
  await expect(page).toHaveURL(/\/login/);
  return { email, password };
}

export async function registerAndLoginCustomer(page: Page) {
  const user = await registerCustomer(page);
  await login(page, user.email, user.password);
  return user;
}

export async function loginEditor(page: Page, info: TestInfo) {
  const real = info.project.name.includes("real");
  const email = real ? process.env.E2E_EDITOR_EMAIL : "editor@example.com";
  const password = real ? process.env.E2E_EDITOR_PASSWORD : "password1";
  if (!email || !password) throw new Error("Real E2E requires EDITOR credentials");
  await login(page, email, password);
}
