"""Check recent Starlit UI translations across every shipped Compose and TV locale.

Historical Compose translation omissions are reported but grandfathered, not silently
claimed fixed. All keys added/reworded since the localization baselines are mandatory.
Run after updating the base message catalogs and before PR/merge/release.
"""
from __future__ import annotations

import argparse
import io
import re
import subprocess
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FMT = re.compile(r"%(?:\d+\$)?[-+#0,.(]*\d*(?:\.\d+)?[diuoxXfFeEgGaAcCsSbBhH%]")

CATALOGS = {
    "compose": {
        "path": "composeApp/src/commonMain/composeResources",
        "source_files": ("strings.xml", "strings_changelog.xml", "strings_mdblist.xml", "strings_redesign.xml"),
        "baseline": "a684b128c",
        "full": False,
    },
    "tv": {
        "path": "tizen/res",
        "source_files": ("strings.xml",),
        "baseline": "c0c6c45fb",
        "full": True,
    },
}


def parse_xml(raw: bytes, source: str) -> dict[str, str]:
    data: dict[str, str] = {}
    for element in ET.parse(io.BytesIO(raw)).getroot():
        if element.tag != "string" or not element.get("name"):
            continue
        name = element.attrib["name"]
        if name in data:
            raise AssertionError(f"Duplicate string {name} in {source}")
        data[name] = "".join(element.itertext())
    return data


def catalog(location: str, files: tuple[str, ...], ref: str | None = None) -> dict[str, str]:
    values: dict[str, str] = {}
    for file in files:
        relative = f"{location}/{file}"
        if ref:
            proc = subprocess.run(
                ["git", "show", f"{ref}:{relative}"], cwd=ROOT, capture_output=True,
            )
            if proc.returncode:
                # An absent locale file in a baseline is allowed, but a missing
                # source file means the checkout is too shallow for validation.
                if "/values/" in location:
                    raise AssertionError(f"Missing Git baseline: {ref}:{relative}")
                continue
            raw = proc.stdout
        else:
            path = ROOT / relative
            if not path.exists():
                continue
            raw = path.read_bytes()
        for key, text in parse_xml(raw, relative).items():
            if key in values:
                raise AssertionError(f"Duplicate string {key} in {location}")
            values[key] = text
    return values


def validate(base_ref: str | None = None) -> int:
    errors: list[str] = []
    for product, config in CATALOGS.items():
        base = config["path"]
        path = f"{base}/values"
        files = config["source_files"]
        source = catalog(path, files)
        old_source = catalog(path, files, config["baseline"])
        new = set(source) - set(old_source)
        changed = {key for key in set(source) & set(old_source) if source[key] != old_source[key]}
        diff_keys: set[str] = set()
        if base_ref:
            previous_source = catalog(path, files, base_ref)
            diff_keys = (set(source) - set(previous_source)) | {
                key for key in set(source) & set(previous_source)
                if source[key] != previous_source[key]
            }
        required = set(source) if config["full"] else new | changed | diff_keys
        locales = sorted(folder for folder in (ROOT / base).glob("values-*") if folder.is_dir())
        existing_debt = 0
        for folder in locales:
            locale = folder.name.removeprefix("values-")
            locale_path = f"{base}/{folder.name}"
            current = catalog(locale_path, ("strings.xml",))
            previous = catalog(locale_path, ("strings.xml",), config["baseline"]) if changed and not config["full"] else {}
            base_localized = catalog(locale_path, ("strings.xml",), base_ref) if base_ref else {}
            missing = set(source) - set(current)
            existing_debt += len(missing - required)
            for key in sorted(missing & required):
                errors.append(f"{product}/{locale}/{key}: missing translation")
            for key in sorted(required & set(current)):
                translation = current[key]
                if "[[P" in translation or "[[K" in translation:
                    errors.append(f"{product}/{locale}/{key}: leaked draft marker")
                if Counter(FMT.findall(source[key])) != Counter(FMT.findall(translation)):
                    errors.append(f"{product}/{locale}/{key}: formatting parameters differ")
                source_breaks = source[key].count(r"\n") + source[key].count("\n")
                translated_breaks = translation.count(r"\n") + translation.count("\n")
                if translated_breaks < source_breaks or (
                    source[key].count(r"\t") != translation.count(r"\t")
                ):
                    errors.append(f"{product}/{locale}/{key}: missing required line break/tab")
                if (not config["full"] and key in changed and
                    key in previous and translation == previous[key]):
                    errors.append(f"{product}/{locale}/{key}: stale copy for revised English")
                if key in diff_keys and key in base_localized and translation == base_localized[key]:
                    errors.append(f"{product}/{locale}/{key}: unchanged for modified English in this PR")
        print(
            f"{product}: {len(locales)} locale catalogs, {len(source)} source keys; "
            f"{len(new)} newer keys, {len(changed)} revised strings; "
            f"{existing_debt} older missing entries exempted"
        )
    if errors:
        for err in errors[:100]:
            print("ERROR:", err)
        print(f"FAILED: {len(errors)} translation parity errors")
        return 1
    print("PASS: all recent UI keys covered in every supported locale")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", default=None, help="Git commit before the PR/push for incremental parity")
    args = parser.parse_args()
    raise SystemExit(validate(args.base_ref or None))
