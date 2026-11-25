import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

import { convertDocument } from '../src/converter.mjs';

const execute = promisify(execFile);
const testDirectory = dirname(fileURLToPath(import.meta.url));
const sofficePath = process.env.SOFFICE_PATH ?? '/usr/bin/soffice';
const registryTemplatePath = resolve(testDirectory, '..', 'registrymodifications.xcu');
const sofficeAvailable = await executableExists(sofficePath);

test('converts real legacy DOC and XLS files with installed LibreOffice', {
  skip: sofficeAvailable ? false : `LibreOffice is unavailable at ${sofficePath}`,
}, async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'real-converter-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));

  for (const format of ['doc', 'xls']) {
    const content = await createLegacyFixture(root, format);
    const converted = await convertDocument({ format, content }, {
      tempRoot: join(root, 'jobs'),
      registryTemplatePath,
      sofficePath,
      maxInputBytes: 2 * 1024 * 1024,
      maxOutputBytes: 2 * 1024 * 1024,
      timeoutMs: 20_000,
      killReapTimeoutMs: 1_000,
    });

    assert.equal(converted.format, format === 'doc' ? 'docx' : 'xlsx');
    assert.ok(converted.content.length > 4);
    assert.equal(converted.content.subarray(0, 2).toString('ascii'), 'PK');
  }
});

async function createLegacyFixture(root, format) {
  const sourceDirectory = join(root, `fixture-${format}`);
  const outputDirectory = join(sourceDirectory, 'output');
  const profileDirectory = join(sourceDirectory, 'profile');
  await mkdir(outputDirectory, { recursive: true });
  const fixture = format === 'doc'
    ? {
        sourceName: 'source.txt',
        outputName: 'source.doc',
        filter: 'doc:MS Word 97',
        content: 'Real LibreOffice document converter fixture.\n',
      }
    : {
        sourceName: 'source.csv',
        outputName: 'source.xls',
        filter: 'xls:MS Excel 97',
        content: 'name,value\nfixture,1\n',
      };
  const sourcePath = join(sourceDirectory, fixture.sourceName);
  await writeFile(sourcePath, fixture.content, 'utf8');
  await execute(sofficePath, [
    '--headless', '--nologo', '--nodefault', '--nolockcheck',
    '--nofirststartwizard', '--norestore',
    `-env:UserInstallation=${pathToFileURL(profileDirectory).href}`,
    '--convert-to', fixture.filter,
    '--outdir', outputDirectory,
    sourcePath,
  ], {
    timeout: 20_000,
    windowsHide: true,
    env: isolatedEnvironment(profileDirectory, sourceDirectory),
  });
  return readFile(join(outputDirectory, fixture.outputName));
}

function isolatedEnvironment(profileDirectory, tempDirectory) {
  return {
    ...process.env,
    HOME: profileDirectory,
    TMPDIR: tempDirectory,
    SAL_USE_VCLPLUGIN: 'svp',
    SAL_DISABLE_OPENCL: '1',
  };
}

async function executableExists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}
