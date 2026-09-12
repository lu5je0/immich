{ pkgs ? import <nixpkgs> { config.allowUnfree = true; } }:

# Immich mobile dev shell.
#
# Flutter 3.47.2 is pinned by mobile/pubspec.yaml and is newer than nixpkgs'
# flutter, so the official stable tarball is pulled into the store and
# materialised into a writable dir on first use (the SDK writes into
# bin/cache at runtime).
#
# The Android SDK comes from nixpkgs' androidenv, with the components that
# Flutter 3.47.2 and AGP 8.11 ask for: compileSdk/targetSdk 36, NDK
# 28.2.13676358 and CMake 3.22.1 (app/CMakeLists.txt). Licenses are accepted
# through androidenv, so no `sdkmanager --licenses` step is required.
let
  flutterTarball = pkgs.fetchurl {
    url = "https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.47.2-stable.tar.xz";
    hash = "sha256-RHh4hZ0Bypv9uZqF8kWvB+2KFf7c2dGJxHSejpLR8YU=";
  };

  flutterSdk = pkgs.runCommandLocal "flutter-3.47.2-sdk" { } ''
    mkdir -p $out
    tar -xJf ${flutterTarball} -C $out --strip-components=1
  '';

  androidEnv = pkgs.androidenv.override { licenseAccepted = true; };
  androidSdk = (androidEnv.composeAndroidPackages {
    cmdLineToolsVersion = "latest";
    platformToolsVersion = "latest";
    # 36 for the app (compileSdk/targetSdk); 33-35 because Flutter plugins in
    # this app (e.g. bonsoir_android) compile against older platforms. The SDK
    # is read-only in the store, so every platform a module asks for must be
    # listed here or AGP fails while trying to auto-install it.
    platformVersions = [
      "33"
      "34"
      "35"
      "36"
    ];
    buildToolsVersions = [
      "35.0.0"
      "36.0.0"
    ];
    includeNDK = true;
    ndkVersion = "28.2.13676358";
    includeCmake = true;
    cmakeVersions = [ "3.22.1" ];
    # The remaining license groups `sdkmanager --licenses` lists, so that
    # `flutter doctor` reports the toolchain as fully accepted.
    extraLicenses = [
      "android-googletv-license"
      "android-googlexr-license"
      "android-sdk-arm-dbt-license"
      "android-sdk-preview-license"
      "google-gdk-license"
      "mips-android-sysimage-license"
    ];
  }).androidsdk;

  flutterRoot = "$HOME/.cache/immich/flutter-3.47.2";
in
pkgs.mkShell {
  name = "immich-mobile";

  buildInputs = with pkgs; [
    git
    which
    curl
    unzip
    xz
    file
    jdk21
    # `npx` backs the openapi-generator-cli shim below.
    nodejs
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = pkgs.jdk21.home;

  shellHook = ''
    export SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt

    export FLUTTER_ROOT=${flutterRoot}
    if [ ! -x "$FLUTTER_ROOT/bin/flutter" ]; then
      echo "materialising flutter 3.47.2 into $FLUTTER_ROOT ..."
      mkdir -p "$(dirname "$FLUTTER_ROOT")"
      cp -r ${flutterSdk} "$FLUTTER_ROOT"
      chmod -R u+w "$FLUTTER_ROOT"
    fi

    flutterPath="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin"
    androidPath="$ANDROID_HOME/platform-tools"
    for binDir in "$ANDROID_HOME"/cmdline-tools/*/bin; do
      androidPath="$binDir:$androidPath"
    done

    # open-api/bin/generate-dart-sdk.sh calls `openapi-generator-cli`. nixpkgs
    # only carries 7.22.0 while open-api/openapitools.json pins 7.25.0, so run
    # the pinned release through npx instead (fetched on first use).
    openapiBin="$(mktemp -d)"
    cat > "$openapiBin/openapi-generator-cli" <<'SHIM'
#!/usr/bin/env bash
exec npx --yes @openapitools/openapi-generator-cli "$@"
SHIM
    chmod +x "$openapiBin/openapi-generator-cli"

    export PATH="$openapiBin:$flutterPath:$androidPath:$PATH"

    echo "flutter: $($FLUTTER_ROOT/bin/flutter --version | head -1)"
    echo "android: $ANDROID_HOME (compileSdk 36, ndk 28.2.13676358)"
    echo "java:    $JAVA_HOME"
  '';
}
