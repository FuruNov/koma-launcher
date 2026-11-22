---
name: change-feature
description: koma-launcher の機能を追加・修正するときの進め方。どのモジュールのどのファイルに書くか、コマの種類・アニメーション・ジェスチャーの操作・設定の項目・layout.json の項目を追加する手順、テスト、Waydroid での確認、コミットメッセージの書き方。コードを変更する依頼を受けたら、最初に読む。
---

# 機能を変える

## 1. 書く場所を決める

依存の向きは `docs/development.md` の「構成」の表のとおり。迷ったら次の順に考える。

1. Android の API を使わない計算（形・座標・照合・JSON の読み書き・編集の操作）は `:core:model` に純粋な関数で書き、JUnit でテストする。例: `model/layout/LayoutEdits.kt`（線の木の編集）、`model/apps/AppSearch.kt`（検索の照合）、`model/media/Spectrum.kt`（ビジュアライザーの値）。
2. Android の API の呼び出しは `:core:platform` に置き、`:core:model/model/ports/Ports.kt` のインターフェースを実装する。組み立ては `app/.../AppContainer.kt`。
3. 画面は `:feature:*`。機能のモジュールどうしは参照しない。ホームに別の機能を差し込むときは `:app` から `HomeSlots` で渡す。
4. 機能をまたぐ Compose のコンポーネント（コマの絵・文字・アイコンの描画、ドラッグ）は `:core:ui`。

## 2. よくある変更

`docs/development.md` の「機能の追加」に要点がある。手順の全体は次のとおり。

- **コマの種類**: `core/model/.../model/layout/Layout.kt` の `PanelContent` に追加 → `LayoutParser.kt` の `Kind` と DTO の変換 → `feature/home/.../HomeViewModel.kt` の `onEvent` の `TapPanel` → `HomeScreen.kt` の描画 → 編集画面で選べるなら `feature/editor/.../EditorScreen.kt` → `docs/layout-json.md` の `kind` の説明。
- **アニメーション**: `feature/home/.../anim/PanelAnimation.kt` に `PanelAnimation` を実装したオブジェクトを書き、`PanelAnimations` に登録 → `core/model/.../model/settings/Settings.kt` の `AnimationCatalog` に id と表示名を 1 行。設定画面の選択肢は自動で増える。
- **ジェスチャーに割り当てる操作**: `Settings.kt` の `LauncherAction` に `@SerialName` を付けて追加 → ホームの中で完結するなら `HomeViewModel.perform`、外へ出るなら `app/.../DefaultActionPerformer.kt`。`@SerialName` は保存した設定の互換に関わるので、既存の名前を変えない。
- **設定の項目**: `feature/settings/.../SettingsTable.kt` の `settingsSections()` に 1 行。値は `Settings.kt` の `Settings` にデフォルト値付きで追加する（既存の `settings.json` を読めるように）。
- **layout.json の項目**: `Layout.kt` のデータ型 → `LayoutParser.kt` の DTO（省略できる値はデフォルト値を持たせ、`validate` に範囲の検査）→ `LayoutV2ParserTest.kt` に読み書きの往復のテスト → 編集画面で変えるなら `EditorViewModel.kt` と `EditorScreen.kt`、編集の操作は `PartEdits.kt` / `LayoutEdits.kt` → `docs/layout-json.md` の「version 2」の説明。モックアップから送る値なら `scripts/import-mockup.sh` の jq も修正する。
- **ビジュアライザー**: 実機の記録を `DEBUG_VIS` の `record` で取り、`tools/vis-replay`（`nix develop -c ./gradlew :tools:vis-replay:run --args=...`）で設定ごとの計算を PC 上で比べる。数値の基準（揺れ、平均の高さ、無音で 0 など）を先に利用者と決める。

## 3. テストと確認

```sh
nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest :feature:editor:testDebugUnitTest
nix develop -c ./gradlew spotlessApply lint
nix develop -c ./gradlew assembleDebug
```

- `:core:model` の変更はテストを先に書く。ViewModel はフェイクの Store で `HomeViewModelTest.kt` / `EditorViewModelTest.kt` と同じ形でテストする。
- 画面の変更は Waydroid か実機にインストールして確認する（`build-and-install` スキル）。ホームの基本の操作に触れたら `scripts/e2e-waydroid.sh` を通す。座標を前提にした項目が変わったら、スクリプトも修正する。
- センサー・ロック画面・音楽が無い Waydroid では、debug ビルドの受け口を使う。

  ```sh
  adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y 0
  adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_PLAY_UNLOCK
  adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_MEDIA --es state play
  ```

- 描画が重くなっていないかは、`scripts/measure.sh` で前の結果（`docs/measurements/`）と比べる。アニメーションの値は `graphicsLayer { }` の中で読み、再コンポーズを起こさない（`jetpack-compose-performance` スキル）。

## 4. 文言とドキュメント

- UI の文言は Android の日本語 UI の慣習に合わせる。ボタンと項目は「分割」「結合」「削除」「非表示」「キャンセル」「サイズ」のような漢語・カタカナ語の体言止めにし、「横に割る」「線を消す」のような動詞句にしない。
- 利用者から見える動作を変えたら、README（主な操作）か `docs/usage.md`（機能の詳細）の該当する節を同じコミットで修正する。layout.json の項目なら `docs/layout-json.md` を修正する。

## 5. コミット

Conventional Commits の形で、型とスコープは英語、要約は日本語で書く。スコープはモジュールか機能の名前（`home`・`editor`・`player`・`platform`・`tools` など）。

```
feat(editor): コマごとの設定と全体の設定をタブに分ける
fix(platform): debug で送った傾きを、センサーの値で上書きしない
chore: バージョンを 0.9.0 に上げる
```

本文には、なぜその変更にしたかと、確認した方法を書く。バージョン番号を変えるコミットは、機能のコミットと分ける。
