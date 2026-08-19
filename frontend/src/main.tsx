import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@/app/App";
import "@/app/styles/global.css";

async function prepare() {
  // 使用字面量环境变量判断，便于生产 real 构建剔除 MSW 分块
  if (import.meta.env.VITE_API_MODE === "mock") {
    const { startMockWorker } = await import("@/mocks/browser");
    await startMockWorker();
  }
}

void prepare().then(() => {
  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
});
