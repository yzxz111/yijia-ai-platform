CREATE OR REPLACE FUNCTION gateway.rebind_request_limit_fallback_channel(
    p_lease_id uuid,
    p_channel_id uuid,
    p_concurrency_limit integer)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    current_channel uuid;
    current_expires_at timestamptz;
    current_released_at timestamptz;
    scope_lock bigint;
BEGIN
    IF p_lease_id IS NULL OR p_channel_id IS NULL OR p_concurrency_limit <= 0 THEN
        RAISE EXCEPTION 'invalid_limit_channel_rebind';
    END IF;

    SELECT channel_id, expires_at, released_at
    INTO current_channel, current_expires_at, current_released_at
    FROM gateway.request_limit_fallback_lease
    WHERE lease_id = p_lease_id
    FOR UPDATE;

    IF NOT FOUND OR current_released_at IS NOT NULL
            OR current_expires_at <= clock_timestamp() THEN
        RETURN -1;
    END IF;
    IF current_channel = p_channel_id THEN
        RETURN 1;
    END IF;

    FOR scope_lock IN
        SELECT DISTINCT lock_id
        FROM unnest(ARRAY[
            hashtextextended('channel:' || current_channel::text, 0),
            hashtextextended('channel:' || p_channel_id::text, 0)
        ]) AS scope_locks(lock_id)
        ORDER BY lock_id
    LOOP
        PERFORM pg_advisory_xact_lock(scope_lock);
    END LOOP;

    IF current_released_at IS NOT NULL OR current_expires_at <= clock_timestamp() THEN
        RETURN -1;
    END IF;

    IF (SELECT count(*) FROM gateway.request_limit_fallback_lease
        WHERE released_at IS NULL AND expires_at > clock_timestamp()
          AND channel_id = p_channel_id AND lease_id <> p_lease_id) >= p_concurrency_limit THEN
        RETURN 0;
    END IF;

    UPDATE gateway.request_limit_fallback_lease
    SET channel_id = p_channel_id
    WHERE lease_id = p_lease_id
      AND channel_id = current_channel
      AND released_at IS NULL
      AND expires_at > clock_timestamp();
    IF NOT FOUND THEN
        RETURN -1;
    END IF;
    RETURN 1;
END;
$$;
