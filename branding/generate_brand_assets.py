"""Generates every Starlit logo, app icon and wordmark asset from one vector mark.

    python branding/generate_brand_assets.py [--preview-only]

The mark is a rounded play triangle holding a four-point star, with a small
twinkle above it. Each app-icon colourway (Settings > Appearance > App icon)
only swaps the three gradient stops. Shapes are written as SVG and rasterised
with headless Microsoft Edge, so this runs on any Windows machine with Edge,
Python 3 and Pillow. Outputs keep the existing file names, so no code needs to
change when the artwork does.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
DESKTOP_ICONS = ROOT / "composeApp/src/desktopMain/resources/icons"
COMPOSE_DRAWABLE = ROOT / "composeApp/src/commonMain/composeResources/drawable"
ANDROID_RES = ROOT / "composeApp/src/androidMain/res"
IOS_ASSETS = ROOT / "iosApp/iosApp/Assets.xcassets"
WORDMARK_FONT = ROOT / "composeApp/src/commonMain/composeResources/font/jetbrains_sans_bold.ttf"
PREVIEW = ROOT / "build/brand/starlit-brand-board.png"
EDGE_CANDIDATES = [
    Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe",
    Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Microsoft/Edge/Application/msedge.exe",
]

BRAND_NAME = "Starlit"

# key -> (gradient stops top-left to bottom-right, iOS icon set name)
COLORWAYS = {
    "original": (("#45D6EB", "#3965D4", "#B250EF"), "AppIcon"),
    "arctic_blue": (("#4DE3FF", "#3185F5", "#4D55E8"), "AppIconArcticBlue"),
    "emerald": (("#7BF08D", "#22D37C", "#0BBF9A"), "AppIconEmerald"),
    "rose_gold": (("#B75AFF", "#EC70A9", "#FFB37A"), "AppIconRoseGold"),
    "copper": (("#C95945", "#DE7B45", "#ECAE62"), "AppIconCopper"),
    "graphite": (("#D7DCE3", "#8D96A3", "#4E5763"), "AppIconGraphite"),
}
# Wordmarks that follow an app theme rather than an icon colourway.
THEME_WORDMARKS = {
    "gold": ("#FFD45C", "#E8A91C", "#9A6200"),
}

ANDROID_DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]


# ---------------------------------------------------------------- geometry

def rounded_polygon(points: list[tuple[float, float]], radius: float) -> str:
    """SVG path for a polygon whose corners are rounded with quadratic curves."""
    parts = []
    for i, corner in enumerate(points):
        before, after = points[i - 1], points[(i + 1) % len(points)]

        def toward(target: tuple[float, float]) -> tuple[float, float]:
            dx, dy = target[0] - corner[0], target[1] - corner[1]
            length = math.hypot(dx, dy)
            return corner[0] + dx * radius / length, corner[1] + dy * radius / length

        start, end = toward(before), toward(after)
        parts.append(f"{'M' if i == 0 else 'L'}{start[0]:.1f} {start[1]:.1f}")
        parts.append(f"Q{corner[0]:.1f} {corner[1]:.1f} {end[0]:.1f} {end[1]:.1f}")
    return " ".join(parts) + " Z"


def sparkle(cx: float, cy: float, rx: float, ry: float, waist: float = 0.12) -> str:
    """A four-point star whose sides curve in towards the centre."""
    kx, ky = rx * waist, ry * waist
    return (
        f"M{cx} {cy - ry} Q{cx + kx:.1f} {cy - ky:.1f} {cx + rx} {cy} "
        f"Q{cx + kx:.1f} {cy + ky:.1f} {cx} {cy + ry} "
        f"Q{cx - kx:.1f} {cy + ky:.1f} {cx - rx} {cy} "
        f"Q{cx - kx:.1f} {cy - ky:.1f} {cx} {cy - ry} Z"
    )


PLAY = rounded_polygon([(250, 190), (250, 834), (836, 512)], 150)
STAR = sparkle(470, 512, 150, 178)
TWINKLE = sparkle(788, 318, 58, 66)
# The mark's bounding box is 250..846 x 190..834; this centres it in 1024.
MARK_OFFSET = (-36, 0)


def mark_svg(stops: tuple[str, str, str], uid: str = "m") -> str:
    """The mark as an SVG group on a 1024 canvas (ids are prefixed so several can share a page)."""
    first, middle, last = stops
    return f"""
  <defs>
    <path id="{uid}-play" d="{PLAY}"/>
    <clipPath id="{uid}-clip"><use href="#{uid}-play"/></clipPath>
    <linearGradient id="{uid}-body" x1="250" y1="190" x2="836" y2="834" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{first}"/><stop offset="0.52" stop-color="{middle}"/><stop offset="1" stop-color="{last}"/>
    </linearGradient>
    <linearGradient id="{uid}-twinkle" x1="730" y1="252" x2="846" y2="384" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{first}"/><stop offset="1" stop-color="{last}"/>
    </linearGradient>
    <linearGradient id="{uid}-sheen" x1="250" y1="190" x2="520" y2="640" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.30"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="{uid}-glow" cx="470" cy="512" r="250" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.5"/>
      <stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0.12"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <g transform="translate({MARK_OFFSET[0]} {MARK_OFFSET[1]})">
    <use href="#{uid}-play" fill="url(#{uid}-body)"/>
    <g clip-path="url(#{uid}-clip)">
      <rect x="0" y="0" width="1024" height="1024" fill="url(#{uid}-sheen)"/>
      <circle cx="470" cy="512" r="250" fill="url(#{uid}-glow)"/>
    </g>
    <path d="{STAR}" fill="#FFFFFF"/>
    <path d="{TWINKLE}" fill="url(#{uid}-twinkle)"/>
  </g>"""


def mark_document(stops: tuple[str, str, str]) -> str:
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">{mark_svg(stops)}\n</svg>\n'


def wordmark_document(stops: tuple[str, str, str]) -> str:
    """HTML page with the mark and the name; rendered wide, then trimmed to its pixels."""
    font = WORDMARK_FONT.as_uri()
    return f"""<!doctype html><html><head><style>
