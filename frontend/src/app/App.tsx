import { BrowserRouter } from "react-router-dom";
import { AppProviders } from "@/app/providers";
import { AppRouter } from "@/app/router";
import { useResolvedTheme } from "@/shared/theme/useResolvedTheme";
import { useEffect } from "react";

function ThemeAttributeSync() {
  const theme = useResolvedTheme();
  useEffect(() => {
    document.documentElement.setAttribute("data-theme", theme);
  }, [theme]);
  return null;
}

export function App() {
  return (
    <AppProviders>
      <BrowserRouter>
        <ThemeAttributeSync />
        <AppRouter />
      </BrowserRouter>
    </AppProviders>
  );
}
