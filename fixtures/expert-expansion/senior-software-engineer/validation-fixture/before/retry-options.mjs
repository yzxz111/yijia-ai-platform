// User-owned note: these names are consumed by a separate worker process.
export function createRetryOptions(input = {}) {
  return {
    maxRetries: input.maxRetries || 3,
    delayMs: input.delayMs || 100,
  };
}
