#!/bin/sh
# Run as root on the Linux PostgreSQL host, after GoTrue has become healthy.
set -eu
cd "$(dirname "$0")/.."
test "$(id -u)" -eq 0 || { echo 'Run as root (uses PostgreSQL peer authentication).' >&2; exit 1; }
backup_dir=${STARLIT_BACKUP_DIR:-/var/backups/starlit}
install -d -m 700 "$backup_dir"
umask 077
backup=$(mktemp "$backup_dir/pre-migrate-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.dump")
runuser -u postgres -- pg_dump -Fc starlit > "$backup"
echo "Pre-migration backup: $backup"

# Each SQL migration and its checksum record commit together. An advisory lock
# serializes deployments, and changed applied migrations stop the deployment.
sql=$(mktemp)
trap 'rm -f "$sql"' EXIT HUP INT TERM
cat > "$sql" <<'SQL'
\set ON_ERROR_STOP on
select pg_advisory_lock(hashtextextended('starlit-schema-migrations',0));
grant usage on schema auth to starlit_owner, anon, authenticated;
grant execute on function auth.uid(), auth.jwt() to starlit_owner, anon, authenticated;
grant references, trigger on auth.users to starlit_owner;
set role starlit_owner;
create schema if not exists starlit_deploy;
create table if not exists starlit_deploy.migrations (
  name text primary key, sha256 text not null, applied_at timestamptz not null default now()
);
revoke all on schema starlit_deploy from public, anon, authenticated;
SQL
for migration in supabase/migrations/*.sql; do
  name=$(basename "$migration")
  case "$name" in *[!a-zA-Z0-9_.-]*) echo 'Invalid migration filename' >&2; exit 1;; esac
  checksum=$(sha256sum "$migration" | cut -d ' ' -f 1)
  cat >> "$sql" <<SQL
select exists(select 1 from starlit_deploy.migrations where name='$name' and sha256<>'$checksum') as changed \gset
\if :changed
  \echo 'Applied migration checksum changed: $name'
  \quit 3
\endif
select exists(select 1 from starlit_deploy.migrations where name='$name') as applied \gset
\if :applied
  \echo 'Already applied: $name'
\else
begin;
SQL
  cat "$migration" >> "$sql"
  printf "\ninsert into starlit_deploy.migrations(name,sha256) values('%s','%s');\ncommit;\n" "$name" "$checksum" >> "$sql"
  printf '%s\n' '\endif' >> "$sql"
done
printf '%s\n' "notify pgrst, 'reload schema';" >> "$sql"
runuser -u postgres -- psql -X -d starlit < "$sql"
