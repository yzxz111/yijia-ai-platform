-- Grant each seeded demo account the package matching its login name.
-- Writes platform.grant_request + platform.outbox_event; the platform outbox
-- publisher then delivers the event to the gateway, which activates it.
-- Idempotent on business_no.
\set ON_ERROR_STOP on

DO $$
DECLARE
    plan_row record;
    target_user record;
    v_grant_id uuid;
    v_event_id uuid;
    v_business_no varchar(64);
    v_actor uuid;
    v_identity_key varchar(128);
    v_models jsonb;
    v_payload jsonb;
BEGIN
    SELECT id INTO v_actor
    FROM platform.app_user WHERE id = '70000000-0000-4000-8000-000000000001';

    FOR plan_row IN
        SELECT p.code, p.name, p.kind,
               v.id AS version_id, v.price_amount, v.currency,
               v.total_token_quota, v.duration_days, v.max_api_keys,
               v.requests_per_minute, v.tokens_per_minute, v.max_concurrency,
               v.daily_request_limit, v.max_output_tokens,
               v.max_file_size_bytes, v.once_per_user, v.usage_dimension
        FROM platform.package_plan p
        JOIN platform.package_plan_version v
          ON v.package_plan_id = p.id AND v.status = 'PUBLISHED'
        WHERE p.status = 'ACTIVE'
        ORDER BY p.code
    LOOP
        SELECT u.id, u.tenant_id, u.email
        INTO target_user
        FROM platform.app_user u
        WHERE lower(u.email) = lower(replace(plan_row.code, '_', '-')) || '@yuzhi.test';

        IF target_user.id IS NULL THEN
            RAISE NOTICE 'no demo user for %, skipping', plan_row.code;
            CONTINUE;
        END IF;

        v_business_no := 'seed:' || lower(plan_row.code);
        v_identity_key := encode(digest(lower(target_user.email), 'sha256'), 'hex');

        SELECT COALESCE(
                   jsonb_agg(jsonb_build_object(
                       'modelCode', m.model_code,
                       'modelTokenQuota', m.model_token_quota,
                       'capabilities', m.capabilities) ORDER BY m.model_code),
                   '[]'::jsonb)
        INTO v_models
        FROM platform.package_plan_version_model m
        WHERE m.package_plan_version_id = plan_row.version_id;

        IF v_models = '[]'::jsonb THEN
            RAISE NOTICE 'package % has no model permissions, skipping', plan_row.code;
            CONTINUE;
        END IF;

        v_grant_id := gen_random_uuid();
        v_event_id := gen_random_uuid();

        v_payload := jsonb_build_object(
            'grantRequestId', v_grant_id,
            'businessNo', v_business_no,
            'tenantId', target_user.tenant_id,
            'userId', target_user.id,
            'identityKey', v_identity_key,
            'packageVersionId', plan_row.version_id,
            'packageCode', plan_row.code,
            'packageName', plan_row.name,
            'packageKind', plan_row.kind,
            'priceAmount', plan_row.price_amount,
            'currency', plan_row.currency,
            'totalTokenQuota', plan_row.total_token_quota,
            'durationDays', plan_row.duration_days,
            'maxApiKeys', plan_row.max_api_keys,
            'requestsPerMinute', plan_row.requests_per_minute,
            'tokensPerMinute', plan_row.tokens_per_minute,
            'maxConcurrency', plan_row.max_concurrency,
            'dailyRequestLimit', plan_row.daily_request_limit,
            'maxOutputTokens', plan_row.max_output_tokens,
            'maxFileSizeBytes', plan_row.max_file_size_bytes,
            'oncePerUser', plan_row.once_per_user,
            'usageDimension', plan_row.usage_dimension,
            'models', v_models);

        -- Retire any grant this seed previously created so the demo re-issues cleanly.
        DELETE FROM platform.outbox_event
        WHERE business_key = v_business_no;

        UPDATE platform.grant_request
        SET status = 'REVOKED', updated_at = now()
        WHERE business_no = v_business_no AND status <> 'REVOKED';

        INSERT INTO platform.grant_request(
            id, business_no, tenant_id, user_id, package_plan_version_id,
            status, source, reason, created_by)
        VALUES (
            v_grant_id, v_business_no, target_user.tenant_id, target_user.id,
            plan_row.version_id, 'QUEUED', 'SYSTEM',
            'demo seed account for ' || plan_row.code, v_actor);

        INSERT INTO platform.outbox_event(
            id, aggregate_type, aggregate_id, event_type, business_key, payload)
        VALUES (
            v_event_id, 'GRANT', v_grant_id, 'PACKAGE_GRANTED_V1',
            v_business_no, v_payload);

        RAISE NOTICE 'queued % for %', plan_row.code, target_user.email;
    END LOOP;
END
$$;
