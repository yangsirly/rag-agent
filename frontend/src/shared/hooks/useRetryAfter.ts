import { useEffect, useState } from "react";
import { AppApiError } from "@/shared/api/errors";

/** 使用截止时间计算冷却，后台标签页计时器延迟不会延长等待。 */
export function useRetryAfter() {
  const [deadline, setDeadline] = useState(0);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    if (!deadline) return;
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= deadline) setDeadline(0);
    }, 250);
    return () => window.clearInterval(timer);
  }, [deadline]);
  const start = (error: unknown) => {
    if (error instanceof AppApiError && error.isRateLimited) {
      const time = Date.now();
      setNow(time);
      setDeadline(time + (error.retryAfterSeconds ?? 1) * 1000);
    }
  };
  return { seconds: Math.max(0, Math.ceil((deadline - now) / 1000)), start };
}
