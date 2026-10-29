# Starlit for Android

The Android app uses the shared mobile UI and Android playback/download implementations. Desktop-only features and the mpv desktop bridge are separate. Android requires Android 7.0 (API 24) or later. The current target is API 36 and compilation uses API 37.0.

## Build and install

Use JDK 21 and Android SDK command-line tools (or Android Studio). Point `JAVA_HOME` at JDK 21 and `ANDROID_HOME` at your SDK. With `sdkmanager` on PATH, install:

```powershell
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
sdkmanager --licenses
```

On Windows, build each distribution in a separate Gradle invocation:

```powershell
.\gradlew.bat :androidApp:assembleFullDebug --no-configuration-cache
.\gradlew.bat :androidApp:assemblePlaystoreDebug --no-configuration-cache
```

On macOS/Linux, use `./gradlew` instead. The APKs are:

- `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`
- `androidApp/build/outputs/apk/playstore/debug/androidApp-playstore-debug.apk`

For sideloading, use the **full** APK. Enable USB debugging on a phone, confirm it appears in `adb devices`, then install and launch:

```powershell
adb install -r androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk
adb shell am start -n com.starlit.app.debug/com.nuvio.app.launcher.AppIconDefault
```

Debug builds appear as **Starlit Debug** with application ID `com.starlit.app.debug`; release builds use `com.starlit.app`. They install separately from upstream Nuvio. Full and Play Store variants share the same application ID and replace one another. Data from Nuvio's old application IDs is not automatically migrated. Internal Kotlin packages and `nuvio://`/`stremio://` links retain their existing names for compatibility.

Local and debug builds use the mobile version in `iosApp/Configuration/Version.xcconfig`. Debug APKs are signed with the local development keystore. Release builds need `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, and `NUVIO_RELEASE_KEY_PASSWORD`, either as environment variables or in ignored `local.properties`. Keep that keystore for future updates: Android only installs an update signed with the same key.

## Releases

Every `vX.Y.Z` release tag publishes the Windows installer and signed Full APKs together (`Starlit-Android-<abi>-X.Y.Z.apk`; most phones need `arm64-v8a`). The release workflow builds the APKs with the release version (`-Pnuvio.app.versionName` / `-Pnuvio.app.versionCode` from `composeApp/Configuration/DesktopVersion.properties`), so the in-app updater compares the installed APK with the release tag. The build job has no secrets and produces unsigned APKs (a release build without a configured key is always unsigned). A separate job that runs only GitHub's own actions signs them with the key in the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`, so third-party build actions never see it. Without those secrets the release stops before publishing anything.

The Full updater checks this fork's public GitHub releases and picks the APK for the phone's ABI. Play Store builds disable the in-app updater.

## Distribution differences

| Capability | Full | Play Store |
| --- | --- | --- |
| Add-ons, P2P, downloads | Enabled | Enabled |
| JavaScript plugins | Enabled | Disabled |
| Custom account server | Enabled | Disabled |
| In-app trailers | Enabled | External playback |
| Playback/download foreground services | Enabled | Disabled |
| APK updater | Fork releases | Disabled |

The checked-in AARs in `composeApp/libs/` provide Android media codecs and the Nuvio engine. Desktop Git LFS runtimes are unnecessary for Android builds.

## Tests

Run the Android host suite separately for each distribution:

```powershell
.\gradlew.bat :composeApp:testAndroidHostTest '-Pnuvio.android.distribution=full' --no-configuration-cache
.\gradlew.bat :composeApp:testAndroidHostTest '-Pnuvio.android.distribution=playstore' --no-configuration-cache
```

Use `:androidApp:lintFullDebug` or `:androidApp:lintPlaystoreDebug` for lint. Instrumented tests require a connected phone or emulator; some existing tests explicitly require configured provider accounts/network access. A successful APK build or host suite alone does not validate playback on every device, account sync, or real provider credentials.

