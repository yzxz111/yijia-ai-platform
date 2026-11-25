import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const composeFile = join(projectRoot, 'deploy', 'compose.yaml');
const envFile = join(projectRoot, 'deploy', 'env', '.env.example');

test('operating fixture balances and repeated execution is immutable', { timeout: 60_000 }, () => {
  seedFixture();
  const first = summary();
  seedFixture();
  const repeated = summary();

  assert.equal(first.rows, 10, 'all operating fixture lines must exist');
  assert.deepEqual(repeated, first, 'repeated fixture execution must not change the ledger');
  assert.equal(first.difference, 0, 'operating ledger difference must be zero cents');
  assert.equal(first.billingModes, 'HYBRID,PACKAGE_ONLY,WALLET_ONLY');
  assert.equal(first.callShapes, 'COMPOSITE,DIRECT');
  assert.equal(first.estimatedFeeLines, 2);
  assert.equal(first.actualFeeLines, 1);
});

function seedFixture() {
  psql(`
    INSERT INTO platform.operating_fact(
      id, source_service, source_event_id, line_no, event_type, occurred_at, recognized_at,
      billing_mode, sign, reversal, confirmed_revenue_amount, cash_in_amount, refund_amount,
      provider_input_cost_amount, provider_output_cost_amount, payment_provider_fee_amount,
      refund_provider_fee_amount, currency, estimated, missing_source, metadata)
    VALUES
      ('98000000-0000-4000-8000-000000000001','operating-e2e','package',0,
       'PACKAGE_PURCHASE_REVENUE',now(),now(),'PACKAGE_ONLY',1,false,10000,0,0,0,0,0,0,'CNY',false,false,'{}'),
      ('98000000-0000-4000-8000-000000000002','operating-e2e','hybrid-wallet',0,
       'WALLET_USAGE_REVENUE',now(),now(),'HYBRID',1,false,2000,0,0,0,0,0,0,'CNY',false,false,'{}'),
      ('98000000-0000-4000-8000-000000000003','operating-e2e','wallet-only',0,
       'WALLET_USAGE_REVENUE',now(),now(),'WALLET_ONLY',1,false,3000,0,0,0,0,0,0,'CNY',false,false,'{}'),
      ('98000000-0000-4000-8000-000000000004','operating-e2e','recharge',0,
       'WALLET_RECHARGE_CASH_IN',now(),now(),'WALLET_ONLY',1,false,0,5000,0,0,0,0,0,'CNY',false,false,'{}'),
      ('98000000-0000-4000-8000-000000000005','operating-e2e','refund',0,
       'PACKAGE_REFUND',now(),now(),'PACKAGE_ONLY',-1,true,0,0,1000,0,0,0,0,'CNY',false,false,'{}'),
      ('98000000-0000-4000-8000-000000000006','operating-e2e','direct-usage',0,
       'PROVIDER_USAGE_COST',now(),now(),'WALLET_ONLY',1,false,0,0,0,300,200,0,0,'CNY',false,false,
       '{"callShape":"DIRECT"}'),
      ('98000000-0000-4000-8000-000000000007','operating-e2e','composite-usage',0,
       'PROVIDER_USAGE_COST',now(),now(),'HYBRID',1,false,0,0,0,200,300,0,0,'CNY',false,false,
       '{"callShape":"COMPOSITE"}'),
      ('98000000-0000-4000-8000-000000000008','operating-e2e','estimated-fee',0,
       'PAYMENT_PROVIDER_FEE',now(),now(),'PACKAGE_ONLY',1,false,0,0,0,0,0,100,0,'CNY',true,false,
       '{"feeState":"ESTIMATED"}'),
      ('98000000-0000-4000-8000-000000000009','operating-e2e','estimated-fee-reversal',0,
       'PAYMENT_PROVIDER_FEE',now(),now(),'PACKAGE_ONLY',-1,true,0,0,0,0,0,100,0,'CNY',true,false,
       '{"feeState":"ESTIMATED_REVERSAL"}'),
      ('98000000-0000-4000-8000-000000000010','operating-e2e','actual-fees',0,
       'PAYMENT_PROVIDER_FEE',now(),now(),'PACKAGE_ONLY',1,false,0,0,0,0,0,80,20,'CNY',false,false,
       '{"feeState":"ACTUAL","includesRefundFee":true}')
    ON CONFLICT (source_service, source_event_id, line_no) DO NOTHING;
  `);
}

function summary() {
  return JSON.parse(psql(`
    WITH fixture AS (
      SELECT * FROM platform.operating_fact WHERE source_service='operating-e2e'
    ), totals AS (
      SELECT count(*) rows,
             sum(sign * confirmed_revenue_amount)::bigint revenue,
             sum(sign * refund_amount)::bigint refund,
             sum(sign * (provider_input_cost_amount + provider_output_cost_amount))::bigint provider_cost,
             sum(sign * (payment_provider_fee_amount + refund_provider_fee_amount))::bigint fees,
             count(*) FILTER (WHERE estimated AND event_type='PAYMENT_PROVIDER_FEE') estimated_fee_lines,
             count(*) FILTER (WHERE NOT estimated AND event_type='PAYMENT_PROVIDER_FEE') actual_fee_lines
      FROM fixture
    )
    SELECT json_build_object(
      'rows', rows,
      'difference', abs(revenue-15000) + abs(refund+1000) + abs(provider_cost-1000) + abs(fees-100),
      'billingModes', (SELECT string_agg(DISTINCT billing_mode, ',' ORDER BY billing_mode) FROM fixture),
      'callShapes', (SELECT string_agg(DISTINCT metadata->>'callShape', ',' ORDER BY metadata->>'callShape')
                     FROM fixture WHERE metadata ? 'callShape'),
      'estimatedFeeLines', estimated_fee_lines,
      'actualFeeLines', actual_fee_lines)
    FROM totals;
  `));
}

function psql(sql) {
  try {
    return execFileSync('docker', [
      'compose', '--env-file', envFile, '-f', composeFile,
      'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'api_model_platform',
      '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql,
    ], { encoding: 'utf8', timeout: 30_000, windowsHide: true }).trim();
  } catch {
    throw new Error('operating reporting E2E requires the current Compose postgres service');
  }
}
