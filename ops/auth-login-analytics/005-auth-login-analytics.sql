-- Run as the local PostgreSQL administrator after a verified backup.
-- This is a separate, read-only feed: the authentication transaction never
-- synchronously contacts OpenObserve or writes to a network destination.
BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'starlit_login_analytics') THEN
    CREATE ROLE starlit_login_analytics NOLOGIN NOINHERIT;
  END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS starlit_analytics AUTHORIZATION postgres;
REVOKE ALL ON SCHEMA starlit_analytics FROM PUBLIC;
GRANT USAGE ON SCHEMA starlit_analytics TO starlit_login_analytics;
GRANT CONNECT ON DATABASE starlit TO starlit_login_analytics;

CREATE TABLE IF NOT EXISTS starlit_analytics.export_cursor (
  source text PRIMARY KEY CHECK (source IN ('success', 'failure')),
  cursor_time timestamptz,
  cursor_id uuid,
  file_inode text,
  file_offset bigint NOT NULL DEFAULT 0 CHECK (file_offset >= 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON starlit_analytics.export_cursor FROM PUBLIC;
GRANT SELECT, UPDATE ON starlit_analytics.export_cursor TO starlit_login_analytics;

INSERT INTO starlit_analytics.export_cursor(source,cursor_time,cursor_id)
VALUES ('success', now() - interval '30 days',
        '00000000-0000-0000-0000-000000000000'::uuid)
ON CONFLICT(source) DO NOTHING;
INSERT INTO starlit_analytics.export_cursor(source)
VALUES ('failure') ON CONFLICT(source) DO NOTHING;

-- The restricted exporter never receives account IDs, emails, password
-- hashes, OAuth credentials, tokens, user-agent strings or audit payloads.
-- Retrospective reads are hard-capped to the last 30 days and 100 rows.
CREATE OR REPLACE FUNCTION starlit_analytics.login_successes(
  p_after timestamptz,
  p_after_id uuid,
  p_limit integer DEFAULT 100
)
RETURNS TABLE (
  event_id uuid,
  login_at timestamptz,
  login_method text,
  ip_address text
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $func$
  SELECT
    a.id,
    a.created_at,
    CASE
      WHEN NULLIF(a.payload #>> '{traits,passkey_id}', '') IS NOT NULL THEN 'passkey'
      WHEN lower(COALESCE(a.payload #>> '{traits,provider}', ''))
           IN ('email', 'google', 'apple', 'github')
        THEN lower(a.payload #>> '{traits,provider}')
      WHEN a.payload ->> 'actor_via_sso' = 'true' THEN 'sso'
      ELSE 'unknown'
    END AS login_method,
    COALESCE(NULLIF(btrim(a.ip_address), ''),
      CASE WHEN matched.session_count = 1 THEN matched.session_ip END)
    AS ip_address
  FROM auth.audit_log_entries AS a
  LEFT JOIN LATERAL (
    -- Only a single session matching within 50ms may supply a missing IP.
    SELECT count(*) AS session_count, max(host(s.ip)) AS session_ip
    FROM auth.sessions AS s
    WHERE s.user_id::text = COALESCE(NULLIF(a.payload #>> '{traits,user_id}', ''),
                                    a.payload ->> 'actor_id')
      AND s.created_at BETWEEN a.created_at - interval '50 milliseconds'
                           AND a.created_at + interval '50 milliseconds'
  ) AS matched ON true
  WHERE a.payload ->> 'action' = 'login'
    AND a.created_at >= now() - interval '30 days'
    AND (a.created_at, a.id) >
      (COALESCE(p_after, now() - interval '30 days'),
       COALESCE(p_after_id, '00000000-0000-0000-0000-000000000000'::uuid))
  ORDER BY a.created_at ASC, a.id ASC
  LIMIT LEAST(GREATEST(COALESCE(p_limit, 1), 1), 100);
$func$;
REVOKE ALL ON FUNCTION starlit_analytics.login_successes(timestamptz,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION starlit_analytics.login_successes(timestamptz,uuid,integer)
  TO starlit_login_analytics;
COMMIT;
