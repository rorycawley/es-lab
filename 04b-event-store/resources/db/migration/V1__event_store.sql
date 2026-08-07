-- Event store schema. See SPEC.md sections 3 and 4.

CREATE SEQUENCE IF NOT EXISTS global_message_position;

-- One row per stream. Exists so concurrent writers have a single row to
-- collide on; stream_position is the version. (R3.1: 0 means "no stream")
CREATE TABLE IF NOT EXISTS streams (
    stream_id       TEXT   NOT NULL PRIMARY KEY,
    stream_type     TEXT   NOT NULL,
    stream_position BIGINT NOT NULL
);

-- One row per event. Append-only.
CREATE TABLE IF NOT EXISTS messages (
    stream_id        TEXT        NOT NULL,
    stream_position  BIGINT      NOT NULL,
    message_id       TEXT        NOT NULL,
    message_type     TEXT        NOT NULL,
    message_data     JSONB       NOT NULL,
    message_metadata JSONB       NOT NULL DEFAULT '{}',

    -- R3.3: provisioned now, used when background projections arrive.
    -- nextval() is not transactional, so global_position has gaps and can be
    -- committed out of order; transaction_id is what lets a future consumer
    -- avoid skipping an event that commits late with a lower position.
    global_position  BIGINT      NOT NULL DEFAULT nextval('global_message_position'),
    transaction_id   XID8        NOT NULL DEFAULT pg_current_xact_id(),
    created          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- R3.2: backstop. If the version logic ever has a hole this turns a silent
    -- double-write into a loud error. Column order also serves the read query
    -- (WHERE stream_id = ? ORDER BY stream_position).
    PRIMARY KEY (stream_id, stream_position)
);


-- Claims a version and writes events, atomically.
--
--   p_expected      a version number, or NULL for "don't check"
--   p_require_new   TRUE means the stream must not already exist
--
-- Returns success = FALSE rather than raising, so that a losing write does not
-- abort the caller's transaction (R4.3, R4.6).
--
-- ISOLATION: this function REQUIRES read committed. The whole design rests on a
-- losing UPDATE matching zero rows, which is a read-committed behaviour:
-- Postgres waits for the blocking transaction, then re-evaluates the WHERE
-- against the updated row. At repeatable read or serializable it instead raises
-- 40001, turning every conflict into an aborted transaction. The guard below
-- makes that loud rather than silent. (R4.9)
CREATE OR REPLACE FUNCTION append_to_stream(
    p_stream_id     text,
    p_stream_type   text,
    p_expected      bigint,
    p_require_new   boolean,
    p_message_ids   text[],
    p_message_types text[],
    p_message_data  jsonb[],
    p_message_meta  jsonb[]
)
RETURNS TABLE (success boolean, next_position bigint, current_position bigint)
LANGUAGE plpgsql
AS $$
DECLARE
    v_count    int;
    v_current  bigint;
    v_expected bigint;
    v_next     bigint;
    v_rows     int;
