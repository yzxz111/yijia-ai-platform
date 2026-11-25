import { spawn } from 'node:child_process';
import { constants as fileConstants } from 'node:fs';
import {
  copyFile,
  lstat,
  mkdir,
  mkdtemp,
  open,
  rm,
  writeFile,
} from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

import { positiveInteger } from './config.mjs';

const FORMATS = Object.freeze({
  doc: Object.freeze({ inputExtension: 'doc', outputExtension: 'docx' }),
  xls: Object.freeze({ inputExtension: 'xls', outputExtension: 'xlsx' }),
});

export class ConversionError extends Error {
  constructor(status, code) {
    super(code);
    this.name = 'ConversionError';
    this.status = status;
    this.code = code;
  }
}

export async function convertDocument(request, options = {}) {
  const format = FORMATS[request?.format];
  if (!format) throw new ConversionError(400, 'unsupported_file_type');

  const content = Buffer.isBuffer(request?.content)
    ? request.content
    : Buffer.from(request?.content ?? []);
  const maxInputBytes = positiveInteger(options.maxInputBytes, 20 * 1024 * 1024);
  const maxOutputBytes = positiveInteger(options.maxOutputBytes, 20 * 1024 * 1024);
  const timeoutMs = positiveInteger(options.timeoutMs, 20_000);
  const killReapTimeoutMs = positiveInteger(options.killReapTimeoutMs, 1000);
  if (content.length === 0) throw new ConversionError(400, 'invalid_file_content');
  if (content.length > maxInputBytes) throw new ConversionError(413, 'file_too_large');
  if (request.signal?.aborted) throw abortFailure(request.signal);

  const tempRoot = options.tempRoot ?? '/tmp/document-converter';
  const registryTemplatePath = options.registryTemplatePath
    ?? '/app/registrymodifications.xcu';
  const sofficePath = options.sofficePath ?? '/usr/bin/soffice';
  const spawnProcess = options.spawnProcess ?? spawn;
  await mkdir(tempRoot, { recursive: true, mode: 0o700 });
  const jobDirectory = await mkdtemp(join(tempRoot, 'job-'));

  try {
    const inputPath = join(jobDirectory, `input.${format.inputExtension}`);
    const outputDirectory = join(jobDirectory, 'output');
    const profileDirectory = join(jobDirectory, 'profile');
    const profileUserDirectory = join(profileDirectory, 'user');
    await mkdir(outputDirectory, { mode: 0o700 });
    await mkdir(profileUserDirectory, { recursive: true, mode: 0o700 });
    await copyFile(
      registryTemplatePath,
      join(profileUserDirectory, 'registrymodifications.xcu'),
    );
    await writeFile(inputPath, content, { flag: 'wx', mode: 0o600 });

    await runLibreOffice({
      spawnProcess,
      sofficePath,
      inputPath,
      outputDirectory,
      profileDirectory,
      workingDirectory: jobDirectory,
      outputExtension: format.outputExtension,
      timeoutMs,
      killReapTimeoutMs,
      signal: request.signal,
    });

    const outputPath = join(outputDirectory, `input.${format.outputExtension}`);
    const outputStat = await safeOutputStat(outputPath);
    if (!outputStat.isFile() || outputStat.size <= 0) {
      throw new ConversionError(422, 'document_conversion_failed');
    }
    if (outputStat.size > maxOutputBytes) {
      throw new ConversionError(413, 'file_too_large');
    }
    const handle = await open(outputPath, fileConstants.O_RDONLY | fileConstants.O_NOFOLLOW);
    try {
      const converted = await handle.readFile();
      if (converted.length === 0 || converted.length > maxOutputBytes) {
        throw new ConversionError(
          converted.length > maxOutputBytes ? 413 : 422,
          converted.length > maxOutputBytes ? 'file_too_large' : 'document_conversion_failed',
        );
      }
      return { format: format.outputExtension, content: converted };
    } finally {
      await handle.close();
    }
  } catch (error) {
    if (error instanceof ConversionError) throw error;
    throw new ConversionError(422, 'document_conversion_failed');
  } finally {
    await rm(jobDirectory, { recursive: true, force: true, maxRetries: 2 });
  }
}

