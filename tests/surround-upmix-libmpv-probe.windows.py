"""Windows-only offline check of the bundled stereo-to-5.1 libmpv filter.

Uses synthetic test tones only: a null audio backend for live filter
transitions, then a PCM file backend to verify that actual six-channel
audio samples are produced. No sound is played through Windows speakers.
This does not establish physical-speaker routing or subjective audio quality.
"""

import ctypes
import math
import struct
import tempfile
import time
import wave
from pathlib import Path


def check_pcm_surround_output(path: Path) -> None:
    """Verify WAVEFORMATEXTENSIBLE 5.1 float PCM with centre/surround signal.

    The source contains different sine waves on L/R, so a correct surround
    matrix should produce distinguishable centre and surround samples while
    leaving the explicitly disabled LFE silent.
    """
    data = path.read_bytes()
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE", "PCM WAV missing"
    offset = 12
    output_format = None
    pcm_samples = None
    while offset + 8 <= len(data):
        chunk, length = struct.unpack_from("<4sI", data, offset)
        body = offset + 8
        assert body + length <= len(data), "Truncated PCM chunk"
        if chunk == b"fmt ":
            output_format = data[body:body + length]
        elif chunk == b"data":
            # One second is enough for per-channel level checks.
            pcm_samples = data[body:body + min(length, 48000 * 6 * 4)]
        offset = body + length + (length & 1)

    assert output_format is not None and len(output_format) >= 40, "No extended WAV format"
    format_code, channels, rate = struct.unpack_from("<HHI", output_format)
    bits_per_sample = struct.unpack_from("<H", output_format, 14)[0]
    speaker_mask = struct.unpack_from("<I", output_format, 20)[0]
    subtype = struct.unpack_from("<I", output_format, 24)[0]
    assert (format_code, channels, rate, bits_per_sample, speaker_mask, subtype) == (
        0xFFFE, 6, 48000, 32, 0x060F, 3
    ), "Wrong 5.1 PCM output format"
    assert pcm_samples and len(pcm_samples) >= 48000 * 6 * 4, "PCM samples missing"
    values = struct.unpack("<" + "f" * (len(pcm_samples) // 4), pcm_samples)
    count = len(values) // 6
    rms = [
        math.sqrt(sum(values[frame * 6 + channel] ** 2 for frame in range(count)) / count)
        for channel in range(6)
    ]
    assert rms[0] > 0.005 and rms[1] > 0.005, "Front stereo channels are silent"
    assert rms[2] > 0.00001, "Centre signal was not generated"
    assert rms[4] > 0.00001 and rms[5] > 0.00001, "Surround signals were not generated"
    assert rms[3] < 0.000001, "LFE should be silent (system bass management)"


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

        def execute(handle, *args: str) -> None:
            raw = (text * (len(args) + 1))(
                *[part.encode("utf-8") for part in args], None
            )
            code = command(handle, raw)
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
            execute(mpv, "loadfile", str(test_file), "replace")
            time.sleep(0.25)
            assert get("audio-params/channels") == "stereo", "Stereo input missing"
            assert get("audio-out-params/channels") == "stereo", "Unexpected original layout"
            for target in ("5.1(side)", "5.1"):
                execute(
                    mpv,
                    "af",
                    "add",
                    "@starlit-surround:lavfi=[surround=chl_in=stereo:"
                    f"chl_out={target}:lfe=0:level_out=0.65]",
                )
                time.sleep(0.25)
                assert "@starlit-surround" in get("af"), "Audio filter not installed"
                assert get("audio-out-params/channels") == target, "Unexpected output layout"
                execute(mpv, "af", "remove", "@starlit-surround")
                time.sleep(0.1)
                assert "@starlit-surround" not in get("af"), "Filter removal failed"
                assert get("audio-out-params/channels") == "stereo", "Original layout not restored"
        finally:
            terminate(mpv)

        pcm_file = Path(directory) / "upmixed-5.1.wav"
        pcm_player = create()
        if not pcm_player:
            raise AssertionError("PCM mpv_create failed")
        try:
            for key, value in (
                ("config", "no"),
                ("vo", "null"),
                ("video", "no"),
                ("ao", "pcm"),
                ("ao-pcm-file", str(pcm_file)),
                ("pause", "yes"),
            ):
                assert set_option(pcm_player, key.encode(), value.encode()) == 0, key
            assert initialize(pcm_player) == 0
            execute(
                pcm_player, "af", "add",
                "@starlit-surround:lavfi=[surround=chl_in=stereo:"
                "chl_out=5.1(side):lfe=0:level_out=0.65]",
            )
            execute(pcm_player, "loadfile", str(test_file), "replace")
            execute(pcm_player, "set", "pause", "no")
            time.sleep(3.8)
        finally:
            terminate(pcm_player)
        check_pcm_surround_output(pcm_file)

    print("PASS: bundled libmpv processes real six-channel PCM and restores stereo")


if __name__ == "__main__":
    main()
