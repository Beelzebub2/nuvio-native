#!/bin/sh
# Execute the real migration builder with fake PostgreSQL commands. No database
# is contacted. Catches dash printf escapes corrupting psql control commands.
set -eu
backend=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/bin"
cat > "$work/bin/id" <<'SH'
#!/bin/sh
echo 0
SH
cat > "$work/bin/runuser" <<'SH'
#!/bin/sh
case "$4" in
  pg_dump) echo 'test backup';;
  psql) cat > "$STARLIT_TEST_SQL";;
  *) exit 1;;
esac
SH
chmod +x "$work/bin/id" "$work/bin/runuser"
export STARLIT_TEST_SQL="$work/migrations.sql"
PATH="$work/bin:$PATH" STARLIT_BACKUP_DIR="$work/backups" sh "$backend/vps/migrate.sh"
test -s "$STARLIT_TEST_SQL"
if LC_ALL=C grep -q "$(printf '\033')" "$STARLIT_TEST_SQL"; then
  echo 'Migration builder emitted an escape character' >&2
  exit 1
fi
awk '
  $0 ~ /^\\if / { depth++; count++ }
  $0 == "\\endif" { if (--depth < 0) exit 1 }
  END { if (depth != 0 || count < 2) exit 1 }
' "$STARLIT_TEST_SQL"
grep -qx "notify pgrst, 'reload schema';" "$STARLIT_TEST_SQL"
echo 'Migration SQL shell regression check passed'
