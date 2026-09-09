import { expect, it } from "vitest";
import { createAppQueryClient } from "./query-client";
import { AppApiError, ContractValidationError } from "./errors";
it("has one bounded retry policy and never retries mutations or contracts", () => {
  const options = createAppQueryClient().getDefaultOptions();
  const retry = options.queries!.retry as (count: number, error: Error) => boolean;
  expect(options.mutations!.retry).toBe(false);
  expect(retry(0, new ContractValidationError("/", "GET", []))).toBe(false);
  for (const code of [400, 401, 403, 404, 409, 429])
    expect(retry(0, new AppApiError(code, "ERROR", ""))).toBe(false);
  expect(retry(1, new AppApiError(0, "NETWORK_ERROR", ""))).toBe(true);
  expect(retry(2, new AppApiError(0, "NETWORK_ERROR", ""))).toBe(false);
  expect(retry(0, new AppApiError(503, "ERROR", ""))).toBe(true);
  expect(retry(1, new AppApiError(503, "ERROR", ""))).toBe(false);
});
