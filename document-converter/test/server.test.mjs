import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import test from 'node:test';

import { ConversionError } from '../src/converter.mjs';
import { constantTimeTokenMatch, createConverterServer } from '../src/server.mjs';

const TOKEN = 'converter-test-token-with-sufficient-entropy';

test('health is public and bearer authentication uses fixed-length digest comparison', async (t) => {
  await withServer(t, {}, async (baseUrl) => {
    const health = await fetch(`${baseUrl}/health`);
    const liveness = await fetch(`${baseUrl}/health/live`);
    const readiness = await fetch(`${baseUrl}/health/ready`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: 'ok' });
    assert.equal(liveness.status, 200);
    assert.deepEqual(await liveness.json(), { status: 'ok' });
    assert.equal(readiness.status, 200);
    assert.deepEqual(await readiness.json(), {
      status: 'ready',
      active: 0,
      pending: 0,
      workerCapacity: 2,
      pendingCapacity: 2,
    });

    const missing = await fetch(`${baseUrl}/convert`, requestOptions('doc', Buffer.from('doc'), null));
    const wrong = await fetch(`${baseUrl}/convert`, requestOptions('doc', Buffer.from('doc'), 'short'));
    assert.equal(missing.status, 401);
    assert.equal(wrong.status, 401);
    assert.deepEqual(await wrong.json(), { error: { code: 'unauthorized' } });
  });

  assert.equal(constantTimeTokenMatch(TOKEN, TOKEN), true);
  assert.equal(constantTimeTokenMatch('short', TOKEN), false);
  assert.equal(constantTimeTokenMatch(`${TOKEN}x`, TOKEN), false);
});

test('forwards only DOC/XLS bytes and returns the matching OOXML media type', async (t) => {
  const calls = [];
  await withServer(t, {
    convert: async (request) => {
      calls.push(request);
      return {
        format: request.format === 'doc' ? 'docx' : 'xlsx',
        content: Buffer.from(`converted-${request.format}`),
      };
    },
  }, async (baseUrl) => {
    const doc = await fetch(
      `${baseUrl}/convert`, requestOptions('doc', Buffer.from('doc-bytes'), TOKEN),
    );
    const xls = await fetch(
      `${baseUrl}/convert`, requestOptions('xls', Buffer.from('xls-bytes'), TOKEN),
    );

    assert.equal(doc.status, 200);
    assert.equal(
      doc.headers.get('content-type'),
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    );
    assert.equal(Buffer.from(await doc.arrayBuffer()).toString(), 'converted-doc');
    assert.equal(xls.status, 200);
    assert.equal(
      xls.headers.get('content-type'),
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    );
    assert.equal(Buffer.from(await xls.arrayBuffer()).toString(), 'converted-xls');
  });

  assert.equal(calls.length, 2);
  assert.equal(calls[0].format, 'doc');
  assert.equal(calls[0].content.toString(), 'doc-bytes');
  assert.ok(calls[0].signal instanceof AbortSignal);
  assert.equal(calls[1].format, 'xls');
});

test('rejects invalid format, content type, empty body, and oversized request', async (t) => {
  await withServer(t, { maxInputBytes: 4 }, async (baseUrl) => {
    const unsupported = await fetch(
      `${baseUrl}/convert`, requestOptions('ppt', Buffer.from('x'), TOKEN),
    );
    const mediaType = await fetch(`${baseUrl}/convert`, {
      ...requestOptions('doc', Buffer.from('x'), TOKEN),
      headers: {
        ...requestOptions('doc', Buffer.from('x'), TOKEN).headers,
        'content-type': 'text/plain',
      },
    });
    const empty = await fetch(
      `${baseUrl}/convert`, requestOptions('doc', Buffer.alloc(0), TOKEN),
    );
    const oversized = await fetch(
      `${baseUrl}/convert`, requestOptions('xls', Buffer.alloc(5), TOKEN),
    );

    assert.equal(unsupported.status, 400);
    assert.equal((await unsupported.json()).error.code, 'unsupported_file_type');
    assert.equal(mediaType.status, 400);
    assert.equal((await mediaType.json()).error.code, 'invalid_file_content');
    assert.equal(empty.status, 400);
    assert.equal((await empty.json()).error.code, 'invalid_file_content');
    assert.equal(oversized.status, 413);
    assert.equal((await oversized.json()).error.code, 'file_too_large');
  });
});

test('bounds active workers and pending queue', async (t) => {
  const releases = [];
  await withServer(t, {
    workerCount: 1,
    maxPending: 1,
    convert: ({ format }) => new Promise((resolve) => {
      releases.push(() => resolve({ format: format === 'doc' ? 'docx' : 'xlsx', content: Buffer.from('ok') }));
    }),
  }, async (baseUrl) => {
    const first = fetch(`${baseUrl}/convert`, requestOptions('doc', Buffer.from('a'), TOKEN));
    await waitFor(() => releases.length === 1);
    const second = fetch(`${baseUrl}/convert`, requestOptions('xls', Buffer.from('b'), TOKEN));
    await new Promise((resolve) => setTimeout(resolve, 20));
    const saturated = await fetch(
      `${baseUrl}/convert`, requestOptions('doc', Buffer.from('c'), TOKEN),
    );

    assert.equal(saturated.status, 503);
    assert.deepEqual(await saturated.json(), {
      error: { code: 'document_converter_unavailable' },
    });
    const notReady = await fetch(`${baseUrl}/health/ready`);
    assert.equal(notReady.status, 503);
    assert.deepEqual(await notReady.json(), {
      status: 'not_ready',
      active: 1,
      pending: 1,
      workerCapacity: 1,
      pendingCapacity: 1,
    });
    assert.equal((await fetch(`${baseUrl}/health/live`)).status, 200);

    releases.shift()();
    assert.equal((await first).status, 200);
    await waitFor(() => releases.length === 1);
    assert.equal((await fetch(`${baseUrl}/health/ready`)).status, 200);
    releases.shift()();
    assert.equal((await second).status, 200);
  });
});

