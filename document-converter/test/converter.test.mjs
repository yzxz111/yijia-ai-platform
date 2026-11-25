import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { PassThrough } from 'node:stream';
import test from 'node:test';

import { ConversionError, convertDocument } from '../src/converter.mjs';

test('converts DOC and XLS with fixed filenames, isolated profiles, and literal arguments', async (t) => {
  const fixture = await converterFixture(t);
  const calls = [];
  const spawnProcess = fakeSpawn({ calls });

  const doc = await convertDocument(
    { format: 'doc', content: Buffer.from('legacy-doc') },
    fixture.options({ spawnProcess }),
  );
  const xls = await convertDocument(
    { format: 'xls', content: Buffer.from('legacy-xls') },
    fixture.options({ spawnProcess }),
  );

  assert.deepEqual(doc, { format: 'docx', content: Buffer.from('converted-docx') });
  assert.deepEqual(xls, { format: 'xlsx', content: Buffer.from('converted-xlsx') });
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.command, '/opt/libreoffice/program/soffice');
    assert.equal(call.options.shell, false);
    assert.deepEqual(call.options.stdio, ['ignore', 'ignore', 'pipe']);
    assert.match(call.options.cwd, /job-/u);
    assert.match(call.options.env.HOME, /profile$/u);
    assert.match(call.options.env.TMPDIR, /job-/u);
    assert.equal(call.options.env.SAL_USE_VCLPLUGIN, 'svp');
    assert.equal(call.options.env.SAL_DISABLE_OPENCL, '1');
    assert.deepEqual(Object.keys(call.options.env).sort(), [
      'HOME', 'LANG', 'LC_ALL', 'PATH', 'SAL_DISABLE_OPENCL', 'SAL_USE_VCLPLUGIN', 'TMPDIR',
    ]);
    assert.ok(call.args.includes('--headless'));
    assert.ok(call.args.includes('--invisible'));
    assert.ok(call.args.includes('--norestore'));
    assert.equal(call.args.some((value) => value.startsWith('--accept')), false);
    assert.ok(call.args.some((value) => value.startsWith('-env:UserInstallation=file:')));
    assert.match(basename(call.args.at(-1)), /^input\.(?:doc|xls)$/u);
    assert.doesNotMatch(call.args.join(' '), /customer|secret|original/iu);
  }
  assert.notEqual(
    calls[0].args.find((value) => value.startsWith('-env:UserInstallation=')),
    calls[1].args.find((value) => value.startsWith('-env:UserInstallation=')),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('rejects unsupported formats and bounded input before creating a job', async (t) => {
  const fixture = await converterFixture(t);
  const options = fixture.options({ maxInputBytes: 4, spawnProcess: fakeSpawn() });

  await assert.rejects(
    convertDocument({ format: 'ppt', content: Buffer.from('x') }, options),
    stableError(400, 'unsupported_file_type'),
  );
  await assert.rejects(
    convertDocument({ format: 'doc', content: Buffer.alloc(5) }, options),
    stableError(413, 'file_too_large'),
  );
  await assert.rejects(
    convertDocument({ format: 'xls', content: Buffer.alloc(0) }, options),
    stableError(400, 'invalid_file_content'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('bounds converted output and cleans jobs after process or output failure', async (t) => {
  const fixture = await converterFixture(t);

  await assert.rejects(
    convertDocument(
      { format: 'doc', content: Buffer.from('doc') },
      fixture.options({ spawnProcess: fakeSpawn({ exitCode: 1 }) }),
    ),
    stableError(422, 'document_conversion_failed'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);

  await assert.rejects(
    convertDocument(
      { format: 'xls', content: Buffer.from('xls') },
      fixture.options({
        maxOutputBytes: 4,
        spawnProcess: fakeSpawn({ output: Buffer.alloc(5) }),
      }),
    ),
    stableError(413, 'file_too_large'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('kills LibreOffice and cleans the job on timeout', async (t) => {
  const fixture = await converterFixture(t);
  let killed = false;

  await assert.rejects(
    convertDocument(
      { format: 'doc', content: Buffer.from('doc') },
      fixture.options({
        timeoutMs: 20,
        spawnProcess: fakeSpawn({
          hang: true,
          onKill: () => { killed = true; },
        }),
      }),
    ),
    stableError(504, 'multimodal_preprocessing_timeout'),
  );

  assert.equal(killed, true);
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('waits for child close before releasing a timed-out job', async (t) => {
  const fixture = await converterFixture(t);
  let killedChild;
  let settled = false;
  const conversion = convertDocument(
    { format: 'doc', content: Buffer.from('doc') },
    fixture.options({
      timeoutMs: 20,
      killReapTimeoutMs: 1000,
      spawnProcess: fakeSpawn({
        hang: true,
        closeOnKill: false,
        onKill: (child) => { killedChild = child; },
      }),
    }),
  );
  void conversion.finally(() => { settled = true; }).catch(() => {});

  await waitFor(() => killedChild !== undefined);
  await new Promise((resolve) => setTimeout(resolve, 75));
  assert.equal(settled, false);
  assert.equal((await readdir(fixture.tempRoot)).length, 1);

  killedChild.emit('close', null, 'SIGKILL');
  await assert.rejects(
    conversion,
    stableError(504, 'multimodal_preprocessing_timeout'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('settles successfully when the child emits exit without close', async (t) => {
  const fixture = await converterFixture(t);
  const conversion = convertDocument(
    { format: 'doc', content: Buffer.from('doc') },
    fixture.options({
      timeoutMs: 1000,
      spawnProcess: fakeSpawn({ exitOnly: true }),
    }),
  );

  await assert.doesNotReject(within(conversion, 250));
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('settles timeout and cleans the job when killing never produces close', async (t) => {
  const fixture = await converterFixture(t);
  const conversion = convertDocument(
    { format: 'xls', content: Buffer.from('xls') },
    fixture.options({
      timeoutMs: 20,
      killReapTimeoutMs: 20,
      spawnProcess: fakeSpawn({ hang: true, closeOnKill: false }),
    }),
  );

  await assert.rejects(
    within(conversion, 250),
    stableError(504, 'multimodal_preprocessing_timeout'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('maps cancellation before a child error and cleans listeners and the job', async (t) => {
  const fixture = await converterFixture(t);
  const controller = new AbortController();
  const conversion = convertDocument(
    { format: 'doc', content: Buffer.from('doc'), signal: controller.signal },
    fixture.options({
      killReapTimeoutMs: 20,
      spawnProcess: fakeSpawn({
        hang: true,
        closeOnKill: false,
        errorOnKill: true,
        onSpawn: () => controller.abort(),
      }),
    }),
  );

  await assert.rejects(
    within(conversion, 250),
    stableError(499, 'document_conversion_cancelled'),
  );
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

test('propagates cancellation, kills the child, and removes temporary input', async (t) => {
  const fixture = await converterFixture(t);
  const controller = new AbortController();
  let killed = false;
  const conversion = convertDocument(
    { format: 'xls', content: Buffer.from('xls'), signal: controller.signal },
    fixture.options({
      spawnProcess: fakeSpawn({
        hang: true,
        onSpawn: () => controller.abort(),
        onKill: () => { killed = true; },
      }),
    }),
  );

  await assert.rejects(conversion, (error) => error?.code === 'document_conversion_cancelled');
  assert.equal(killed, true);
  assert.deepEqual(await readdir(fixture.tempRoot), []);
});

async function converterFixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'converter-test-'));
  const tempRoot = join(root, 'jobs');
  const registryTemplatePath = join(root, 'registrymodifications.xcu');
  await mkdir(tempRoot);
  await writeFile(registryTemplatePath, '<oor:items/>');
  t.after(() => rm(root, { recursive: true, force: true }));
  return {
    tempRoot,
    options(overrides = {}) {
      return {
        tempRoot,
        registryTemplatePath,
        sofficePath: '/opt/libreoffice/program/soffice',
        maxInputBytes: 1024,
        maxOutputBytes: 1024,
        timeoutMs: 1000,
        killReapTimeoutMs: 1000,
        ...overrides,
      };
    },
  };
}

function fakeSpawn({
  calls = [],
  exitCode = 0,
  output,
  hang = false,
  exitOnly = false,
  closeOnKill = true,
  errorOnKill = false,
  onSpawn,
  onKill,
} = {}) {
  return (command, args, options) => {
    const child = new EventEmitter();
    child.stderr = new PassThrough();
    child.kill = () => {
      onKill?.(child);
      queueMicrotask(() => {
        if (errorOnKill) child.emit('error', new Error('killed'));
        if (closeOnKill) child.emit('close', null, 'SIGKILL');
      });
      return true;
    };
    calls.push({ command, args: [...args], options });
    queueMicrotask(() => onSpawn?.());
    if (!hang) {
      setImmediate(async () => {
        try {
          if (exitCode === 0) {
            const outdirIndex = args.indexOf('--outdir');
            const outputDirectory = args[outdirIndex + 1];
            const targetFormat = args[args.indexOf('--convert-to') + 1];
            const bytes = output ?? Buffer.from(`converted-${targetFormat}`);
            await writeFile(join(outputDirectory, `input.${targetFormat}`), bytes);
          }
          child.emit(exitOnly ? 'exit' : 'close', exitCode, null);
        } catch (error) {
          child.emit('error', error);
        }
      });
    }
    return child;
  };
}

function within(promise, timeoutMs) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('conversion did not settle')), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

async function waitFor(condition) {
  const deadline = Date.now() + 1000;
  while (!condition()) {
    if (Date.now() >= deadline) throw new Error('condition_not_met');
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

function stableError(status, code) {
  return (error) => {
    assert.ok(error instanceof ConversionError);
    assert.equal(error.status, status);
    assert.equal(error.code, code);
    assert.doesNotMatch(error.message, /legacy|input|output|tmp|soffice/iu);
    return true;
  };
}
