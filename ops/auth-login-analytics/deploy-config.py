#!/usr/bin/env python3
"""Insert the audited auth feed into an *existing* VPS gateway configuration.

This helper intentionally refuses unexpected source layouts and partial
installations. It never creates or prints secrets, runs SQL, or restarts a
service. Review its diff and validate nginx/Compose before activation.
"""

from pathlib import Path
import argparse
import difflib
import sys

HERE = Path(__file__).resolve().parent


def replace_once(content, old, new):
    if content.count(old) != 1:
        raise ValueError("Expected unique configuration anchor missing or duplicated")
    return content.replace(old, new, 1)


def update_nginx(text):
    definitions = (HERE / "nginx-auth-audit-snippet.conf").read_text().split(
        "# In the existing /auth/v1/token location")[0]
    definitions = definitions[definitions.index("map $arg_grant_type"):].rstrip()
    already = ["map $arg_grant_type $login_audit_grant",
               "log_format auth_login_fail",
               "auth-failures.jsonl auth_login_fail"]
    if any(marker in text for marker in already):
        if all(marker in text for marker in already):
            return text
        raise ValueError("Partially installed auth gateway logging")
    text = replace_once(
        text,
        "  access_log /dev/stdout safe;\n",
        definitions + "\n  access_log /dev/stdout safe;\n",
    )
    text = replace_once(
        text,
        "    location = /auth/v1/token {\n",
        "    location = /auth/v1/token {\n"
        "      access_log /dev/stdout safe;\n"
        "      access_log /var/log/nginx/auth-failures.jsonl auth_login_fail if=$log_failed_login;\n",
    )
    return text


def update_compose(text):
    service = (HERE / "compose-service.snippet.yml").read_text().split(
        "  # Also append")[0]
    if "  auth-login-analytics:\n" in text or "auth-failures.jsonl:/var/log/nginx" in text:
        if "  auth-login-analytics:\n" in text and "auth-failures.jsonl:/var/log/nginx" in text:
            return text
        raise ValueError("Partially installed auth analytics service")
    text = replace_once(
        text,
        "  gateway:\n",
        service.rstrip() + "\n\n  gateway:\n",
    )
    return replace_once(
        text,
        "      - ./nginx.conf:/etc/nginx/nginx.conf:ro\n",
        "      - ./nginx.conf:/etc/nginx/nginx.conf:ro\n"
        "      - /var/log/starlit/auth-failures.jsonl:/var/log/nginx/auth-failures.jsonl\n",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--nginx", type=Path, required=True)
    parser.add_argument("--compose", type=Path, required=True)
    parser.add_argument("--write", action="store_true", help="Write after a manual backup")
    args = parser.parse_args()
    changes = []
    for path, update in [(args.nginx, update_nginx), (args.compose, update_compose)]:
        old = path.read_text()
        if "\r" in old:
            raise ValueError("Only existing LF-encoded Linux configuration is supported")
        new = update(old)
        changes.append((path, old, new))
        if old != new:
            diff = difflib.unified_diff(
                old.splitlines(keepends=True), new.splitlines(keepends=True),
                fromfile=str(path) + " (before)", tofile=str(path) + " (proposed)")
            sys.stdout.writelines(diff)
    if args.write:
        for path, old, new in changes:
            if old != new:
                path.write_text(new)
        print("Updated configuration files; run nginx -t and docker compose config before restart")
    else:
        print("Dry run only; no files changed")


if __name__ == "__main__":
    main()