@font-face {{ font-family: "Brand"; src: url("{font}"); font-weight: 700; }}
html, body {{ margin: 0; background: transparent; }}
.row {{ display: flex; align-items: center; gap: 16px; height: 400px; padding: 0 40px; }}
svg {{ width: 360px; height: 360px; margin: 0 -40px 0 -48px; }}
span {{ font: 700 236px/1 "Brand"; letter-spacing: -0.035em; color: #FFFFFF; transform: translateY(-6px); }}
</style></head><body><div class="row">
<svg viewBox="0 0 1024 1024">{mark_svg(stops, "w")}</svg><span>{BRAND_NAME}</span></div></body></html>
"""


# ---------------------------------------------------------------- rendering

def find_edge() -> Path:
    for candidate in EDGE_CANDIDATES:
        if candidate.exists():
            return candidate
    raise SystemExit("Microsoft Edge was not found; it is used to rasterise the SVG artwork.")


def render(edge: Path, page: Path, out: Path, width: int, height: int, profile: Path) -> Image.Image:
    out.unlink(missing_ok=True)
    subprocess.run(
        [
            str(edge), "--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=1", "--default-background-color=00000000",
            f"--window-size={width},{height}", f"--user-data-dir={profile}",
            f"--screenshot={out}", page.as_uri(),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        timeout=120,
    )
    # Edge can return a moment before the screenshot lands on disk.
    for _ in range(50):
        if out.exists() and out.stat().st_size > 0:
            break
        time.sleep(0.2)
    return Image.open(out).convert("RGBA")


def svg_page(work: Path, name: str, svg: str, size: int) -> Path:
    (work / f"{name}.svg").write_text(svg, encoding="utf-8")
    page = work / f"{name}.html"
    page.write_text(
        "<!doctype html><html><head><style>html,body{margin:0;background:transparent}"
        f"img{{display:block;width:{size}px;height:{size}px}}</style></head>"
        f'<body><img src="{name}.svg"></body></html>',
        encoding="utf-8",
    )
    return page


# ---------------------------------------------------------------- derived images

def fit(mark: Image.Image, size: int, fill: float) -> Image.Image:
    """Mark scaled so its visible pixels span `fill` of a transparent square."""
    bounds = mark.getchannel("A").getbbox()
    cropped = mark.crop(bounds)
    scale = size * fill / max(cropped.width, cropped.height)
    scaled = cropped.resize((round(cropped.width * scale), round(cropped.height * scale)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
    return canvas


def on_black(image: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", image.size, (0, 0, 0, 255))
    canvas.alpha_composite(image)
    return canvas.convert("RGB")


def round_mask(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    mask = Image.new("L", rgba.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, rgba.width - 1, rgba.height - 1), fill=255)
    rgba.putalpha(mask)
    return rgba


def silhouette(image: Image.Image) -> Image.Image:
    white = Image.new("RGBA", image.size, (255, 255, 255, 0))
    white.putalpha(image.getchannel("A"))
    return white


def trim(image: Image.Image, pad_ratio: float = 0.04) -> Image.Image:
    bounds = image.getchannel("A").getbbox()
    cropped = image.crop(bounds)
    pad = round(cropped.height * pad_ratio)
    canvas = Image.new("RGBA", (cropped.width + pad * 2, cropped.height + pad * 2), (0, 0, 0, 0))
    canvas.alpha_composite(cropped, (pad, pad))
    return canvas


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", lossless=True, method=6)


def write_platform_icons(key: str, ios_name: str, mark: Image.Image) -> Image.Image:
    # Desktop: transparent, filling most of the square like other Windows/macOS icons.
    desktop = fit(mark, 1024, 0.92)
    desktop_names = [f"app-icon-{key}-transparent"] + (["nuvio-app-icon-transparent"] if key == "original" else [])
    for name in desktop_names:
        save_png(desktop.resize((256, 256), Image.Resampling.LANCZOS), DESKTOP_ICONS / f"{name}.png")
        desktop.save(DESKTOP_ICONS / f"{name}.ico", sizes=[(s, s) for s in ICO_SIZES])
        desktop.save(DESKTOP_ICONS / f"{name}.icns")

    # In-app icon picker and wordmark-free contexts.
    picker = fit(mark, 1024, 0.74)
    save_png(on_black(picker).resize((256, 256), Image.Resampling.LANCZOS), COMPOSE_DRAWABLE / f"app_icon_{key}.png")
    save_png(fit(mark, 256, 0.92), COMPOSE_DRAWABLE / f"app_icon_{key}_transparent.png")

    # iOS: opaque 1024 icon; the system applies the corner mask.
    icon_set = IOS_ASSETS / f"{ios_name}.appiconset"
    save_png(on_black(picker), icon_set / "app-icon-1024.png")
    contents = icon_set / "Contents.json"
    if not contents.exists():
        contents.write_text(json.dumps({
            "images": [{"filename": "app-icon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
            "info": {"author": "xcode", "version": 1},
        }, indent=2) + "\n", encoding="utf-8")

    # Android: legacy square icon, adaptive foreground (inside the 66 dp safe zone), splash.
    launcher = "ic_launcher" if key == "original" else f"ic_launcher_{key}"
    adaptive = fit(mark, 1024, 0.56)
    for density, scale in ANDROID_DENSITIES.items():
        folder = ANDROID_RES / f"mipmap-{density}"
        legacy = round(48 * scale)
        foreground = round(108 * scale)
        save_webp(on_black(picker).resize((legacy, legacy), Image.Resampling.LANCZOS), folder / f"{launcher}.webp")
        save_webp(adaptive.resize((foreground, foreground), Image.Resampling.LANCZOS), folder / f"{launcher}_foreground.webp")
        if key == "original":
            save_webp(round_mask(on_black(picker).resize((legacy, legacy), Image.Resampling.LANCZOS)), folder / "ic_launcher_round.webp")
            save_webp(silhouette(adaptive).resize((foreground, foreground), Image.Resampling.LANCZOS), folder / "ic_launcher_monochrome.webp")
    splash = "ic_splash_logo" if key == "original" else f"ic_splash_logo_{key}"
    save_webp(fit(mark, 1080, 0.72), ANDROID_RES / "drawable-nodpi" / f"{splash}.webp")
    return on_black(picker)


def write_preview(icons: dict[str, Image.Image], wordmark: Image.Image, mark: Image.Image) -> None:
    board = Image.new("RGB", (1500, 760), (13, 13, 13))
    x = 40
    for key, icon in icons.items():
        board.paste(icon.resize((200, 200), Image.Resampling.LANCZOS), (x, 40))
        x += 240
    shown = wordmark.resize((round(wordmark.width * 150 / wordmark.height), 150), Image.Resampling.LANCZOS)
    board.paste(shown, (40, 300), shown)
    # Desktop icon sizes on a dark and a light background (taskbar, Explorer, Start).
    for background, x in (((13, 13, 13), 40), ((243, 243, 243), 760)):
        for size in (256, 64, 48, 32, 24, 16):
            tile = Image.new("RGBA", (size, size), background + (255,))
            tile.alpha_composite(fit(mark, size, 0.92))
            board.paste(tile.convert("RGB"), (x, 480))
            x += size + 24
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    board.save(PREVIEW)


# ---------------------------------------------------------------- main

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview-only", action="store_true", help="render the board without touching app assets")
    args = parser.parse_args()

    edge = find_edge()
    with tempfile.TemporaryDirectory(prefix="starlit-brand-") as temp:
        work = Path(temp)
        (work / "starlit-mark.svg").write_text(mark_document(COLORWAYS["original"][0]), encoding="utf-8")
        shutil.copy(work / "starlit-mark.svg", Path(__file__).with_name("starlit-mark.svg"))

        marks: dict[str, Image.Image] = {}
        for key, (stops, _) in {**COLORWAYS, **{k: (v, None) for k, v in THEME_WORDMARKS.items()}}.items():
            page = svg_page(work, f"mark-{key}", mark_document(stops), 1024)
            marks[key] = render(edge, page, work / f"mark-{key}.png", 1024, 1024, work / f"profile-{key}")

        wordmarks: dict[str, Image.Image] = {}
        for key, stops in {**{k: v[0] for k, v in COLORWAYS.items()}, **THEME_WORDMARKS}.items():
            page = work / f"wordmark-{key}.html"
            page.write_text(wordmark_document(stops), encoding="utf-8")
            wordmarks[key] = trim(render(edge, page, work / f"wordmark-{key}.png", 1800, 400, work / f"profile-w-{key}"))

        icons = {}
        if not args.preview_only:
            for key, (_, ios_name) in COLORWAYS.items():
                icons[key] = write_platform_icons(key, ios_name, marks[key])
            for key, wordmark in wordmarks.items():
                save_png(wordmark, COMPOSE_DRAWABLE / f"app_logo_wordmark_{key}.png")
            save_png(wordmarks["original"], COMPOSE_DRAWABLE / "app_logo_wordmark.png")
        else:
            icons = {key: on_black(fit(marks[key], 256, 0.74)) for key in COLORWAYS}
        write_preview(icons, wordmarks["original"], marks["original"])
    print(f"Preview: {PREVIEW}")


if __name__ == "__main__":
    main()
