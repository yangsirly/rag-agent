import { useEffect, useMemo, useState } from "react";
import { useUiStore, type ThemePreference } from "@/shared/store/ui-store";

function getSystemDark(): boolean {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

export function useResolvedTheme(): "light" | "dark" {
  const preference = useUiStore((s) => s.themePreference);
  const [systemDark, setSystemDark] = useState(getSystemDark);

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setSystemDark(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  return useMemo(() => {
    if (preference === "system") return systemDark ? "dark" : "light";
    return preference;
  }, [preference, systemDark]);
}

export function cycleTheme(current: ThemePreference): ThemePreference {
  if (current === "light") return "dark";
  if (current === "dark") return "system";
  return "light";
}
