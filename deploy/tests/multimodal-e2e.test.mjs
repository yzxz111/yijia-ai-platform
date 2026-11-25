import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const composeFile = join(projectRoot, 'deploy', 'compose.yaml');
const composeEnvFile = process.env.E2E_COMPOSE_ENV_FILE ?? join(
  projectRoot, 'deploy', 'env', '.env.example',
);
const baseUrl = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:18081';
const adminEmail = process.env.E2E_ADMIN_EMAIL ?? 'admin@local.test';
// 管理员口令不再是提交进仓库的默认值（compose.yaml 要求必须提供
// LOCAL_ADMIN_PASSWORD），因此这里读取的是启动服务栈时所用的同一个 env 文件。
const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? readComposeEnvironment('LOCAL_ADMIN_PASSWORD');

function readComposeEnvironment(name) {
  const source = readFileSync(composeEnvFile, 'utf8');
  for (const line of source.split(/\r?\n/u)) {
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    if (separator > 0 && line.slice(0, separator).trim() === name) {
      return line.slice(separator + 1).trim().replace(/^["']|["']$/gu, '');
    }
  }
  throw new Error(`${name} is not configured in ${composeEnvFile}; set E2E_ADMIN_PASSWORD instead`);
}
const requireRealProvider = booleanEnvironment('E2E_REQUIRE_REAL_PROVIDER', false);
const e2ePackageVersionId = '00000000-0000-4000-8000-000000000103';
const compositeModel = 'multimodal-basic';
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAFklEQVR4nGP4TyFgGDVg1IBRA4aLAQBdePwur/3haQAAAABJRU5ErkJggg==',
  'base64',
);
const pngDataUri = `data:image/png;base64,${png.toString('base64')}`;

test('unified multimodal model works through the complete local stack', { timeout: 300_000 }, async () => {
  const uploadedFileIds = [];
  const cleanupFailures = [];
  const runId = `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;
  let adminToken;
  let userToken;
  let userId;
  let apiKey;
  let apiKeyId;

  try {
    const adminSession = await jsonRequest('/console/platform/auth/login', {
      method: 'POST',
      expectedStatus: 200,
      body: { email: adminEmail, password: adminPassword },
    });
    adminToken = requiredString(adminSession.accessToken, 'admin access token');

    const temporaryUserId = randomUUID();
    const temporaryEmail = `multimodal-${runId}@local.test`;
    const temporaryPassword = `E2e!${randomUUID().replaceAll('-', '')}Aa1`;
    provisionTemporaryUser({
      userId: temporaryUserId,
      email: temporaryEmail,
      password: temporaryPassword,
      displayName: `多模态测试-${runId.slice(-4)}`,
    });
    userId = temporaryUserId;
    const userSession = await jsonRequest('/console/platform/auth/login', {
      method: 'POST',
      expectedStatus: 200,
      body: {
        email: temporaryEmail,
        password: temporaryPassword,
      },
    });
    userToken = requiredString(userSession.accessToken, 'user access token');
    assert.equal(requiredUuid(userSession.user?.id, 'user id'), temporaryUserId);

    await jsonRequest('/console/platform/admin/grants', {
      method: 'POST',
      expectedStatus: 201,
      token: adminToken,
      body: {
        userId,
        packageVersionId: e2ePackageVersionId,
        reason: 'Unified multimodal E2E',
        businessNo: `multimodal-e2e-${randomUUID()}`,
      },
    });

    const initialQuota = await poll('temporary package activation', async () => {
      return jsonRequest('/console/gateway/quota/summary', {
        expectedStatus: 200,
        token: userToken,
      });
    }, (summary) => summary.activePackage?.status === 'ACTIVE'
      && summary.activePackage.allowedModels?.includes(compositeModel));

    const createdKey = await jsonRequest('/console/gateway/api-keys', {
      method: 'POST',
      expectedStatus: 201,
      token: userToken,
      body: { name: `Multimodal E2E ${runId}`, allowedModels: [compositeModel] },
    });
    apiKey = requiredString(createdKey.secret, 'API key secret');
    apiKeyId = requiredUuid(createdKey.id, 'API key id');

    assertCompositeModel(await listModels(apiKey));
    runLocalBootstrap();
    assertCompositeModel(await listModels(apiKey));

    const nativeText = Buffer.from('Multimodal integration fixture.\n', 'utf8');
    const legacyDoc = createLegacyFixture('doc');
    const legacyXls = createLegacyFixture('xls');
    const nativeFile = await uploadFile(apiKey, 'native-e2e.txt', 'text/plain', nativeText);
    const docFile = await uploadFile(apiKey, 'legacy-e2e.doc', 'application/msword', legacyDoc);
    const xlsFile = await uploadFile(apiKey, 'legacy-e2e.xls', 'application/vnd.ms-excel', legacyXls);
    uploadedFileIds.push(nativeFile.id, docFile.id, xlsFile.id);

    const realComponents = configuredRealComponents();
    if (requireRealProvider) {
      assert.equal(
        realComponents.size > 0,
        true,
        'real-provider acceptance requires at least one healthy credentialed real component route',
      );
    }
    const completions = [];

    completions.push(await chatCompletion(apiKey, {
      model: compositeModel,
      messages: [{ role: 'user', content: 'Reply with one short acknowledgement.' }],
    }, 'text-basic', adminToken, userId, realComponents.has('text-basic')));

    completions.push(await responsesCompletion(apiKey, {
      model: compositeModel,
      input: [{
        role: 'user',
        content: [
          { type: 'input_text', text: 'Describe this image briefly.' },
          { type: 'input_image', image_url: pngDataUri },
        ],
      }],
    }, 'vision-basic', adminToken, userId, realComponents.has('vision-basic')));

    completions.push(await responsesCompletion(apiKey, {
      model: compositeModel,
      input: [{
        role: 'user',
        content: [
          { type: 'input_text', text: 'Summarize both documents.' },
          { type: 'input_file', file_id: nativeFile.id },
          { type: 'input_file', file_id: docFile.id },
        ],
      }],
    }, 'file-basic', adminToken, userId, realComponents.has('file-basic')));

    completions.push(await streamingChatCompletion(apiKey, {
      model: compositeModel,
      stream: true,
      stream_options: { include_usage: true },
      messages: [{
        role: 'user',
        content: [
          { type: 'text', text: 'Compare the image with the spreadsheet.' },
          { type: 'image_url', image_url: { url: pngDataUri } },
          { type: 'file_id', file_id: xlsFile.id },
        ],
      }],
    }, 'vision-basic', adminToken, userId, realComponents.has('vision-basic')));

    const missingFileResponse = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: 'POST',
      headers: jsonHeaders(apiKey),
      body: JSON.stringify({
        model: compositeModel,
        messages: [{
          role: 'user',
          content: [
            { type: 'text', text: 'Read the missing file.' },
            { type: 'file_id', file_id: 'file_missing_multimodal_e2e' },
          ],
        }],
      }),
      signal: AbortSignal.timeout(60_000),
    });
    assert.equal(missingFileResponse.status, 404, 'missing composite file must return 404');
    const missingFileError = await missingFileResponse.json();
    assert.equal(missingFileError.error?.code, 'file_not_found');
    assert.equal(missingFileError.error?.type, 'invalid_request_error');

    const finalQuota = await jsonRequest('/console/gateway/quota/summary', {
      expectedStatus: 200,
      token: userToken,
    });
    const totalTokens = completions.reduce((sum, completion) => sum + completion.totalTokens, 0);
    assert.equal(
      finalQuota.activePackage.usedTokens - initialQuota.activePackage.usedTokens,
      totalTokens,
      'parent quota must be charged exactly once per composite request',
    );

    await assertParentLedger(userToken, completions);
  } finally {
    if (apiKey) {
      for (const fileId of uploadedFileIds) {
        await cleanupAttempt(cleanupFailures, 'uploaded file deletion', async () => {
          const response = await fetch(`${baseUrl}/v1/files/${encodeURIComponent(fileId)}`, {
            method: 'DELETE',
            headers: bearerHeaders(apiKey),
            signal: AbortSignal.timeout(30_000),
          });
          assert.equal(response.status, 200);
        });
      }
    }
    if (apiKeyId && userToken) {
      await cleanupAttempt(cleanupFailures, 'API key revocation', async () => {
        const response = await fetch(
          `${baseUrl}/console/gateway/api-keys/${encodeURIComponent(apiKeyId)}`,
          {
            method: 'DELETE',
            headers: bearerHeaders(userToken),
            signal: AbortSignal.timeout(30_000),
          },
        );
        assert.equal(response.status, 204);
      });
    }
    if (userId) {
      await cleanupAttempt(cleanupFailures, 'package revocation', async () => {
        revokeTemporaryPackages(userId);
      });
    }
    if (userId && adminToken) {
      await cleanupAttempt(cleanupFailures, 'temporary user deactivation', async () => {
        const response = await fetch(
          `${baseUrl}/console/platform/admin/users/${encodeURIComponent(userId)}`,
          {
            method: 'PATCH',
            headers: jsonHeaders(adminToken),
            body: JSON.stringify({ status: 'DISABLED', role: 'USER' }),
            signal: AbortSignal.timeout(30_000),
          },
        );
        assert.equal(response.status, 200);
      });
    }
    if (cleanupFailures.length) {
      throw new Error(`multimodal E2E cleanup failed in ${cleanupFailures.join(', ')}`);
    }
  }
});

async function listModels(apiKey) {
  return jsonRequest('/v1/models', { expectedStatus: 200, token: apiKey });
}

function assertCompositeModel(models) {
  assert.equal(models.object, 'list');
  const composite = models.data?.find((model) => model.id === compositeModel);
  assert.equal(Boolean(composite), true, 'composite model must be visible to the temporary key');
  assert.equal(composite.routingStrategy, 'COMPOSITE');
  assert.deepEqual([...composite.capabilities].sort(), ['FILE', 'IMAGE', 'TEXT']);
}

async function uploadFile(apiKey, filename, mimeType, content) {
  const form = new FormData();
  form.append('purpose', 'assistants');
  form.append('file', new Blob([content], { type: mimeType }), filename);
  const response = await fetch(`${baseUrl}/v1/files`, {
    method: 'POST',
    headers: bearerHeaders(apiKey),
    body: form,
    signal: AbortSignal.timeout(60_000),
  });
  assert.equal(response.status, 200, `upload ${filename} must succeed`);
  const uploaded = await response.json();
  assert.match(uploaded.id, /^file_[a-zA-Z0-9]+$/u);
  assert.equal(uploaded.filename, filename);
  assert.equal(uploaded.status, 'processed');
  return uploaded;
}

async function chatCompletion(apiKey, body, routeModel, adminToken, userId, expectRealProvider) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: jsonHeaders(apiKey),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120_000),
  });
  assert.equal(response.status, 200, 'Chat Completions request must succeed');
  const requestId = requiredRequestId(response);
  const payload = await response.json();
  assert.equal(payload.model, compositeModel);
  const outputText = chatOutputText(payload);
  assert.equal(outputText.length > 0, true, 'Chat Completions must return text');
  if (expectRealProvider) assert.equal(outputText.includes('mock response'), false);
  return completionFacts(
    requestId, payload.usage?.total_tokens, routeModel,
    await pollAdminUsage(adminToken, userId, requestId), expectRealProvider,
  );
}

async function responsesCompletion(apiKey, body, routeModel, adminToken, userId, expectRealProvider) {
  const response = await fetch(`${baseUrl}/v1/responses`, {
    method: 'POST',
    headers: jsonHeaders(apiKey),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120_000),
  });
  assert.equal(response.status, 200, 'Responses request must succeed');
  const requestId = requiredRequestId(response);
  const payload = await response.json();
  assert.equal(payload.model, compositeModel);
  assert.equal(payload.status, 'completed');
  const outputText = responsesOutputText(payload);
  assert.equal(outputText.length > 0, true, 'Responses must return text');
  if (expectRealProvider) assert.equal(outputText.includes('mock response'), false);
  return completionFacts(
    requestId, payload.usage?.total_tokens, routeModel,
    await pollAdminUsage(adminToken, userId, requestId), expectRealProvider,
  );
}

async function streamingChatCompletion(
  apiKey, body, routeModel, adminToken, userId, expectRealProvider,
) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: 'POST',
    headers: jsonHeaders(apiKey),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120_000),
  });
  assert.equal(response.status, 200, 'streaming Chat Completions request must succeed');
  assert.equal(response.headers.get('content-type')?.includes('text/event-stream'), true);
  const requestId = requiredRequestId(response);
  const streamBody = await response.text();
  assert.match(
    streamBody,
    /(?:^|\r?\n)data:[ \t]*\[DONE\](?:\r?\n|$)/u,
    'SSE must terminate with [DONE]',
  );
  assert.equal(streamBody.includes(`\"model\":\"${compositeModel}\"`), true);
  if (expectRealProvider) assert.equal(streamBody.includes('mock stream response'), false);
  const usage = await pollAdminUsage(adminToken, userId, requestId);
  return completionFacts(requestId, usage.totalTokens, routeModel, usage, expectRealProvider);
}

function completionFacts(requestId, responseTokens, routeModel, usage, expectRealProvider) {
  const totalTokens = Number(responseTokens);
  assert.equal(Number.isSafeInteger(totalTokens) && totalTokens > 0, true);
  assert.equal(usage.modelCode, compositeModel);
  assert.equal(usage.outcome, 'SUCCEEDED');
  assert.equal(usage.totalTokens, totalTokens);
  assert.equal(usage.stages?.length, 1, 'one final upstream stage must be recorded');
  const [stage] = usage.stages;
  assert.equal(stage.stageIndex, 0);
  assert.equal(stage.stageType, 'FINAL_RESPONSE');
  assert.equal(stage.logicalModelCode, routeModel);
  assert.equal(stage.totalTokens, totalTokens);
  assert.equal(stage.outcome, 'SUCCEEDED');
  assert.equal(typeof stage.upstreamModel, 'string');
  assert.equal(stage.upstreamModel.length > 0, true);
  if (expectRealProvider) assert.notEqual(stage.providerCode, 'local-mock');
  return { requestId, totalTokens };
}

async function pollAdminUsage(adminToken, userId, requestId) {
  return poll(`usage ${requestId}`, async () => {
    const page = await jsonRequest(
      `/console/gateway/admin/usage?size=10&userId=${encodeURIComponent(userId)}`
        + `&requestId=${encodeURIComponent(requestId)}`,
      { expectedStatus: 200, token: adminToken },
    );
    return page.items?.find((entry) => entry.requestId === requestId) ?? null;
  }, (entry) => entry?.stages?.length === 1);
}

async function assertParentLedger(userToken, completions) {
  const compositeLedger = await jsonRequest(
    `/console/gateway/quota/ledger?modelCode=${encodeURIComponent(compositeModel)}&size=100`,
    { expectedStatus: 200, token: userToken },
  );
  for (const completion of completions) {
    const entries = compositeLedger.items.filter(
      (entry) => entry.requestId === completion.requestId,
    );
    assert.equal(entries.length, 2, 'each parent request must have one reserve and one settle');
    assert.deepEqual(entries.map((entry) => entry.entryType).sort(), ['RESERVE', 'SETTLE']);
    assert.equal(
      entries.reduce((sum, entry) => sum + entry.totalTokenDelta, 0),
      -completion.totalTokens,
    );
  }

  for (const component of ['text-basic', 'vision-basic', 'file-basic']) {
    const componentLedger = await jsonRequest(
      `/console/gateway/quota/ledger?modelCode=${encodeURIComponent(component)}&size=100`,
      { expectedStatus: 200, token: userToken },
    );
    const requestIds = new Set(completions.map((completion) => completion.requestId));
    assert.equal(
      componentLedger.items.some((entry) => requestIds.has(entry.requestId)),
      false,
      `component quota ${component} must not be charged`,
    );
  }
}

async function jsonRequest(path, options) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method ?? 'GET',
    headers: options.body === undefined
      ? bearerHeaders(options.token)
      : jsonHeaders(options.token),
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(options.timeoutMs ?? 60_000),
  });
  assert.equal(response.status, options.expectedStatus, `${path} returned an unexpected status`);
  return response.json();
}