BEGIN
    -- R4.9
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION
            'append_to_stream requires read committed isolation, got %',
            current_setting('transaction_isolation')
            USING HINT = 'At repeatable read or serializable a losing UPDATE raises 40001 instead of matching zero rows, so conflicts would abort the transaction.';
    END IF;

    v_count := COALESCE(array_length(p_message_data, 1), 0);

    -- R4.7: the caller skips the write when a decision produces nothing, so
    -- reaching here with no messages is a bug worth surfacing.
    IF v_count = 0 THEN
        RAISE EXCEPTION 'append_to_stream called with no messages';
    END IF;

    -- R4.10: unnest() of several arrays pads the short ones with NULL and
    -- yields max(length) rows, which would write more events than the version
    -- reserved. Never rely on the only caller being correct.
    IF COALESCE(array_length(p_message_ids,   1), 0) <> v_count
    OR COALESCE(array_length(p_message_types, 1), 0) <> v_count
    OR COALESCE(array_length(p_message_meta,  1), 0) <> v_count THEN
        RAISE EXCEPTION
            'append_to_stream: message arrays differ in length (ids %, types %, data %, meta %)',
            array_length(p_message_ids, 1), array_length(p_message_types, 1),
            v_count, array_length(p_message_meta, 1);
    END IF;

    SELECT COALESCE((SELECT s.stream_position FROM streams s
                      WHERE s.stream_id = p_stream_id), 0)
      INTO v_current;

    -- R4.4: three modes.
    IF NOT p_require_new AND p_expected IS NULL THEN
        -- R4.4 mode ":any" -- no concurrency check, so this must NOT be able to
        -- conflict. A SELECT-then-UPDATE would: another writer committing in
        -- between moves the version and the UPDATE matches nothing, producing a
        -- conflict the caller explicitly opted out of. Use a real upsert, which
        -- blocks on the conflicting row and then applies on top of whatever
        -- committed. It always succeeds.
        INSERT INTO streams (stream_id, stream_type, stream_position)
        VALUES (p_stream_id, p_stream_type, v_count)
        ON CONFLICT (stream_id) DO UPDATE
           SET stream_position = streams.stream_position + v_count
        RETURNING stream_position INTO v_next;

        v_expected := v_next - v_count;
        v_rows     := 1;

    ELSE
        IF p_require_new THEN
            v_expected := 0;
        ELSE
            v_expected := p_expected;
        END IF;

        v_next := v_expected + v_count;

        -- R4.2/R4.3: claim the version. Both branches report loss as zero rows.
        IF v_expected = 0 THEN
            -- Creating. Two concurrent creates: one inserts, the other does
            -- nothing. ON CONFLICT keeps it from aborting the transaction.
            INSERT INTO streams (stream_id, stream_type, stream_position)
            VALUES (p_stream_id, p_stream_type, v_next)
            ON CONFLICT (stream_id) DO NOTHING;
            GET DIAGNOSTICS v_rows = ROW_COUNT;
        ELSE
            -- Appending. The WHERE clause is the check; the UPDATE takes a row
            -- lock, so a competing writer waits, then matches nothing.
            UPDATE streams SET stream_position = v_next
             WHERE stream_id = p_stream_id
               AND stream_position = v_expected;
            GET DIAGNOSTICS v_rows = ROW_COUNT;
        END IF;
    END IF;

    IF v_rows = 0 THEN
        -- R4.5: v_current above was read before we blocked on the winner's
        -- lock, so under READ COMMITTED it is stale. Re-read: by now the
        -- winner has committed, which is what we were waiting for.
        SELECT COALESCE((SELECT s.stream_position FROM streams s
                          WHERE s.stream_id = p_stream_id), 0)
          INTO v_current;
        RETURN QUERY SELECT FALSE, NULL::bigint, v_current;
        RETURN;
    END IF;

    -- We own positions v_expected+1 .. v_next. Write them in one statement.
    --   unnest(a,b,c,d)     four parallel arrays -> one row per event
    --   WITH ORDINALITY     adds a 1,2,3... counter in guaranteed array order
    --   v_expected + ord    turns that counter into the real position
    WITH numbered AS (
        SELECT m.message_id,
               m.message_type,
               m.message_data,
               m.message_meta,
               v_expected + m.ord AS stream_position
          FROM unnest(p_message_ids, p_message_types, p_message_data, p_message_meta)
               WITH ORDINALITY
               AS m(message_id, message_type, message_data, message_meta, ord)
    )
    INSERT INTO messages
        (stream_id, stream_position, message_id, message_type,
         message_data, message_metadata)
    SELECT p_stream_id, n.stream_position, n.message_id, n.message_type,
           n.message_data, n.message_meta
      FROM numbered n;

    RETURN QUERY SELECT TRUE, v_next, v_current;
END;
$$;
