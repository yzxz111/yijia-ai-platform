import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTaskServer } from './server.mjs';
import { updateTask } from './client.mjs';

async function fixture(fn) {
  const tasks = new Map([['1', { id: '1', owner: 'alice', title: 'A', done: false }], ['3', { id: '3', owner: 'bob', title: 'B', done: false }]]);
  const server = createTaskServer({ tasks, resolveUser: req => ({ alice: 'alice', bob: 'bob' })[req.headers.authorization] || null });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = (route, method = 'GET', value, token = 'alice') => fetch(base + route, { method, headers: { Authorization: token, 'Content-Type': 'application/json' }, ...(value === undefined ? {} : { body: JSON.stringify(value) }) });
  try { await fn({ tasks, base, request }); } finally { await new Promise(resolve => server.close(resolve)); }
}
test('missing identity cannot list tasks', async () => fixture(async ({ request }) => {
  const r = await request('/api/tasks', 'GET', undefined, ''); assert.equal(r.status, 401);
}));
test('list contains only the authenticated owner records', async () => fixture(async ({ request }) => {
  const r = await request('/api/tasks'); assert.equal(r.status, 200); assert.deepEqual((await r.json()).map(x => x.id), ['1']);
}));
test('new task trims title and cannot collide with sparse existing ids', async () => fixture(async ({ request, tasks }) => {
  const r = await request('/api/tasks', 'POST', { title: '  New  ' }); assert.equal(r.status, 201); const t = await r.json();
  assert.equal(t.title, 'New'); assert.equal(t.owner, 'alice'); assert.equal(t.done, false); assert.ok(!['1', '3'].includes(t.id)); assert.equal(tasks.size, 3); assert.equal(tasks.get('3').owner, 'bob');
}));
test('blank and oversized titles are rejected without writes', async () => fixture(async ({ request, tasks }) => {
  for (const title of ['   ', 'a'.repeat(81), 42]) { const r = await request('/api/tasks', 'POST', { title }); assert.equal(r.status, 400); }
  assert.equal(tasks.size, 2);
}));
test('foreign update returns 404 and leaves another owner record unchanged', async () => fixture(async ({ request, tasks }) => {
  const r = await request('/api/tasks/3', 'PATCH', { done: true }); assert.equal(r.status, 404); assert.equal(tasks.get('3').done, false);
}));
test('boolean state crosses the real local HTTP boundary', async () => fixture(async ({ base, tasks }) => {
  const value = await updateTask(base, '1', true, 'alice'); assert.equal(value.done, true); assert.equal(tasks.get('1').done, true);
}));
test('string false is rejected without changing a boolean', async () => fixture(async ({ request, tasks }) => {
  const r = await request('/api/tasks/1', 'PATCH', { done: 'false' }); assert.equal(r.status, 400); assert.equal(tasks.get('1').done, false);
}));
test('client rejects HTTP failure with status', async () => fixture(async ({ base }) => {
  await assert.rejects(updateTask(base, 'missing', true, 'alice'), error => error.status === 404);
}));
test('malformed JSON returns 400 and subsequent request still works', async () => fixture(async ({ base, request, tasks }) => {
  const r = await fetch(base + '/api/tasks', { method: 'POST', headers: { Authorization: 'alice', 'Content-Type': 'application/json' }, body: '{bad' });
  assert.equal(r.status, 400); assert.equal(tasks.size, 2); assert.equal((await request('/api/tasks')).status, 200);
}));
