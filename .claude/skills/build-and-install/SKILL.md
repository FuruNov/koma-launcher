---
name: build-and-install
description: koma-launcher をビルドし、実機か Waydroid にインストールしてデフォルトのホームアプリにする手順と、GitHub Release で APK を配る手順。「ビルドして」「実機にインストールして」「Waydroid で確認して」「Release を作って」「バージョン番号を変えて」と言われたとき、adb install が止まったときに使う。
---

# ビルドと導入

## 環境

- x86_64-linux で Nix があれば、`nix develop -c <コマンド>` で JDK 21・Android SDK（compileSdk 37、build-tools 36.0.0）・jq がそろう。devShell は AGP の aapt2 を SDK の aapt2 に差し替える（Maven の aapt2 は NixOS で動かないため）。
- Nix が無い・macOS・Windows なら Android Studio で開く。SDK Manager で Android 37 と build-tools 36.0.0 を入れ、Gradle の JDK を 21 にする。`flake.nix` の `GRADLE_OPTS` は要らない。以下のコマンドは `nix develop -c` を外して読む。
- adb は devShell の SDK に含まれる。

## ビルド

```sh
nix develop -c ./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
nix develop -c ./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk（R8 で縮小）
```

署名鍵を `local.properties` で渡していれば、debug も release も同じ鍵で署名する。鍵の作り方は `fork-setup` スキル。鍵が無いと release は `app-release-unsigned.apk` になり、そのままではインストールできない。

debug ビルドにだけ、adb から挙動を再現する受け口（`DebugReceiver`）がある。ロック解除のアニメーション、傾き、音楽の再生、ビジュアライザーの記録。release をインストールした端末ではこれらを使えない。

## 端末にインストールする

```sh
adb devices                                  # 1 台だけ出ていればよい。複数なら ANDROID_SERIAL で選ぶ
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity dev.furunov.komalauncher/.MainActivity
```

- applicationId を変えたフォークでは、`dev.furunov.komalauncher` を自分の ID に読み替える。
- 実機は USB より「ワイヤレス デバッグ」が楽（`adb pair` → `adb connect`）。
- HyperOS（Xiaomi・POCO）は、開発者向けオプションの「USB 経由でインストール」を別に許可する必要がある。
- 署名の違う APK がインストールされていると `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で失敗する。アンインストールするとフォルダとウィジェットの割り当てが消えるので、利用者に確認してから行う。先に設定画面の「自動バックアップ」でバックアップを取ってもらう。

## Waydroid

- `adb connect <Waydroid の IP>:5555`。IP は `waydroid status` に出る。
- `adb install` が終わらないときは、dex2oat32 が pid 65535 超えで止まっていることが多い。次を実行してから再インストールする。adb のコマンドには `timeout` を付ける。

  ```sh
  timeout 20 adb shell su -c "pkill dex2oat32; setprop pm.dexopt.install skip; setprop pm.dexopt.cmdline skip"
  ```

  setprop は Waydroid を再起動すると元に戻る。
- `wm size` と `wm density` の上書きは再起動後も残る。使ったら `wm size reset` と `wm density reset` で戻す。
- `scripts/e2e-waydroid.sh` はホームの一連の操作（21 項目）を確認する。始める前にアプリのファイルを退避してサンプルのレイアウトに戻し、終わったら戻す。su と 1080×1920 の画面を前提にしている。

## 計測

`scripts/measure.sh <ラベル>` が起動時間・メモリ・フレームを `docs/measurements/` に書く。release ビルドで測る。

## Release を作る

GitHub Actions は使わない（署名鍵を GitHub に預けないため）。手元でビルドして `gh` でアップロードする。

1. `app/build.gradle.kts` の `versionCode`（1 増やす）と `versionName` を変え、`chore: バージョンを <バージョン> に上げる` でコミットする。機能の追加ならマイナー、修正だけならパッチの番号を 1 増やす。
2. ワークツリーの未コミットの変更を含めないよう、スクラッチパッドへ clone してビルドする。

   ```sh
   git clone . <スクラッチパッド>/rel && cp local.properties <スクラッチパッド>/rel/
   (cd <スクラッチパッド>/rel && nix develop -c ./gradlew assembleRelease)
   ```

3. APK の名前を `koma-launcher-v<バージョン>.apk` にして公開する。`--target` には完全な SHA を渡す（短い SHA は受け付けない）。

   ```sh
   gh release create v<バージョン> koma-launcher-v<バージョン>.apk --target "$(git rev-parse HEAD)" --prerelease \
     --title "v<バージョン>（<主な変更>）" --notes-file <ノート>
   ```

   ノートには変更点と「実機で確認してほしいこと」の節を書く。
4. ブラウザから APK をインストールした端末では、通知へのアクセスを許可する前に、アプリ情報の右上のメニューから「制限付き設定を許可」を選ぶ必要がある。ノートにも書いておく。
