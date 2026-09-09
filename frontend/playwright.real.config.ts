import { defineConfig, devices } from "@playwright/test";

if (!process.env.E2E_EDITOR_EMAIL || !process.env.E2E_EDITOR_PASSWORD) {
  throw new Error("Real E2E requires EDITOR credentials: E2E_EDITOR_EMAIL and E2E_EDITOR_PASSWORD");
}

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:5175",
    trace: "on-first-retry",
  },
  webServer: {
    env: { VITE_API_MODE: "real", VITE_ENABLE_KB_MEMBERSHIP: "false" },
    command: "npm run dev -- --host 127.0.0.1 --port 5175",
    url: "http://127.0.0.1:5175",
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    {
      name: "chromium-real",
      use: { ...devices["Desktop Chrome"] },
      testMatch: /(auth|chat|customer-rbac|editor-kb)\.spec\.ts$/,
    },
  ],
});
