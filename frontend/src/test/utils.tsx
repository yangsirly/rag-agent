import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { App as AntApp, ConfigProvider } from "antd";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement, ReactNode } from "react";

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

export function renderWithProviders(
  ui: ReactElement,
  options?: { route?: string; client?: QueryClient },
) {
  const client = options?.client ?? createTestQueryClient();
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <ConfigProvider>
          <AntApp>
            <MemoryRouter initialEntries={[options?.route ?? "/"]}>{children}</MemoryRouter>
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>
    );
  }
  return { ...render(ui, { wrapper: Wrapper }), client };
}
