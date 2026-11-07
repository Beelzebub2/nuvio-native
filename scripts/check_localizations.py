"""Validate new Compose Multiplatform localizations against English source.

Run: py -3 scripts/check_localizations.py
No network or third-party Python dependencies are required.
"""

from __future__ import annotations

import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "composeApp/src/commonMain/composeResources"
SOURCE_FILES = ("strings.xml", "strings_redesign.xml", "strings_mdblist.xml", "strings_changelog.xml")
ADDED_LOCALES = ("zh", "zh-rTW", "ko", "th", "uk", "fa", "ms", "fil", "sv", "fi", "ta", "te", "sw")
PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?[-+#0,.() 0-9]*[a-zA-Z%]")
ESCAPED_NEWLINE_RE = re.compile(r"\\[nrt]")


def read_strings(directory: Path, files: tuple[str, ...]) -> dict[str, str]:
    result = {}
    for file in files:
        root = ET.parse(directory / file).getroot()
        for item in root:
            if item.tag != "string":
                continue
            name = item.attrib["name"]
            if name in result:
                raise AssertionError(f"Duplicate resource {directory}/{name}")
            result[name] = item.text or ""
    return result


def main():
    sources = read_strings(RESOURCES / "values", SOURCE_FILES)
    assert not (RESOURCES / "values-hi").exists(), "Hindi is explicitly excluded"
    failures = []
    for locale in ADDED_LOCALES:
        base = RESOURCES / f"values-{locale}"
        translated = read_strings(base, ("strings.xml",))
        extra = set(translated) - set(sources)
        missing = set(sources) - set(translated)
        if extra or missing:
            failures.append(f"{locale}: {len(extra)} unknown / {len(missing)} missing keys")
        for key, text in translated.items():
            if key not in sources:
                continue
            english = sources[key]
            if "[[K" in text or "[[P" in text:
                failures.append(f"{locale}/{key}: leaked translation marker")
            if Counter(PLACEHOLDER_RE.findall(english)) != Counter(PLACEHOLDER_RE.findall(text)):
                failures.append(f"{locale}/{key}: format argument mismatch")
            if Counter(ESCAPED_NEWLINE_RE.findall(english)) != Counter(ESCAPED_NEWLINE_RE.findall(text)):
                failures.append(f"{locale}/{key}: newline/tab escape mismatch")
        print(f"{locale}: {len(translated)}/{len(sources)} string keys")
    if failures:
        print("\n".join(failures[:80]))
        raise SystemExit(f"{len(failures)} localization errors found")
    print(f"Validated {len(ADDED_LOCALES)} new locales; Hindi not added")


if __name__ == "__main__":
    main()
