#!/usr/bin/env bash
# Build exactly one verified x86_64 Starlit Linux release package.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <deb|rpm|appimage|flatpak> <version>" >&2
  exit 2
fi

format="$1"
version="$2"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid release version: $version" >&2
  exit 2
fi

case "$format" in
  deb|rpm|appimage|flatpak) ;;
  *) echo "Unsupported Linux package format: $format" >&2; exit 2 ;;
esac

expected_version="$(sed -n 's/^VERSION_NAME=//p' composeApp/Configuration/DesktopVersion.properties | tr -d '\r')"
if [[ "$version" != "$expected_version" ]]; then
  echo "Version mismatch: $version is not $expected_version" >&2
  exit 1
fi

mkdir -p release
name="Starlit-Linux-x86_64-$version"
gradle_args=(
  '-Pcompose.desktop.packaging.checkJdkVendor=false'
  '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8'
  '-Pkotlin.daemon.jvmargs=-Xmx3072m'
  --max-workers=2 --no-configuration-cache --no-daemon --console=plain
)

copy_single_package() {
  local directory="$1" extension="$2" destination="$3"
  local candidates=()
  mapfile -t candidates < <(find "$directory" -maxdepth 1 -type f -name "*.$extension" | LC_ALL=C sort)
  if [[ ${#candidates[@]} -ne 1 ]]; then
    echo "Expected one $extension package in $directory; found ${#candidates[@]}." >&2
    exit 1
  fi
  cp -- "${candidates[0]}" "$destination"
  test -s "$destination"
}

case "$format" in
  deb)
    ./gradlew :composeApp:packageReleaseDeb "${gradle_args[@]}"
    package="release/$name.deb"
    copy_single_package composeApp/build/compose/binaries/main-release/deb deb "$package"
    bash scripts/linux/verify-linux-deb.sh "$package"
    [[ "$(dpkg-deb -f "$package" Package)" == "starlit" ]]
    ;;
  rpm)
    ./gradlew :composeApp:packageReleaseRpm "${gradle_args[@]}"
    package="release/$name.rpm"
    copy_single_package composeApp/build/compose/binaries/main-release/rpm rpm "$package"
    bash scripts/linux/verify-linux-rpm.sh "$package"
    [[ "$(rpm -qp --qf '%{NAME}' "$package")" == "starlit" ]]
    ;;
  appimage)
    tool="$RUNNER_TEMP/appimagetool-x86_64.AppImage"
    version_tool="1.9.1"
    expected_sha="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
    curl --fail --silent --show-error --location \
      "https://github.com/AppImage/appimagetool/releases/download/$version_tool/appimagetool-x86_64.AppImage" \
      --output "$tool"
    echo "$expected_sha  $tool" | sha256sum --check --status
    chmod +x "$tool"
    export APPIMAGETOOL="$tool"
    export APPIMAGE_WEBSITE_URL="https://starlit.rrmtools.uk"
    owner="${GITHUB_REPOSITORY%%/*}"
    repository="${GITHUB_REPOSITORY#*/}"
    export APPIMAGE_UPDATE_INFORMATION="gh-releases-zsync|$owner|$repository|latest|Starlit-Linux-x86_64-*.AppImage.zsync"
    ./gradlew :composeApp:packageReleaseAppImage "${gradle_args[@]}"
    package="release/$name.AppImage"
    cp -- "composeApp/build/compose/release-appimages/$name.AppImage" "$package"
    test -s "$package"
    chmod +x "$package"
    # appimagetool already generates the matching zsync metadata when
    # UPDATE_INFORMATION is embedded; keep its exact output and URL.
    zsync_source="composeApp/build/compose/binaries/main-release/app/$name.AppImage.zsync"
    if [[ ! -s "$zsync_source" ]]; then
      echo "Missing appimagetool-generated zsync metadata: $zsync_source" >&2
      exit 1
    fi
    cp -- "$zsync_source" "$package.zsync"
    test -s "$package.zsync"
    ;;
  flatpak)
    ./gradlew :composeApp:createReleaseDistributable "${gradle_args[@]}"
    app_root="$PWD/composeApp/build/compose/binaries/main-release/app/Starlit"
    if [[ -x "$app_root/Starlit/bin/Starlit" ]]; then
      app_root="$app_root/Starlit"
    fi
    if [[ ! -x "$app_root/bin/Starlit" ]]; then
      echo "Cannot find the Starlit Linux launcher in $app_root" >&2
      exit 1
    fi
    sudo flatpak remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
    sudo flatpak install --system --noninteractive --assumeyes flathub org.gnome.Platform//50 org.gnome.Sdk//50
    work="$RUNNER_TEMP/starlit-flatpak-source"
    mkdir -p "$work"
    cp scripts/linux/nuvio-launcher.sh scripts/linux/linux-shortcut-definition.sh \
       scripts/linux/com.nuvio.media.desktop.metainfo.xml scripts/linux/nuvio-flatpak.yml "$work/"
    sed -i "s|version=\"0.0.0\"|version=\"$version\"|" "$work/com.nuvio.media.desktop.metainfo.xml"
    sed -i "s|date=\"1970-01-01\"|date=\"$(date -u +%F)\"|" "$work/com.nuvio.media.desktop.metainfo.xml"
    sed -i "s|\${app_root}|$app_root|g" "$work/nuvio-flatpak.yml"
    flatpak-builder --repo="$RUNNER_TEMP/starlit-flatpak-repo" --force-clean \
      "$RUNNER_TEMP/starlit-flatpak-build" "$work/nuvio-flatpak.yml"
    package="release/$name.flatpak"
    flatpak build-bundle "$RUNNER_TEMP/starlit-flatpak-repo" "$package" com.nuvio.media.desktop
    test -s "$package"
    ;;
esac

(
  cd release
  case "$format" in
    appimage) sha256sum "$name.AppImage" "$name.AppImage.zsync" > SHA256SUMS-linux-appimage.txt ;;
    *) sha256sum "$name.$format" > "SHA256SUMS-linux-$format.txt" ;;
  esac
  sha256sum --check "SHA256SUMS-linux-$format.txt"
)
echo "Linux $format package and checksums are ready."
