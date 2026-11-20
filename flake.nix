{
  description = "koma-launcher: 漫画のコマ割りのホームアプリ";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      # compileSdk 37 に合わせる（Compose BOM 2026.09 と lifecycle 2.11 が 37 を要求する）。エミュレータは使わず Waydroid と実機で確かめるので含めない
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ];
        # AGP 9.5 の既定の build-tools は 36.0.0。aapt2 の差し替えにも同じ版を使う
        buildToolsVersions = [ "36.0.0" ];
        includeEmulator = false;
        includeNDK = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk21
          android.androidsdk
          # scripts/import-mockup.sh がモックアップの state を layout.json に直すのに使う
          pkgs.jq
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        JAVA_HOME = pkgs.jdk21.home;
        # AGP が Maven から取る aapt2 は FHS 前提の動的リンクのバイナリなので、
        # SDK に入っている Nix 版の aapt2 を使わせる
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2";
      };
    };
}
