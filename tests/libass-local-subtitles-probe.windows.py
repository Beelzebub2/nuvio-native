"""Exercise the pinned Windows libmpv with synthetic local animated ASS subtitles.

This is an offscreen renderer probe, not an interactive desktop drag-and-drop test.
It uses no media downloads, fonts, screen captures, or audio output.
"""

import ctypes
import hashlib
import sys
import tempfile
import time
from pathlib import Path


def bind(library, name, restype, argtypes):
    function = getattr(library, name)
    function.restype = restype
    function.argtypes = argtypes
    return function


def check_images(files, minimum):
    if len(files) < minimum:
        raise AssertionError(f"Expected at least {minimum} rendered frames; got {len(files)}")
    for filename in files:
        if not filename.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("Bundled mpv produced an invalid PNG frame")


def render(library, video, subtitle, output, override):
    output.mkdir()
    pointer, number, text = ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p
    create = bind(library, "mpv_create", pointer, [])
    option = bind(library, "mpv_set_option_string", number, [pointer, text, text])
    initialize = bind(library, "mpv_initialize", number, [pointer])
    command = bind(library, "mpv_command", number, [pointer, ctypes.POINTER(text)])
    terminate = bind(library, "mpv_terminate_destroy", None, [pointer])

    handle = create()
    if not handle:
        raise AssertionError("mpv_create failed")

    def execute(*arguments):
        raw = (text * (len(arguments) + 1))(
            *[str(argument).encode("utf-8") for argument in arguments], None
        )
        if command(handle, raw) < 0:
            raise AssertionError(f"libmpv rejected {arguments[0]}")

    try:
        for key, value in (
            ("config", "no"),
            ("ao", "null"),
            ("vo", "image"),
            ("vo-image-outdir", str(output)),
            ("vo-image-format", "png"),
            ("pause", "yes"),
            ("sub-ass-override", override),
            ("sub-scale", "1.0"),
            ("sub-pos", "100"),
        ):
            if option(handle, key.encode(), value.encode("utf-8")) != 0:
                raise AssertionError(f"Bundled libmpv rejected option {key}")

        if initialize(handle) != 0:
            raise AssertionError("mpv_initialize failed")
        execute("loadfile", video, "replace")
        # Pausing before loading prevents the short test clip from finishing
        # before the explicit, local Unicode-named subtitle is selected.
        time.sleep(0.3)
        if subtitle is not None:
            execute("sub-add", subtitle, "select")
        execute("set", "pause", "no")
        time.sleep(1.0)
    finally:
        terminate(handle)
    return sorted(output.glob("*.png"))


def main():
    if sys.platform != "win32":
        raise SystemExit("This bundled-libmpv probe requires Windows")
    repo = Path(__file__).resolve().parent.parent
    runtime = repo / "composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll"
    if not runtime.is_file() or runtime.stat().st_size != 115320296:
        raise AssertionError("Pinned Windows libmpv must be fetched and verified first")
    library = ctypes.WinDLL(str(runtime))

    with tempfile.TemporaryDirectory(prefix="starlit-libass-probe-") as directory:
        root = Path(directory)
        video = root / "synthetic.y4m"
        # 10 identical black YUV420p frames at 320x180; no external video.
        frame = b"FRAME\n" + bytes([16]) * (320 * 180) + bytes([128]) * (320 * 180 // 2)
        video.write_bytes(b"YUV4MPEG2 W320 H180 F2:1 Ip A1:1 C420jpeg\n" + frame * 10)

        subtitle = root / "字幕-animated.ass"
        subtitle.write_text(
            "[Script Info]\n"
            "ScriptType: v4.00+\nPlayResX: 320\nPlayResY: 180\n"
            "[V4+ Styles]\n"
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
            "Style: Signs,Arial,34,&H0000FFFF,&H000000FF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,3,1,5,0,0,0,1\n"
            "Style: Dialogue,Arial,24,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,0,0,12,1\n"
            "[Events]\n"
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            "Dialogue: 0,0:00:00.00,0:00:05.00,Signs,,0,0,0,,{\\move(70,48,260,90,0,4500)\\t(0,4500,\\fscx150)}SIGN\n"
            "Dialogue: 1,0:00:00.00,0:00:05.00,Dialogue,,0,0,0,,{\\k30}Ka{\\k50}ra{\\k50}o{\\k50}ke\n",
            encoding="utf-8",
        )

        reference = render(library, video, None, root / "reference", "no")
        animated = render(library, video, subtitle, root / "animated", "no")
        check_images(reference, 8)
        check_images(animated, 8)

        def hashes(files):
            return [hashlib.sha256(file.read_bytes()).hexdigest() for file in files]

        baseline = hashes(reference)
        styled = hashes(animated)
        if len(set(baseline)) != 1:
            raise AssertionError("Identical subtitle-free video frames rendered differently")
        if len(set(styled)) < 3:
            raise AssertionError("Animated ASS positioning/karaoke produced no frame changes")
        if sum(a != b for a, b in zip(baseline, styled)) < 5:
            raise AssertionError("Local ASS subtitles were not rendered over the video")
        print(
            "PASS: bundled libmpv rendered local Unicode-path ASS subtitles with "
            f"multiple styles, movement and effects ({len(animated)} frames)"
        )


if __name__ == "__main__":
    main()
