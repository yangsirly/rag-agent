import { describe, expect, it } from "vitest";
import { AxiosError } from "axios";
import { AppApiError, mapLoginError, toAppApiError } from "./errors";

describe("toAppApiError", () => {
  it("maps UNAUTHORIZED body", () => {
    const err = toAppApiError(
      new AxiosError("x", "ERR", undefined, undefined, {
        status: 401,
        data: { statusCode: 401, code: "UNAUTHORIZED", message: "no" },
        statusText: "Unauthorized",
        headers: {},
        config: {} as never,
      }),
    );
    expect(err).toBeInstanceOf(AppApiError);
    expect(err.isUnauthorized).toBe(true);
  });

  it("does not treat INVALID_CREDENTIALS as session expiry", () => {
    const err = toAppApiError(
      new AxiosError("x", "ERR", undefined, undefined, {
        status: 401,
        data: { statusCode: 401, code: "INVALID_CREDENTIALS", message: "bad" },
        statusText: "Unauthorized",
        headers: {},
        config: {} as never,
      }),
    );
    expect(err.isUnauthorized).toBe(false);
    expect(err.isInvalidCredentials).toBe(true);
    expect(mapLoginError(err)).toContain("邮箱或密码");
  });

  it("maps network errors", () => {
    const err = toAppApiError(new AxiosError("network", "ERR_NETWORK"));
    expect(err.isNetwork).toBe(true);
  });
});
