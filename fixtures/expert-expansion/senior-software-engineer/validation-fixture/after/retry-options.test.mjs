import assert from 'node:assert/strict';
import test from 'node:test';
import { createRetryOptions } from './retry-options.mjs';

test('omitted options retain public defaults', () => {
  assert.deepEqual(createRetryOptions(), { maxRetries: 3, delayMs: 100 });
});
test('explicit undefined retains defaults', () => {
  assert.deepEqual(createRetryOptions({ maxRetries: undefined, delayMs: undefined }), { maxRetries: 3, delayMs: 100 });
});
test('explicit null retains defaults', () => {
  assert.deepEqual(createRetryOptions({ maxRetries: null, delayMs: null }), { maxRetries: 3, delayMs: 100 });
});
test('zero maxRetries disables retries', () => {
  assert.deepEqual(createRetryOptions({ maxRetries: 0 }), { maxRetries: 0, delayMs: 100 });
});
test('zero delayMs requests immediate retry', () => {
  assert.deepEqual(createRetryOptions({ delayMs: 0 }), { maxRetries: 3, delayMs: 0 });
});
test('two explicit zeros remain zero', () => {
  assert.deepEqual(createRetryOptions({ maxRetries: 0, delayMs: 0 }), { maxRetries: 0, delayMs: 0 });
});
test('positive values retain their meaning', () => {
  assert.deepEqual(createRetryOptions({ maxRetries: 5, delayMs: 250 }), { maxRetries: 5, delayMs: 250 });
});
test('frozen input is read without mutation', () => {
  const input = Object.freeze({ maxRetries: 0, delayMs: 25, requestId: 'fixture-only' });
  const result = createRetryOptions(input);
  assert.deepEqual(result, { maxRetries: 0, delayMs: 25 });
  assert.deepEqual(input, { maxRetries: 0, delayMs: 25, requestId: 'fixture-only' });
});
test('output shape stays stable and no additional input fields leak', () => {
  const result = createRetryOptions({ maxRetries: 1, delayMs: 20, debug: true });
  assert.deepEqual(Object.keys(result).sort(), ['delayMs', 'maxRetries']);
});