function bearerHeaders(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(token) {
  return { ...bearerHeaders(token), 'Content-Type': 'application/json', Accept: 'application/json' };
}

function requiredRequestId(response) {
  return requiredString(response.headers.get('x-request-id'), 'x-request-id');
}

function requiredString(value, label) {
  assert.equal(typeof value, 'string', `${label} must be a string`);
  assert.equal(value.length > 0, true, `${label} must not be empty`);
  return value;
}

function booleanEnvironment(name, fallback) {
  const value = process.env[name] ?? String(fallback);
  assert.match(value, /^(?:true|false)$/u, `${name} must be true or false`);
  return value === 'true';
}

function requiredUuid(value, label) {
  const text = requiredString(value, label);
  assert.match(text, /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu);
  return text;
}

function chatOutputText(payload) {
  const content = payload.choices?.[0]?.message?.content;
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map((part) => typeof part?.text === 'string' ? part.text : '').join('');
}

function responsesOutputText(payload) {
  if (!Array.isArray(payload.output)) return '';
  return payload.output.flatMap((item) => Array.isArray(item.content) ? item.content : [])
    .map((part) => typeof part?.text === 'string' ? part.text : '')
    .join('');
}

async function poll(label, observe, accepted, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() <= deadline) {
    const value = await observe();
    if (accepted(value)) return value;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`${label} timed out after ${timeoutMs} ms`);
}

