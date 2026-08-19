export type ApiMode = "mock" | "real";

function parseBool(raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined || raw === "") return fallback;
  return raw === "true" || raw === "1";
}

export const appEnv = {
  apiMode: (import.meta.env.VITE_API_MODE === "real" ? "real" : "mock") as ApiMode,
  enableKbMembership: parseBool(import.meta.env.VITE_ENABLE_KB_MEMBERSHIP, false),
  enableDiagnostics: parseBool(import.meta.env.VITE_ENABLE_DIAGNOSTICS, import.meta.env.DEV),
  isDev: import.meta.env.DEV,
  isProd: import.meta.env.PROD,
} as const;
