export type DiagnosticsEntry = {
  id: string;
  at: string;
  method: string;
  path: string;
  status: number | "pending" | "network" | "contract";
  durationMs?: number;
  requestId: string;
  errorCode?: string;
  note?: string;
  /** 已脱敏/截断的摘要，禁止写入密码/token/cookie */
  summary?: string;
};

type Listener = (entries: DiagnosticsEntry[]) => void;

const MAX = 100;
let entries: DiagnosticsEntry[] = [];
const listeners = new Set<Listener>();

export function getDiagnosticsEntries(): DiagnosticsEntry[] {
  return entries;
}

export function subscribeDiagnostics(listener: Listener): () => void {
  listeners.add(listener);
  listener(entries);
  return () => listeners.delete(listener);
}

function emit() {
  for (const l of listeners) l(entries);
}

export function pushDiagnostics(entry: DiagnosticsEntry) {
  entries = [entry, ...entries].slice(0, MAX);
  emit();
}

export function updateDiagnostics(id: string, patch: Partial<DiagnosticsEntry>) {
  entries = entries.map((e) => (e.id === id ? { ...e, ...patch } : e));
  emit();
}

export function clearDiagnostics() {
  entries = [];
  emit();
}

/** 脱敏：去掉常见密钥字段，长正文截断 */
export function sanitizeForDiagnostics(value: unknown, maxLen = 500): string {
  try {
    const redacted = redact(value);
    const text = typeof redacted === "string" ? redacted : JSON.stringify(redacted);
    if (text.length <= maxLen) return text;
    return `${text.slice(0, maxLen)}…(truncated)`;
  } catch {
    return "[unserializable]";
  }
}

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      const key = k.toLowerCase();
      if (
        key.includes("password") ||
        key.includes("token") ||
        key.includes("cookie") ||
        key.includes("authorization") ||
        key === "access_token"
      ) {
        out[k] = "***";
      } else {
        out[k] = redact(v);
      }
    }
    return out;
  }
  return value;
}