function runLocalBootstrap() {
  try {
    execFileSync('docker', composeArgs('run', '--rm', '--no-deps', 'local-bootstrap'), {
      encoding: 'utf8', timeout: 60_000, windowsHide: true,
    });
  } catch {
    throw new Error('local bootstrap rerun failed');
  }
}

function configuredRealComponents() {
  const output = runPostgres(`
    SELECT DISTINCT component_alias.alias
    FROM gateway.model_alias component_alias
    JOIN gateway.model model ON model.id = component_alias.model_id
    JOIN gateway.channel_model mapping ON mapping.model_id = model.id
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    JOIN gateway.provider provider ON provider.id = channel.provider_id
    WHERE component_alias.alias IN ('text-basic', 'vision-basic', 'file-basic')
      AND model.enabled AND mapping.enabled AND channel.enabled AND provider.enabled
      AND channel.last_health_status = 'HEALTHY'
      AND (
        (component_alias.alias = 'text-basic' AND mapping.capabilities @> '["TEXT"]'::jsonb)
        OR (component_alias.alias = 'vision-basic' AND mapping.capabilities @> '["TEXT","IMAGE"]'::jsonb)
        OR (component_alias.alias = 'file-basic' AND mapping.capabilities @> '["TEXT"]'::jsonb)
      )
      AND provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
      AND channel.encrypted_api_key IS NOT NULL
      AND octet_length(channel.encrypted_api_key) > 0
    ORDER BY component_alias.alias
  `);
  return new Set(output.split(/\r?\n/u).filter(Boolean));
}

