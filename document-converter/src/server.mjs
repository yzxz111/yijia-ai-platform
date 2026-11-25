import { createHash, timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

import { positiveInteger } from './config.mjs';
import { ConversionError, convertDocument } from './converter.mjs';

const MEDIA_TYPES = Object.freeze({
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
});

export function constantTimeTokenMatch(candidate, expected) {
  const candidateDigest = createHash('sha256').update(String(candidate ?? '')).digest();
  const expectedDigest = createHash('sha256').update(String(expected ?? '')).digest();
  return timingSafeEqual(candidateDigest, expectedDigest);
}

export function createConverterServer(options = {}) {
  const authToken = String(options.authToken ?? '');
  if (!authToken) throw new Error('document_converter_auth_token_required');
  const maxInputBytes = positiveInteger(options.maxInputBytes, 20 * 1024 * 1024);
  const maxOutputBytes = positiveInteger(options.maxOutputBytes, 20 * 1024 * 1024);
  const requestTimeoutMs = positiveInteger(options.timeoutMs, 20_000);
  const executor = new BoundedExecutor(
    positiveInteger(options.workerCount, 2),
    positiveInteger(options.maxPending, 4),
  );
  const convert = options.convert ?? ((request) => convertDocument(request, {
    tempRoot: options.tempRoot,
    registryTemplatePath: options.registryTemplatePath,
    sofficePath: options.sofficePath,
    maxInputBytes,
    maxOutputBytes,
    timeoutMs: requestTimeoutMs,
    killReapTimeoutMs: options.killReapTimeoutMs,
  }));

  return createServer((request, response) => {
    if (request.method === 'GET'
        && (request.url === '/health' || request.url === '/health/live')) {
      writeJson(response, 200, { status: 'ok' });
      return;
    }
    if (request.method === 'GET' && request.url === '/health/ready') {
      const capacity = executor.capacity();
      writeJson(response, capacity.ready ? 200 : 503, {
        status: capacity.ready ? 'ready' : 'not_ready',
        active: capacity.active,
        pending: capacity.pending,
        workerCapacity: capacity.workerCapacity,
        pendingCapacity: capacity.pendingCapacity,
      });
      return;
    }
    if (request.method !== 'POST' || request.url !== '/convert') {
      writeJson(response, 404, { error: { code: 'not_found' } });
      return;
    }
    if (!authorized(request.headers.authorization, authToken)) {
      request.resume();
      writeJson(response, 401, { error: { code: 'unauthorized' } });
      return;
    }
    const format = String(request.headers['x-source-format'] ?? '').toLowerCase();
    if (format !== 'doc' && format !== 'xls') {
      request.resume();
      writeJson(response, 400, { error: { code: 'unsupported_file_type' } });
      return;
    }
    const contentType = String(request.headers['content-type'] ?? '')
      .split(';', 1)[0].trim().toLowerCase();
    if (contentType !== 'application/octet-stream') {
      request.resume();
      writeJson(response, 400, { error: { code: 'invalid_file_content' } });
      return;
    }
    const contentLength = Number(request.headers['content-length'] ?? 0);
    if (Number.isFinite(contentLength) && contentLength > maxInputBytes) {
      terminateRequestAfterResponse(request, response);
      writeJson(response, 413, { error: { code: 'file_too_large' } });
      return;
    }

    const controller = new AbortController();
    request.once('aborted', () => controller.abort());
    response.once('close', () => {
      if (!response.writableEnded) controller.abort();
    });
    const deadlineFailure = new ConversionError(504, 'multimodal_preprocessing_timeout');
    const deadlineTimer = setTimeout(() => {
      controller.abort(deadlineFailure);
      if (!response.headersSent && !response.destroyed) {
        terminateRequestAfterResponse(request, response);
        writeStableError(response, deadlineFailure);
      }
    }, requestTimeoutMs);
    const accepted = executor.submit(async () => {
      try {
        const content = await readBoundedBody(request, maxInputBytes, controller.signal);
        if (content.length === 0) throw new ConversionError(400, 'invalid_file_content');
        const converted = await convert({ format, content, signal: controller.signal });
        if (!Buffer.isBuffer(converted?.content)
            || converted.content.length === 0
            || converted.content.length > maxOutputBytes
            || !MEDIA_TYPES[converted.format]) {
          throw new ConversionError(422, 'document_conversion_failed');
        }
        response.writeHead(200, {
          'content-type': MEDIA_TYPES[converted.format],
          'content-length': converted.content.length,
          'cache-control': 'no-store',
        });
        response.end(converted.content);
      } catch (error) {
        if (!response.headersSent && !response.destroyed) {
          if (error instanceof ConversionError
              && (error.status === 413 || error.status === 504)) {
            terminateRequestAfterResponse(request, response);
          }
          writeStableError(response, error);
        }
      } finally {
        clearTimeout(deadlineTimer);
      }
    });
    if (!accepted) {
      clearTimeout(deadlineTimer);
      controller.abort();
      request.resume();
      writeJson(response, 503, { error: { code: 'document_converter_unavailable' } });
    }
  });
}

function authorized(header, expectedToken) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  return constantTimeTokenMatch(header.slice('Bearer '.length), expectedToken);
}