function runLibreOffice({
  spawnProcess,
  sofficePath,
  inputPath,
  outputDirectory,
  profileDirectory,
  workingDirectory,
  outputExtension,
  timeoutMs,
  killReapTimeoutMs,
  signal,
}) {
  return new Promise((resolve, reject) => {
    let child;
    let settled = false;
    let stopRequested = false;
    let stopFailure;
    let reapTimer;
    const onStderr = () => { };
    const onError = () => {
      if (!stopRequested) finish(new ConversionError(503, 'document_converter_unavailable'));
    };
    const onExit = (code) => {
      if (!stopRequested) finish(resultFor(code));
    };
    const onClose = (code) => finish(stopFailure ?? resultFor(code));
    const detach = () => {
      signal?.removeEventListener('abort', onAbort);
      child?.stderr?.removeListener?.('data', onStderr);
      child?.removeListener?.('error', onError);
      child?.removeListener?.('exit', onExit);
      child?.removeListener?.('close', onClose);
    };
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearTimeout(reapTimer);
      detach();
      if (error) reject(error);
      else resolve();
    };
    const resultFor = (code) => {
      return code === 0 ? undefined : new ConversionError(422, 'document_conversion_failed');
    };
    const stop = (failure) => {
      if (stopRequested) return;
      stopRequested = true;
      stopFailure = failure;
      try {
        child?.kill('SIGKILL');
      } catch {
        // 调用方仍然会收到稳定的超时或取消错误。
      }
      if (!settled) reapTimer = setTimeout(() => finish(stopFailure), killReapTimeoutMs);
    };
    const onAbort = () => {
      if (settled) return;
      stop(abortFailure(signal));
    };
    const timer = setTimeout(() => {
      if (settled) return;
      stop(new ConversionError(504, 'multimodal_preprocessing_timeout'));
    }, timeoutMs);

    try {
      child = spawnProcess(sofficePath, [
        '--headless', '--invisible', '--nologo', '--nodefault', '--nolockcheck',
        '--nofirststartwizard', '--norestore',
        `-env:UserInstallation=${pathToFileURL(profileDirectory).href}`,
        '--convert-to', outputExtension,
        '--outdir', outputDirectory,
        inputPath,
      ], {
        stdio: ['ignore', 'ignore', 'pipe'],
        shell: false,
        cwd: workingDirectory,
        env: isolatedOfficeEnvironment(profileDirectory, workingDirectory),
      });
    } catch {
      finish(new ConversionError(503, 'document_converter_unavailable'));
      return;
    }

    child.stderr?.on('data', onStderr);
    child.once('error', onError);
    child.once('exit', onExit);
    child.once('close', onClose);
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) onAbort();
  });
}

function isolatedOfficeEnvironment(profileDirectory, tempDirectory) {
  return {
    HOME: profileDirectory,
    TMPDIR: tempDirectory,
    PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
    LANG: 'C.UTF-8',
    LC_ALL: 'C.UTF-8',
    SAL_USE_VCLPLUGIN: 'svp',
    SAL_DISABLE_OPENCL: '1',
  };
}

function abortFailure(signal) {
  return signal?.reason instanceof ConversionError
    ? signal.reason
    : new ConversionError(499, 'document_conversion_cancelled');
}

async function safeOutputStat(outputPath) {
  try {
    const result = await lstat(outputPath);
    if (result.isSymbolicLink()) throw new ConversionError(422, 'document_conversion_failed');
    return result;
  } catch (error) {
    if (error instanceof ConversionError) throw error;
    throw new ConversionError(422, 'document_conversion_failed');
  }
}
