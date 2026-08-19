import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { mockServer } from "@/mocks/server";
import { resetStore } from "@/mocks/data/store";

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal("ResizeObserver", ResizeObserverMock);

beforeAll(() => {
  mockServer.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  mockServer.resetHandlers();
  resetStore();
});

afterAll(() => {
  mockServer.close();
});