function revokeTemporaryPackages(userId) {
  requiredUuid(userId, 'cleanup user id');
  runPostgres(`
    UPDATE gateway.package_instance
    SET status = 'REVOKED', updated_at = now()
    WHERE user_id = '${userId}'::uuid
      AND status IN ('ACTIVE', 'QUEUED')
  `);
}

function provisionTemporaryUser({ userId, email, password, displayName }) {
  requiredUuid(userId, 'temporary user id');
  runPostgresScript(`
    BEGIN;
    INSERT INTO platform.app_user(
        id, tenant_id, email, password_hash, display_name, status)
    VALUES (
        :'user_id'::uuid, :'user_id'::uuid, lower(:'email'),
        crypt(:'password', gen_salt('bf', 12)), :'display_name', 'ACTIVE');

    INSERT INTO platform.user_role(tenant_id, user_id, role_id)
    SELECT app_user.tenant_id, app_user.id, app_role.id
    FROM platform.app_user app_user
    JOIN platform.app_role app_role ON app_role.code = 'USER'
    WHERE app_user.id = :'user_id'::uuid
    ON CONFLICT (user_id, role_id) DO NOTHING;
    COMMIT;
  `, { user_id: userId, email, password, display_name: displayName });
}

function runPostgres(sql) {
  try {
    return execFileSync('docker', composeArgs(
      'exec', '-T', 'postgres',
      'psql', '-U', 'postgres', '-d', 'api_model_platform',
      '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql,
    ), { encoding: 'utf8', timeout: 30_000, windowsHide: true }).trim();
  } catch {
    throw new Error('bounded E2E database operation failed');
  }
}

