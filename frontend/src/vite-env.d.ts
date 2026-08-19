/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_MODE: "mock" | "real";
  readonly VITE_ENABLE_KB_MEMBERSHIP: string;
  readonly VITE_ENABLE_DIAGNOSTICS: string;
  readonly VITE_API_PROXY_TARGET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