test('enforces the request deadline while work is still queued', async (t) => {
  let releaseFirst;
  let calls = 0;
  await withServer(t, {
    workerCount: 1,
    maxPending: 1,
    timeoutMs: 30,
    convert: async ({ format }) => {
      calls += 1;
      if (calls === 1) {
        return new Promise((resolve) => { releaseFirst = resolve; });
      }
      return {
        format: format === 'doc' ? 'docx' : 'xlsx',
        content: Buffer.from('ok'),
      };
    },
  }, async (baseUrl) => {
    const first = fetch(`${baseUrl}/convert`, requestOptions('doc', Buffer.from('a'), TOKEN));
    await waitFor(() => releaseFirst !== undefined);
    const queued = fetch(`${baseUrl}/convert`, requestOptions('xls', Buffer.from('b'), TOKEN));

    try {
      const response = await within(queued, 250);
      assert.equal(response.status, 504);
      assert.deepEqual(await response.json(), {
        error: { code: 'multimodal_preprocessing_timeout' },
      });
      assert.equal(calls, 1);
    } finally {
      releaseFirst({ format: 'docx', content: Buffer.from('ok') });
      await Promise.allSettled([first, queued]);
    }
  });
});

test('enforces the request deadline while reading a slow body', async (t) => {
  await withServer(t, { timeoutMs: 30 }, async (baseUrl) => {
    const pending = partialRequest(baseUrl, Buffer.from('a'));
    try {
      const response = await within(pending.response, 250);
      assert.equal(response.status, 504);
      assert.deepEqual(response.body, {
        error: { code: 'multimodal_preprocessing_timeout' },
      });
      await within(pending.closed, 250);
    } finally {
      pending.request.destroy();
    }
  });
});

test('terminates an oversized streaming request after returning 413', async (t) => {
  let calls = 0;
  await withServer(t, {
    maxInputBytes: 4,
    convert: async () => {
      calls += 1;
      return { format: 'docx', content: Buffer.from('ok') };
    },
  }, async (baseUrl) => {
    const pending = partialRequest(baseUrl, Buffer.alloc(5));
    try {
      const response = await within(pending.response, 250);
      assert.equal(response.status, 413);
      assert.deepEqual(response.body, { error: { code: 'file_too_large' } });
      await within(pending.closed, 250);
      assert.equal(calls, 0);
    } finally {
      pending.request.destroy();
    }
  });
});

test('maps stable process errors without leaking request, token, stderr, or paths', async (t) => {
  const cases = [
    [new ConversionError(422, 'document_conversion_failed'), 422, 'document_conversion_failed'],
    [new ConversionError(503, 'document_converter_unavailable'), 503, 'document_converter_unavailable'],
    [new ConversionError(504, 'multimodal_preprocessing_timeout'), 504, 'multimodal_preprocessing_timeout'],
    [new Error('secret stderr C:\\tmp\\input.doc'), 422, 'document_conversion_failed'],
  ];

  for (const [failure, status, code] of cases) {
    await withServer(t, {
      convert: async () => { throw failure; },
    }, async (baseUrl) => {
      const response = await fetch(`${baseUrl}/convert`, {
        ...requestOptions('doc', Buffer.from('private-document-content'), TOKEN),
        headers: {
          ...requestOptions('doc', Buffer.from('private-document-content'), TOKEN).headers,
          'x-filename': 'private-customer-name.doc',
        },
      });
      const body = await response.text();

      assert.equal(response.status, status);
      assert.deepEqual(JSON.parse(body), { error: { code } });
      assert.doesNotMatch(body, /private|customer|converter-test-token|stderr|tmp|input\.doc/iu);
    });
  }
});

async function withServer(t, overrides, action) {
  const server = createConverterServer({
    authToken: TOKEN,
    maxInputBytes: 1024,
    workerCount: 2,
    maxPending: 2,
    convert: async ({ format }) => ({
      format: format === 'doc' ? 'docx' : 'xlsx',
      content: Buffer.from('converted'),
    }),
    ...overrides,
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  return action(`http://127.0.0.1:${address.port}`);
}

function requestOptions(format, body, token) {
  const headers = {
    'content-type': 'application/octet-stream',
    'x-source-format': format,
  };
  if (token !== null) headers.authorization = `Bearer ${token}`;
  return { method: 'POST', headers, body };
}

function partialRequest(baseUrl, firstChunk) {
  let resolveResponse;
  let rejectResponse;
  const response = new Promise((resolve, reject) => {
    resolveResponse = resolve;
    rejectResponse = reject;
  });
  const request = httpRequest(`${baseUrl}/convert`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${TOKEN}`,
      'content-type': 'application/octet-stream',
      'x-source-format': 'doc',
    },
  }, (incoming) => {
    const chunks = [];
    incoming.on('data', (chunk) => chunks.push(chunk));
    incoming.once('end', () => {
      try {
        resolveResponse({
          status: incoming.statusCode,
          body: JSON.parse(Buffer.concat(chunks).toString()),
        });
      } catch (error) {
        rejectResponse(error);
      }
    });
  });
  request.once('error', rejectResponse);
  const closed = new Promise((resolve) => request.once('close', resolve));
  request.write(firstChunk);
  return { request, response, closed };
}

async function waitFor(condition) {
  const deadline = Date.now() + 1000;
  while (!condition()) {
    if (Date.now() >= deadline) throw new Error('condition_not_met');
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

function within(promise, timeoutMs) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('operation did not settle')), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}
