"""Windows-only offline check of the bundled stereo-to-5.1 libmpv filter.

This deliberately uses the null audio backend and synthetic test tones.
It cannot establish physical-speaker layout or subjective audio quality.
"""

import ctypes
import math
import struct
import tempfile
import time
import wave
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    dll = root / "composeApp/src/desktopMain/native/windows/runtime/libmpv-2.dll"
    if not dll.is_file():
        raise SystemExit("Bundled Windows libmpv DLL not present")

    library = ctypes.WinDLL(str(dll))
    pointer = ctypes.c_void_p
    integer = ctypes.c_int
    text = ctypes.c_char_p

    def bind(name, result, arguments):
        method = getattr(library, name)
        method.restype = result
        method.argtypes = arguments
        return method

    create = bind("mpv_create", pointer, [])
    set_option = bind("mpv_set_option_string", integer, [pointer, text, text])
    initialize = bind("mpv_initialize", integer, [pointer])
    command = bind("mpv_command", integer, [pointer, ctypes.POINTER(text)])
    get_property = bind("mpv_get_property", integer, [pointer, text, integer, pointer])
    free_value = bind("mpv_free", None, [pointer])
    terminate = bind("mpv_terminate_destroy", None, [pointer])

    with tempfile.TemporaryDirectory(prefix="starlit-surround-probe-") as directory:
        test_file = Path(directory) / "synthetic.wav"
        rate = 48000
        with wave.open(str(test_file), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(2)
            output.setframerate(rate)
            frames = bytearray()
            for n in range(rate * 3):
                left = int(3000 * math.sin(2 * math.pi * 440 * n / rate))
                right = int(3000 * math.sin(2 * math.pi * 660 * n / rate))
                frames.extend(struct.pack("<hh", left, right))
            output.writeframes(frames)

        mpv = create()
        if not mpv:
            raise AssertionError("mpv_create failed")

        def get(name: str) -> str:
            returned = pointer()
            code = get_property(mpv, name.encode(), 1, ctypes.byref(returned))
            if code < 0 or not returned.value:
                return ""
            try:
                return ctypes.string_at(returned.value).decode("utf-8")
            finally:
                free_value(returned)

        def execute(*args: str) -> None:
            raw = (text * (len(args) + 1))(
                *[part.encode("utf-8") for part in args], None
            )
            code = command(mpv, raw)
            if code < 0:
                raise AssertionError(f"libmpv rejected {args[0]}: {code}")

        try:
            for key, value in (
                ("config", "no"),
                ("vo", "null"),
                ("ao", "null"),
                ("video", "no"),
            ):
                assert set_option(mpv, key.encode(), value.encode()) == 0, key
            assert initialize(mpv) == 0
            execute("loadfile", str(test_file), "replace")
            time.sleep(0.25)
            assert get("audio-params/channels") == "stereo", "Stereo input missing"
            assert get("audio-out-params/channels") == "stereo", "Unexpected original layout"
            for target in ("5.1(side)", "5.1"):
                execute(
                    "af",
                    "add",
                    "@starlit-surround:lavfi=[surround=chl_in=stereo:"
                    f"chl_out={target}:lfe=0:level_out=0.65]",
                )
                time.sleep(0.25)
                assert "@starlit-surround" in get("af"), "Audio filter not installed"
                assert get("audio-out-params/channels") == target, "Unexpected output layout"
                execute("af", "remove", "@starlit-surround")
                time.sleep(0.1)
                assert "@starlit-surround" not in get("af"), "Filter removal failed"
                assert get("audio-out-params/channels") == "stereo", "Original layout not restored"
        finally:
            terminate(mpv)

    print("PASS: bundled libmpv applies/removes both stereo-to-5.1 speaker layouts")


if __name__ == "__main__":
    main()