function runPostgresScript(sql, variables) {
  const variableArgs = Object.entries(variables)
    .flatMap(([name, value]) => ['-v', `${name}=${value}`]);
  try {
    return execFileSync('docker', composeArgs(
      'exec', '-T', 'postgres',
      'psql', '-U', 'postgres', '-d', 'api_model_platform',
      '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', ...variableArgs,
    ), {
      encoding: 'utf8', input: sql, timeout: 30_000, windowsHide: true,
    }).trim();
  } catch {
    throw new Error('bounded E2E database operation failed');
  }
}

function createLegacyFixture(format) {
  const fixture = format === 'doc'
    ? {
        source: 'source.txt',
        output: 'source.doc',
        filter: 'doc:MS Word 97',
        content: Buffer.from('Legacy document integration fixture.\n', 'utf8'),
      }
    : {
        source: 'source.csv',
        output: 'source.xls',
        filter: 'xls:MS Excel 97',
        content: Buffer.from('name,value\nfixture,1\n', 'utf8'),
      };
  const script = `
    set -eu
    work_dir=$(mktemp -d /tmp/multimodal-e2e-XXXXXX)
    trap 'rm -rf "$work_dir"' EXIT
    cat > "$work_dir/${fixture.source}"
    mkdir "$work_dir/out"
    /usr/bin/soffice "-env:UserInstallation=file://$work_dir/profile" --headless \\
      --convert-to '${fixture.filter}' --outdir "$work_dir/out" \\
      "$work_dir/${fixture.source}" >/dev/null 2>&1
    cat "$work_dir/out/${fixture.output}"
  `;
  try {
    return execFileSync('docker', composeArgs(
      'exec', '-T', 'document-converter', 'sh', '-ec', script,
    ), {
      input: fixture.content,
      maxBuffer: 25 * 1024 * 1024,
      timeout: 30_000,
      windowsHide: true,
    });
  } catch {
    throw new Error(`legacy ${format} fixture generation failed`);
  }
}

function composeArgs(...args) {
  return ['compose', '--env-file', composeEnvFile, '-f', composeFile, ...args];
}

async function cleanupAttempt(failures, label, cleanup) {
  try {
    await cleanup();
  } catch {
    failures.push(label);
  }
}
