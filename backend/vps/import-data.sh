#!/bin/sh
# Restore a reviewed DATA-ONLY archive into a migrated, empty Starlit database.
# Stop auth/rest/gateway before running. Never point this at an occupied target.
set -eu
test "$(id -u)" -eq 0 || { echo 'Run as root.' >&2; exit 1; }
test "$#" -eq 1 || { echo 'Usage: sh import-data.sh /private/starlit-data.dump' >&2; exit 1; }
archive=$1
test -f "$archive"
# The target GoTrue already recorded its own identical migrations.
if pg_restore --list "$archive" | awk '
  !/^;/ && NF {
    if ($4=="TABLE" && $5=="DATA" && $6 ~ /^(auth|public|starlit_private)$/ && !($6=="auth" && $7=="schema_migrations")) next;
    if ($4=="SEQUENCE" && $5=="SET" && $6 ~ /^(auth|public|starlit_private)$/) next;
    bad=1
  }
  END { exit !bad }
'; then
  echo 'Use a data-only dump, excluding auth.schema_migrations (see README).' >&2
  exit 1
fi
backup_dir=${STARLIT_BACKUP_DIR:-/var/backups/starlit}
install -d -m 700 "$backup_dir"
umask 077
backup=$(mktemp "$backup_dir/pre-import-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.dump")
runuser -u postgres -- pg_dump -Fc starlit > "$backup"
echo "Pre-import backup: $backup"
# The application must remain stopped for the complete import/verification.
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d starlit <<'SQL'
do $$ begin
  if exists(select 1 from auth.users) or exists(select 1 from starlit_private.profiles)
    or exists(select 1 from starlit_private.records) or exists(select 1 from starlit_private.events)
    or exists(select 1 from starlit_private.devices) or exists(select 1 from public.addons)
    or exists(select 1 from public.plugins) then
    raise exception 'Import target contains account/sync data; refusing to overwrite';
  end if;
end $$;
SQL
# Root opens the private archive; postgres receives only the stream. Trigger
# suppression also prevents account/profile generation from duplicating rows.
runuser -u postgres -- pg_restore --data-only --disable-triggers --single-transaction --no-owner --no-privileges -d starlit < "$archive"
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d starlit <<'SQL'
do $$ begin
  if exists(select 1 from starlit_private.profiles p left join auth.users u on u.id=p.user_id where u.id is null)
    or exists(select 1 from starlit_private.records r left join starlit_private.profiles p on (p.user_id,p.profile_index)=(r.user_id,r.profile_id) where p.user_id is null)
    or exists(select 1 from public.addons a left join starlit_private.profiles p on (p.user_id,p.profile_index)=(a.user_id,a.profile_id) where p.user_id is null)
    or exists(select 1 from public.plugins a left join starlit_private.profiles p on (p.user_id,p.profile_index)=(a.user_id,a.profile_id) where p.user_id is null)
    or exists(select 1 from starlit_private.events e left join auth.users u on u.id=e.user_id where u.id is null)
    or exists(select 1 from starlit_private.devices d left join auth.users u on u.id=d.user_id where u.id is null) then
    raise exception 'Imported ownership relations are inconsistent; keep services stopped and inspect the backup';
  end if;
end $$;
select 'auth.users' as relation,count(*) from auth.users
union all select 'profiles',count(*) from starlit_private.profiles
union all select 'addons',count(*) from public.addons
union all select 'plugins',count(*) from public.plugins
union all select 'events',count(*) from starlit_private.events
union all select 'devices',count(*) from starlit_private.devices;
select kind,count(*) from starlit_private.records group by kind order by kind;
SQL
