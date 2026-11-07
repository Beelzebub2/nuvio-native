"""Generate draft translations for new Starlit locales.

Run: py -3 scripts/translate_popular_languages.py

The translation provider is best-effort. Output is a MACHINE-TRANSLATED
draft and requires native-speaker review before language accuracy is claimed.
Existing locales and their manual translations are never replaced.
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "composeApp/src/commonMain/composeResources"
SOURCE_FILES = ("strings.xml", "strings_redesign.xml", "strings_mdblist.xml", "strings_changelog.xml")
# Hindi is excluded by explicit request.
LANGUAGES = {
    "zh": "zh-CN",
    "zh-rTW": "zh-TW",
    "ko": "ko",
    "th": "th",
    "uk": "uk",
    "fa": "fa",
    "ms": "ms",
    "fil": "fil",
    "sv": "sv",
    "fi": "fi",
    "ta": "ta",
    "te": "te",
    "sw": "sw",
}
# Media-library terminology that a context-free translator otherwise mistakes
# for a physical book library. These overrides are maintained in the generator
# so a clean draft run reproduces the UI-specific corrections.
CONTEXT_OVERRIDES = {
    "zh": {"library_title": "媒体库", "library_empty_title": "你的媒体库尚无内容", "media_series": "剧集"},
    "zh-rTW": {"library_title": "媒體庫", "library_empty_title": "你的媒體庫尚無內容", "media_series": "影集"},
    "ko": {"library_title": "보관함"},
    "th": {"library_title": "คลังสื่อ", "library_empty_title": "คลังสื่อของคุณยังว่างอยู่"},
    "fil": {"library_title": "Library"},
    "ms": {"library_title": "Pustaka"},
}
BRANDS = (
    "Starlit", "Nuvio", "Trakt", "MyAnimeList", "MDBList", "TMDB", "TVDB",
    "AniList", "Kitsu", "Stremio", "Torrentio", "OpenSubtitles", "Sentry",
    "Bitwarden", "Google", "GitHub", "Bluetooth", "Wi-Fi", "Chromecast",
    "WebDAV", "Debrid", "OpenObserve", "P2P", "API", "JSON", "QR",
)
BRAND_RE = "|".join(re.escape(name) for name in sorted(BRANDS, key=len, reverse=True))
TOKEN_RE = re.compile(
    r"https?://[^\s\"<>]+"
    r"|%((?:\d+\$)?[-+#0,.() 0-9]*[a-zA-Z%])"
    r"|\\(?:n|r|t|u[0-9a-fA-F]{4}|\\)"
    r"|\{[a-zA-Z_][a-zA-Z0-9_]*\}"
    r"|(?<!\w)(?:" + BRAND_RE + r")(?!\w)"
)
PLACEHOLDER_RE = re.compile(r"\[\[P(\d{4})\]\]")


def source_entries() -> dict[str, str]:
    strings: dict[str, str] = {}
    for file in SOURCE_FILES:
        root = ET.parse(RESOURCES / "values" / file).getroot()
        for item in root:
            if item.tag != "string":
                continue
            name = item.get("name")
            if name in strings:
                raise ValueError(f"Duplicate source key {name}")
            strings[name] = item.text or ""
    return strings


def encode_text(value: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def replace(match):
        token = f"[[P{len(tokens):04d}]]"
        tokens.append(match.group(0))
        return token

    return TOKEN_RE.sub(replace, value), tokens


def decode_text(value: str, tokens: list[str]) -> str | None:
    if sorted(PLACEHOLDER_RE.findall(value)) != [f"{i:04d}" for i in range(len(tokens))]:
        return None
    for index, token in enumerate(tokens):
        value = value.replace(f"[[P{index:04d}]]", token)
    if "[[K" in value or "[[P" in value:
        return None
    return value


def write_locale(folder: str, entries: dict[str, str]):
    directory = RESOURCES / f"values-{folder}"
    if directory.exists():
        print(f"Preserved existing locale {folder}", flush=True)
        return
    directory.mkdir()
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, value in entries.items():
        if "\x00" in value:
            raise ValueError(f"Null byte in {folder}/{name}")
        content = escape(value, {"\"": "&quot;"})
        lines.append(f'    <string name="{name}">{content}</string>')
    lines.append("</resources>")
    path = directory / "strings.xml"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    ET.parse(path)
    print(f"Written {folder}: {len(entries)} source keys", flush=True)


def main():
    original = source_entries()
    prepared = {}
    for name, value in original.items():
        text, tokens = encode_text(value)
        prepared[name] = {"text": text, "tokens": tokens}
    root = ROOT / "build/localization-drafts-20261107"
    root.mkdir(parents=True, exist_ok=True)
    (root / "source.json").write_text(
        json.dumps({"languages": LANGUAGES, "prepared": prepared}, ensure_ascii=False),
        encoding="utf-8",
    )
    subprocess.run(["node", str(ROOT / "scripts/translate_popular_languages.mjs")], cwd=ROOT, check=True)
    for folder in LANGUAGES:
        if (RESOURCES / f"values-{folder}").exists():
            continue
        target = root / f"{folder}.json"
        if not target.exists():
            raise RuntimeError(f"Missing translator output for {folder}")
        translated = json.loads(target.read_text(encoding="utf-8"))
        valid = {}
        total = len(original)
        for name, value in original.items():
            result = translated.get(name)
            restored = decode_text(result, prepared[name]["tokens"]) if isinstance(result, str) and result else None
            if restored is not None:
                valid[name] = restored
        if len(valid) / total < 0.97:
            raise RuntimeError(
                f"{folder} only has {len(valid)}/{total} safe machine translations; refusing misleading locale."
            )
        # Fill the handful of failed strings with the existing English fallback.
        resolved = {key: valid.get(key, value) for key, value in original.items()}
        resolved.update(CONTEXT_OVERRIDES.get(folder, {}))
        write_locale(folder, resolved)
        print(f"  {folder}: {len(valid)}/{total} translated; {total-len(valid)} fallback", flush=True)


if __name__ == "__main__":
    main()
