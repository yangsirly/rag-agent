/// <reference types="vitest/config" />
import path from "node:path";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiTarget = env.VITE_API_PROXY_TARGET || "http://localhost:8080";

  return {
    plugins: [react()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "src"),
      },
    },
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api/, ""),
        },
      },
    },
    preview: {
      port: 4173,
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api/, ""),
        },
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      include: ["src/**/*.{test,spec}.{ts,tsx}"],
      exclude: ["e2e/**", "node_modules/**", "dist/**"],
      css: true,
      coverage: {
        provider: "v8",
        reporter: ["text", "html", "lcov"],
        include: ["src/shared/lib/**/*.{ts,tsx}", "src/shared/api/**/*.{ts,tsx}", "src/features/**/api.ts", "src/features/auth/auth-store.ts"],
        exclude: [
          "src/main.tsx",
          "src/mocks/**",
          "src/test/**",
          "src/**/*.d.ts",
          "src/**/*.module.css",
        ],
        thresholds: {
          lines: 55,
          functions: 50,
          branches: 40,
          statements: 55,
        },
      },
    },
  };
});