On 2026-10-28 the Full debug APK was verified on an Android 16 (API 36, x86_64) emulator: clearing app data and launching creates a usable guest profile and reaches Home; custom-server discovery and email/password signup work; a second account session's library addition appears after restart, and removing it in Android is reflected in that second session. An owned local test add-on also loaded its catalog/details and played a generated H.264/AAC video through ExoPlayer without an app crash.

The broad Windows host suite is not green: download persistence/subtitle, hero pager, episode rating, and subtitle restore cases fail, with additional playback failures sensitive to test ordering. The fresh-guest/server-switch regressions and updated tab/catalog tests pass. Both complete CI suites remain enabled; see [the mobile/backend pull request](https://github.com/Beelzebub2/nuvio-native/pull/14) for the recorded results and current CI status.

## Servers and account sync

Without `NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY`, startup creates a local guest. Browsing and locally configured add-ons do not need an upstream account. Guest libraries, profiles and watch progress remain on that installation; they are not a cloud account and do not sync to another device.

To build against a compatible account backend, set these environment variables or entries in ignored `local.properties` before building:

```properties
NUVIO_SUPABASE_URL=https://your-backend.example
NUVIO_SUPABASE_ANON_KEY=your-public-anon-or-publishable-key
NUVIO_SUPABASE_FALLBACK_URL=
```

Only a public client key belongs in an app. Never embed a Supabase service-role key. Desktop and mobile must point to the same compatible backend and use the same signed-in account and profile for shared data. Play Store builds require the backend configuration at build time because custom-server selection is disabled.

In a Full build, open **Profile → Account → Sign Out** and confirm. On the login screen, choose **Server options → Connect to custom server**, enter your discovery address, choose **Check server**, and review/confirm trust. Close the server sheet and choose **Sign Up** to create your own account (or sign into an existing account on that server). For the local emulator setup below, enter `http://127.0.0.1:8787` after forwarding both ports.

Account sync uses Supabase Auth and PostgREST. A guest ID is only a local identifier, not a server user. The app skips backend availability probes when its backend configuration is blank.

| Data | Desktop and Android behavior |
| --- | --- |
| Profiles, add-ons, full-build plugins, library, watched history, progress, collections, home catalogs | Shared through the same account backend |
| UI/settings blobs | Separate desktop and mobile settings; preferences need not match |
| Debrid, TMDB, MDBList, IntroDB credentials and AnimeSkip client ID | Sensitive values synced by a dedicated provider-credential RPC |
| Downloaded media, device paths, desktop schedules and updater state | Device-local |

Only connect to an account backend you trust: it stores both viewing data and supported provider credentials. An empty Supabase project is insufficient; it needs the required schema, RPCs, functions and access policies. Existing Nuvio accounts are not automatically available to this fork.

The simplest free option is the [Cloudflare sync Worker](../backend/cloudflare/README.md): it runs on the Workers Free plan, and each new account needs the owner's approval by email. Its address (`https://starlit-sync.<subdomain>.workers.dev`) is the custom server to enter on every device. If a sign-up is waiting for approval, the app says so, and signing in explains the account is not approved yet.

The independent [Starlit sync backend](../backend/README.md) supplies the same client contracts for your own Supabase stack/project. Follow its setup, test and deployment instructions, create a new account there, then use the same server/account/profile on desktop and mobile. For an Android emulator, `adb reverse tcp:8787 tcp:8787` and `adb reverse tcp:54321 tcp:54321` let the app use the desktop discovery address `http://127.0.0.1:8787`. The backend guide covers physical devices and deployment. TV login, profile PINs, membership services and account deletion are outside the initial backend; discovery disables TV login.

The `gateway/` Cloudflare Worker only caches schedule-provider/artwork responses. It is unrelated to authentication or account sync. Its `/health` endpoint returned HTTP 200 during the 2026-10-28 audit. Upstream `https://api.nuvio.tv/auth/v1/settings` returned HTTP 401 without credentials, which is an authentication requirement rather than evidence of an outage. Neither check establishes access to upstream account data.

## iOS

iOS sources and Xcode project files are present, but Windows cannot compile or run the Apple targets. A macOS/Xcode build, signing setup and device/simulator validation are still required; Android verification does not establish iOS support.
