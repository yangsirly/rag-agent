import { describe, expect, it } from "vitest";
import { AxiosError } from "axios";
import {
  AppApiError,
  ContractValidationError,
  getUserFacingError,
  parseRetryAfter,
  mapLoginError,
  toAppApiError,
} from "./errors";

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

it("parses Retry-After seconds and dates", () => {
  expect(parseRetryAfter("10")).toBe(10);
  expect(parseRetryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 0)).toBe(10);
  expect(parseRetryAfter("garbage")).toBeUndefined();
  expect(parseRetryAfter("")).toBeUndefined();
});
it.each([403, 404, 409, 429, 500])("maps HTTP %s without exposing server details", (status) => {
  expect(getUserFacingError(new AppApiError(status, "ERROR", "internal secret"))).not.toContain(
    "secret",
  );
});
it("keeps contract issues out of user feedback", () => {
  expect(getUserFacingError(new ContractValidationError("/", "GET", ["private detail"]))).toBe(
    "服务响应格式异常",
  );
});
