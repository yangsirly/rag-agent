import { http } from "msw";
import { describe, expect, it, vi, afterEach } from "vitest";
import { mockServer } from "@/mocks/server";
import { apiClient, setUnauthorizedHandler } from "./client";
import { err, json } from "@/mocks/handlers/utils";

describe("api client access-token recovery", () => {
  afterEach(() => {
    setUnauthorizedHandler(null);
  });

  it("single-flights refresh and retries each original request once", async () => {
    let protectedCalls = 0;
    const refreshCalls = vi.fn();
    mockServer.use(
      http.get("/api/protected", () => {
        protectedCalls += 1;
        return protectedCalls <= 2
          ? err(401, "UNAUTHORIZED", "未登录或凭证无效")
          : json({ statusCode: 200, ok: true });
      }),
      http.post("/api/refresh", () => {
        refreshCalls();
        return json({ statusCode: 200 });
      }),
    );

    const results = await Promise.all([apiClient.get("/protected"), apiClient.get("/protected")]);

    expect(refreshCalls).toHaveBeenCalledTimes(1);
    expect(protectedCalls).toBe(4);
    expect(results.map((result) => result.data.ok)).toEqual([true, true]);
  });

  it("does not recursively refresh when refresh itself fails", async () => {
    const refreshCalls = vi.fn();
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    mockServer.use(
      http.get("/api/protected", () => err(401, "UNAUTHORIZED", "未登录或凭证无效")),
      http.post("/api/refresh", () => {
        refreshCalls();
        return err(401, "UNAUTHORIZED", "未登录或凭证无效");
      }),
    );

    const results = await Promise.allSettled([
      apiClient.get("/protected"),
      apiClient.get("/protected"),
    ]);

    expect(refreshCalls).toHaveBeenCalledTimes(1);
    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(unauthorized).toHaveBeenCalledTimes(1);
  });
});