function readBoundedBody(request, maxBytes, signal) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(error);
    };
    const onData = (chunk) => {
      bytes += chunk.length;
      if (bytes > maxBytes) {
        request.pause();
        fail(new ConversionError(413, 'file_too_large'));
        return;
      }
      chunks.push(chunk);
    };
    const onEnd = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(Buffer.concat(chunks, bytes));
    };
    const onError = () => fail(new ConversionError(400, 'invalid_file_content'));
    const onAbort = () => fail(
      signal?.reason instanceof ConversionError
        ? signal.reason
        : new ConversionError(499, 'document_conversion_cancelled'),
    );
    const cleanup = () => {
      request.removeListener('data', onData);
      request.removeListener('end', onEnd);
      request.removeListener('error', onError);
      signal?.removeEventListener('abort', onAbort);
    };
    request.on('data', onData);
    request.once('end', onEnd);
    request.once('error', onError);
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) onAbort();
  });
}

function terminateRequestAfterResponse(request, response) {
  if (request.complete || request.destroyed) return;
  const terminate = () => {
    response.removeListener('finish', terminate);
    response.removeListener('close', terminate);
    if (!request.destroyed) request.destroy();
  };
  if (response.writableFinished) {
    terminate();
    return;
  }
  response.once('finish', terminate);
  response.once('close', terminate);
}

function writeStableError(response, error) {
  if (error instanceof ConversionError && error.status !== 499) {
    writeJson(response, error.status, { error: { code: error.code } });
    return;
  }
  if (error instanceof ConversionError && error.status === 499) {
    response.destroy();
    return;
  }
  writeJson(response, 422, { error: { code: 'document_conversion_failed' } });
}

function writeJson(response, status, body) {
  const content = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': content.length,
    'cache-control': 'no-store',
  });
  response.end(content);
}

class BoundedExecutor {
  constructor(workerCount, maxPending) {
    this.workerCount = workerCount;
    this.maxPending = maxPending;
    this.active = 0;
    this.pending = [];
  }

  submit(task) {
    if (this.active < this.workerCount) {
      this.start(task);
      return true;
    }
    if (this.pending.length >= this.maxPending) return false;
    this.pending.push(task);
    return true;
  }

  capacity() {
    return {
      ready: this.active < this.workerCount || this.pending.length < this.maxPending,
      active: this.active,
      pending: this.pending.length,
      workerCapacity: this.workerCount,
      pendingCapacity: this.maxPending,
    };
  }

  start(task) {
    this.active += 1;
    Promise.resolve().then(task).finally(() => {
      this.active -= 1;
      const next = this.pending.shift();
      if (next) this.start(next);
    });
  }
}

export function startConverterServerFromEnvironment(environment = process.env) {
  const server = createConverterServer({
    authToken: environment.DOCUMENT_CONVERTER_AUTH_TOKEN,
    maxInputBytes: environment.DOCUMENT_CONVERTER_MAX_INPUT_BYTES,
    maxOutputBytes: environment.DOCUMENT_CONVERTER_MAX_OUTPUT_BYTES,
    workerCount: environment.DOCUMENT_CONVERTER_WORKERS,
    maxPending: environment.DOCUMENT_CONVERTER_MAX_PENDING,
    timeoutMs: environment.DOCUMENT_CONVERTER_TIMEOUT_MS,
    killReapTimeoutMs: environment.DOCUMENT_CONVERTER_KILL_REAP_TIMEOUT_MS,
    tempRoot: environment.DOCUMENT_CONVERTER_TEMP_ROOT,
    sofficePath: environment.SOFFICE_PATH,
    registryTemplatePath: environment.DOCUMENT_CONVERTER_REGISTRY_TEMPLATE,
  });
  const port = positiveInteger(environment.PORT, 8080);
  server.listen(port, '0.0.0.0');
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  startConverterServerFromEnvironment();
}
