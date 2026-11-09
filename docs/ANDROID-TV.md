# Starlit TV — Android TV / Google TV

Starlit TV is a separate Android package (`com.starlit.tv`) built from the shared
Compose Multiplatform application. It installs beside the phone application and
reuses the Starlit account, profile, library, add-on catalog, stream selection,
playback, and synchronization implementations.

## Build and install

Use JDK 21 and the Android SDK with API level 37.0. From the repository root:

```sh
./gradlew :tvApp:assembleDebug -Pnuvio.android.distribution=full --no-configuration-cache
adb connect TV_IP_ADDRESS:5555
adb install tvApp/build/outputs/apk/debug/tvApp-debug.apk
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Enable Android TV developer
options and network debugging, or use USB debugging where available. The build
uses the **full** Android distribution to retain the native streaming engine.
The debug APK is separately identified as `com.starlit.tv.debug` and is not
intended as a signed production release.

## TV UI and compatibility

- Home, Search, Library, and primary Settings use television-scale layouts with
  D-pad-focusable navigation and poster cards.
- Selecting a title opens the existing metadata, episode, source-selection, and
  player flows, retaining provider selection, subtitles and watch progress.
- Signed-in profiles and local settings are scoped to the TV installation; an
  account can synchronize eligible data across devices.
- Requires Android 7.0/API 24 or newer, an Android TV/Google TV-compatible
  device, and a remote or other keyboard input device.
- TV-specific update assets are not yet published. The phone APK updater is
  intentionally disabled in the TV package to avoid installing incompatible
  releases. Upgrade TV installations using a signed TV APK when available.

The phone-first authentication, profile management, detailed settings, title
details, source picker and player overlays are currently shared rather than
fully redesigned for television. These flows require hardware D-pad and
playback verification before calling the TV experience production-ready.
Fire OS compatibility is not guaranteed until tested on an actual Fire TV.

This APK **does not** run on Samsung Tizen or LG webOS TVs. Those platforms
require their own client applications; they cannot install Android APKs.

## Release checks

The Android CI workflow builds the standalone TV debug APK and verifies
Leanback launcher metadata. Before shipping signed TV releases, test the
following on a hardware TV or TV emulator:

1. Launch from the television app grid with no touch input.
2. Navigate the sidebar and each poster row with all D-pad directions and Back.
3. Sign in (or select a saved profile), search with the television keyboard,
   open a series, select an episode, and choose a stream.
4. Play, pause, seek, toggle subtitles/audio, resume, and return to navigation.
5. Verify account library sync, remote playback safety, and stability with
   direct and supported P2P sources.
