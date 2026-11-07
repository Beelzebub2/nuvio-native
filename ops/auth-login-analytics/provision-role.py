#!/usr/bin/env python3
"""One-time root-only creation of the restricted analytics connection URL.

Never print password material, the original mail database URL or the new URL.
The existing runtime env must be a root-owned, non-symlink 0600 regular file.
"""

import os
from pathlib import Path
import secrets
import stat
import subprocess
from urllib.parse import quote, unquote, urlsplit, urlunsplit

RUNTIME_ENV = Path("/etc/starlit/runtime.env")
ROLE = "starlit_login_analytics"


def main():
    if os.geteuid() != 0:
        raise SystemExit("Only root can provision the audit database role")
    info = RUNTIME_ENV.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit("Runtime env owner/type/permissions are unsafe")
    lines = RUNTIME_ENV.read_text().splitlines()
    if any(s.startswith("AUTH_LOGIN_DATABASE_URL=") for s in lines):
        print("Dedicated auth audit database URL already provisioned; no secrets were changed")
        return
    matching = [s.partition("=")[2] for s in lines if s.startswith("MAIL_DATABASE_URL=")]
    if len(matching) != 1:
        raise SystemExit("Existing mail database URL is not uniquely configured")
    source = matching[0].strip().strip("'").strip('"')
    parsed = urlsplit(source)
    if parsed.scheme not in ("postgres", "postgresql") or not parsed.hostname:
        raise SystemExit("Mail connection URL is invalid")
    port = parsed.port or 5432
    if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise SystemExit("Refusing to replicate a remote database URL")
    host = parsed.hostname
    if ":" in host:
        host = "[" + host + "]"
    secret = secrets.token_urlsafe(40)
    netloc = quote(ROLE, safe="") + ":" + quote(secret, safe="") + "@" + host + ":" + str(port)
    new_url = urlunsplit((parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment))
    database = unquote(parsed.path.lstrip("/"))
    if not database or "/" in database or database != "starlit":
        raise SystemExit("Expected Starlit database name")

    # The password is delivered only on stdin to the local superuser, never
    # embedded in a command line, log or checked-in file.
    result = subprocess.run(
        ["runuser", "-u", "postgres", "--", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", database],
        input="ALTER ROLE " + ROLE + " LOGIN PASSWORD '" + secret + "';\n",
        text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise SystemExit("PostgreSQL refused to provision the restricted login")
    probe_env = dict(os.environ, PGPASSWORD=secret)
    result = subprocess.run(
        ["psql", "-X", "-w", "-h", parsed.hostname, "-p", str(port),
         "-U", ROLE, "-d", database, "-A", "-t", "-c",
         "SELECT count(*) FROM starlit_analytics.login_successes(now()-interval '30 days',NULL::uuid,1)"],
        env=probe_env, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise SystemExit("Restricted role connection check failed; runtime env was not modified")
    fd = os.open(RUNTIME_ENV, os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW)
    try:
        os.write(fd, ("\nAUTH_LOGIN_DATABASE_URL=" + new_url + "\n").encode())
        os.fsync(fd)
    finally:
        os.close(fd)
    print("Restricted login analytics role and private runtime configuration are ready")


if __name__ == "__main__":
    main()
