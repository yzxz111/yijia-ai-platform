import test from 'node:test';
import assert from 'node:assert/strict';
import { createSearchController } from './search-controller.mjs';

// This is an injected callback transport, NOT wx.request or a real HTTP server.
function harness({ throwOnRequest = false } = {}) {
  let state = { query: '', items: [], status: 'idle', loading: false, error: '' };
  const writes = [];
  const jobs = [];
  const controller = createSearchController({
    publish(patch) { state = { ...state, ...patch }; writes.push(structuredClone(state)); },
    request(options) {
      if (throwOnRequest) throw new Error('transport unavailable');
      const job = {
        options,
        ok(items, statusCode = 200) {
          options.success?.({ statusCode, data: { ok: true, items } });
          options.complete?.({});
        },
        fail() { options.fail?.({ errMsg: 'request:fail' }); options.complete?.({}); },
        raw(response) { options.success?.(response); options.complete?.({}); },
      };
      jobs.push(job);
      return { abort() { job.fail(); } };
    },
  });
  return { ...controller, jobs, writes, get state() { return structuredClone(state); } };
}
const result = (id) => [{ id, title: id }];
function expectError(h) {
  assert.equal(h.state.status, 'error');
  assert.equal(h.state.loading, false);
  assert.deepEqual(h.state.items, []);
  assert.equal(typeof h.state.error, 'string');
  assert.ok(h.state.error.length > 0);
}

test('normal query is trimmed and callback result becomes ready', () => {
  const h = harness(); h.search('  alpha  ');
  assert.equal(h.jobs.length, 1);
  assert.equal(h.jobs[0].options.data.q, 'alpha');
  assert.equal(h.state.loading, true);
  h.jobs[0].ok(result('alpha'));
  assert.deepEqual(h.state, { query: 'alpha', items: result('alpha'), status: 'ready', loading: false, error: '' });
});
test('old success after new success cannot overwrite any state', () => {
  const h = harness(); h.search('a'); h.search('ab');
  h.jobs[1].ok(result('ab')); const latest = h.state; const count = h.writes.length;
  h.jobs[0].ok(result('a'));
  assert.deepEqual(h.state, latest); assert.equal(h.writes.length, count);
});
test('old failure and completion cannot stop a new loading state', () => {
  const h = harness(); h.search('a'); h.search('ab');
  const active = h.state; const count = h.writes.length; h.jobs[0].fail();
  assert.deepEqual(h.state, active); assert.equal(h.writes.length, count);
  h.jobs[1].ok(result('ab')); assert.equal(h.state.status, 'ready');
});
test('old completion on its own cannot stop current loading', () => {
  const h = harness(); h.search('a'); h.search('b');
  const count = h.writes.length; h.jobs[0].options.complete?.({});
  assert.equal(h.state.loading, true); assert.equal(h.writes.length, count);
});
test('clearing input issues no request and invalidates previous success', () => {
  const h = harness(); h.search('a'); h.search('   ');
  assert.equal(h.jobs.length, 1);
  const cleared = { query: '', items: [], status: 'idle', loading: false, error: '' };
  assert.deepEqual(h.state, cleared); const count = h.writes.length;
  h.jobs[0].ok(result('a')); assert.deepEqual(h.state, cleared); assert.equal(h.writes.length, count);
});
test('clearing null input also invalidates a previous failure', () => {
  const h = harness(); h.search('a'); h.search(null); const count = h.writes.length;
  h.jobs[0].fail();
  assert.deepEqual(h.state, { query: '', items: [], status: 'idle', loading: false, error: '' });
  assert.equal(h.jobs.length, 1); assert.equal(h.writes.length, count);
});
test('current network failure permits another search of the same query', () => {
  const h = harness(); h.search('a'); h.jobs[0].fail(); expectError(h);
  h.search('a'); assert.equal(h.state.error, ''); assert.equal(h.state.loading, true);
  h.jobs[1].ok(result('retry')); assert.equal(h.state.status, 'ready');
  const latest = h.state; h.jobs[0].options.complete?.({}); assert.deepEqual(h.state, latest);
});
test('HTTP 500 delivered to success is still an error', () => {
  const h = harness(); h.search('a'); h.jobs[0].ok(result('server-error'), 500); expectError(h);
});
test('HTTP 401 delivered to success is still an error', () => {
  const h = harness(); h.search('a'); h.jobs[0].ok(result('unauthorized'), 401); expectError(h);
});
test('HTTP 200 with rejected business result is an error', () => {
  const h = harness(); h.search('a');
  h.jobs[0].raw({ statusCode: 200, data: { ok: false, items: result('rejected'), message: 'not allowed' } }); expectError(h);
});
test('malformed items cannot become successful results', () => {
  const h = harness(); h.search('a');
  assert.doesNotThrow(() => h.jobs[0].raw({ statusCode: 200, data: { ok: true, items: { length: 1 } } })); expectError(h);
});
test('empty array is a valid empty result', () => {
  const h = harness(); h.search('a'); h.jobs[0].ok([]);
  assert.equal(h.state.status, 'empty'); assert.equal(h.state.loading, false); assert.equal(h.state.error, '');
});
test('dispose prevents late callbacks and later searches from writing', () => {
  const h = harness(); h.search('a'); h.dispose();
  const afterDispose = h.state; const count = h.writes.length;
  h.jobs[0].ok(result('late')); h.jobs[0].fail(); h.search('b');
  assert.deepEqual(h.state, afterDispose); assert.equal(h.writes.length, count); assert.equal(h.jobs.length, 1);
});
test('synchronous adapter failure becomes a recoverable error state', () => {
  const h = harness({ throwOnRequest: true });
  assert.doesNotThrow(() => h.search('a')); expectError(h);
});
test('two controller instances keep independent current requests', () => {
  const a = harness(); const b = harness();
  a.search('a'); b.search('b'); a.jobs[0].ok(result('a')); b.jobs[0].ok(result('b'));
  assert.deepEqual(a.state.items, result('a')); assert.deepEqual(b.state.items, result('b'));
});
