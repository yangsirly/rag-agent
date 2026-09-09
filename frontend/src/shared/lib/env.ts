export type ApiMode = "mock" | "real";

function parseBool(raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined || raw === "") return fallback;
  return raw === "true" || raw === "1";
}

export function parseApiMode(raw: string | undefined, mode: string): ApiMode {
  if (raw === "mock" || raw === "real") return raw;
  if (raw === undefined && mode === "test") return "mock";
  throw new Error("VITE_API_MODE must be mock or real");
}

export const appEnv = {
  apiMode: parseApiMode(import.meta.env.VITE_API_MODE, import.meta.env.MODE),
  enableKbMembership: parseBool(import.meta.env.VITE_ENABLE_KB_MEMBERSHIP, false),
  enableDiagnostics: parseBool(import.meta.env.VITE_ENABLE_DIAGNOSTICS, import.meta.env.DEV),
  isDev: import.meta.env.DEV,
  isProd: import.meta.env.PROD,
} as const;
