DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM platform.content_safety_policy_version
        WHERE status = 'PUBLISHED'
    ) THEN
        UPDATE platform.content_safety_policy_version
        SET status = 'PUBLISHED',
            config = '{}'::jsonb,
            config_sha256 = '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
            published_at = now(),
            updated_at = now()
        WHERE version = 1
          AND status = 'DRAFT';

        IF NOT FOUND THEN
            INSERT INTO platform.content_safety_policy_version (
                version,
                status,
                config,
                config_sha256,
                published_at,
                created_at,
                updated_at
            )
            VALUES (
                1,
                'PUBLISHED',
                '{}'::jsonb,
                '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
                now(),
                now(),
                now()
            )
            ON CONFLICT (version) DO NOTHING;
        END IF;
    END IF;
END $$;
