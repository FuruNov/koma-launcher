---
name: fork-setup
description: koma-launcher をフォークした人が、自分の端末で使い続けられるように、パッケージ名（applicationId）・アプリ名・署名の鍵・目標の端末の前提を変える手順。「フォークした」「自分用に変えたい」「元のアプリと並べて入れたい」「パッケージ名を変えたい」と言われたときに使う。
---

# フォークした後の初期設定

元のリポジトリの APK と同じ端末に入れるなら、applicationId を変える。変えなければ、元の APK と署名が違うので上書きできず、アンインストールが要る（フォルダとウィジェットの割り当てが消える）。

## 1. applicationId を変える

Kotlin のパッケージ（`dev.furunov.komalauncher`）と各モジュールの `namespace` は変えなくてよい。ファイルの移動が大きくなるうえ、動作には関係しない。変えるのは端末が識別する ID だけにする。

1. 新しい ID を利用者に確かめる（例: `io.github.<ユーザー名>.komalauncher`）。
2. `app/build.gradle.kts` の `applicationId` を書き換える。`namespace` はそのままにする。
3. ID を文字列で持っている所を同じ ID に替える。

   ```sh
   git grep -n 'dev\.furunov\.komalauncher' -- scripts README.md docs .claude/skills app/src/debug
   ```

   - `scripts/*.sh` の `PKG=`
   - README・docs とスキルに書いた取り込み口 `/sdcard/Android/data/<ID>/files/`
   - docs の `adb shell am broadcast -p <ID>`（`-p` は applicationId を取る）
   - `app/src/debug/AndroidManifest.xml` と `DebugReceiver.kt` の action 名（`dev.furunov.komalauncher.DEBUG_TILT` など）は、ただの文字列なので変えなくても動く。変えるなら両方をそろえる。
   - `app/src/main/AndroidManifest.xml` の `taskAffinity` も文字列なので、変えなくても動く。

4. `nix develop -c ./gradlew assembleDebug` が通り、`nix develop -c sh -c '$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk' | head -1` の `package: name=` が新しい ID になっていることを確かめる。

## 2. アプリ名

`app/src/main/res/values/strings.xml` の `app_name` を変える。元のアプリと並べるなら、ホームアプリの選択画面で区別できる名前にする。

## 3. 署名の鍵

鍵は利用者が作り、リポジトリの外に置く。エージェントはパスワードを決めたり、どこかへ書き込んだりしない。手順を示し、利用者に実行してもらう。

```sh
mkdir -p ~/.config/koma-launcher
keytool -genkeypair -v -keystore ~/.config/koma-launcher/release.keystore \
  -alias koma -keyalg RSA -keysize 4096 -validity 36500
# 入力したパスワードを 1 行だけのファイルに書き、権限を絞る
chmod 600 ~/.config/koma-launcher/password.txt
```

`local.properties`（`.gitignore` にある）に場所を書く。

```properties
koma.keystore=/home/<ユーザー>/.config/koma-launcher/release.keystore
koma.keystorePasswordFile=/home/<ユーザー>/.config/koma-launcher/password.txt
```

別名は `koma` に固定してある（`app/build.gradle.kts` の `keyAlias`）。鍵が無いと、debug は Android の debug 鍵で署名し、release は署名なし（`app-release-unsigned.apk`）になる。実機に入れた後で鍵を変えると更新できなくなるので、鍵とパスワードのファイルのバックアップを利用者に勧める。

## 4. 目標の端末

元の作者の前提は POCO X7 Pro（1220×2712、HyperOS）と Waydroid（Android 13、1080×1920）で、次の所に現れる。端末が違っても動くが、見た目と e2e の座標が合わないことがある。

- `layout.json` の `refWidth`（既定 1220）。長さは画面の幅がこの値のときの画素数で書くので、幅の違う端末でも比率は保たれる。変えなくてよい。
- `app/src/main/assets/sample/layout.json`: 初回に表示する見本。
- `scripts/e2e-waydroid.sh`: 1080×1920 の座標を前提にしている。Waydroid の解像度が違うなら座標を測り直す。
- `minSdk = 33`（Waydroid が API 33 のため）。実機だけで確かめるなら上げてもよい。
- README の冒頭と、`docs/development.md` の「まだ実機で確かめていないこと」。自分の端末の名前と結果に書き換える。

## 5. 確かめる

`build-and-install` スキルの手順で入れ、既定のホームアプリにできること、見本のコマ割りが表示されることを確かめる。
