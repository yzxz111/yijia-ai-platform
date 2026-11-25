-- Seed one demo account per published package.
-- Login: <package-code-kebab>@yuzhi.test / 123456
-- Idempotent: re-running only tops up what is missing.
\set ON_ERROR_STOP on

DO $$
DECLARE
    plan_row record;
    new_user_id uuid;
    new_tenant_id uuid;
    login_email varchar(320);
    user_role_id bigint;
BEGIN
    SELECT id INTO user_role_id FROM platform.app_role WHERE code = 'USER';
    IF user_role_id IS NULL THEN
        RAISE EXCEPTION 'app_role USER is missing';
    END IF;

    FOR plan_row IN
        SELECT p.code, p.name
        FROM platform.package_plan p
        JOIN platform.package_plan_version v
          ON v.package_plan_id = p.id AND v.status = 'PUBLISHED'
        WHERE p.status = 'ACTIVE'
        ORDER BY p.code
    LOOP
        login_email := lower(replace(plan_row.code, '_', '-')) || '@yuzhi.test';

        SELECT id, tenant_id INTO new_user_id, new_tenant_id
        FROM platform.app_user
        WHERE lower(email) = login_email;

        IF new_user_id IS NULL THEN
            new_user_id := gen_random_uuid();
            new_tenant_id := new_user_id;

            INSERT INTO platform.app_user(
                id, tenant_id, email, password_hash, display_name, status)
            VALUES (
                new_user_id,
                new_tenant_id,
                login_email,
                crypt('123456', gen_salt('bf', 12)),
                plan_row.name,
                'ACTIVE');

            RAISE NOTICE 'created user % for package %', login_email, plan_row.code;
        ELSE
            -- Keep the demo password stable across re-runs.
            UPDATE platform.app_user
            SET password_hash = crypt('123456', gen_salt('bf', 12)),
                display_name = plan_row.name,
                status = 'ACTIVE',
                updated_at = now()
            WHERE id = new_user_id;
            RAISE NOTICE 'reused user % for package %', login_email, plan_row.code;
        END IF;

        INSERT INTO platform.user_role(tenant_id, user_id, role_id)
        VALUES (new_tenant_id, new_user_id, user_role_id)
        ON CONFLICT (user_id, role_id) DO NOTHING;

        INSERT INTO platform.account_balance(
            id, tenant_id, user_id, currency, available_amount)
        VALUES (gen_random_uuid(), new_tenant_id, new_user_id, 'CNY', 0)
        ON CONFLICT (tenant_id, user_id, currency) DO NOTHING;

        INSERT INTO platform.recharge_option(
            id, tenant_id, amount, enabled, display_order)
        SELECT gen_random_uuid(), new_tenant_id, defaults.amount, true,
               defaults.display_order
        FROM (VALUES
            (1000::bigint, 10), (3000::bigint, 20), (5000::bigint, 30),
            (10000::bigint, 40), (20000::bigint, 50), (30000::bigint, 60),
            (50000::bigint, 70)
        ) AS defaults(amount, display_order)
        ON CONFLICT (tenant_id, amount) DO NOTHING;

        INSERT INTO gateway.safety_enforcement_cache(
            id, tenant_id, user_id, state, reason_code,
            event_version, effective_at, updated_at)
        VALUES (gen_random_uuid(), new_tenant_id, new_user_id, 'CLEARED',
                'account_initialized', 1, now(), now())
        ON CONFLICT (tenant_id, user_id) DO NOTHING;
    END LOOP;
END
$$;

SELECT u.email, u.display_name, u.status, b.available_amount
FROM platform.app_user u
JOIN platform.account_balance b ON b.user_id = u.id
WHERE lower(u.email) LIKE '%@yuzhi.test'
ORDER BY u.email;
