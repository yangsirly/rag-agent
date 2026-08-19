import { setupWorker } from "msw/browser";
import { handlers } from "@/mocks/handlers";

export async function startMockWorker() {
  const worker = setupWorker(...handlers);
  await worker.start({
    onUnhandledRequest: "bypass",
    serviceWorker: {
      url: "/mockServiceWorker.js",
    },
  });
  return worker;
}
