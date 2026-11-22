# 開発の手順と構成

ビルド、モジュールの構成、機能の増やし方、計測の結果。AI のエージェントに作業を頼むときの手順は、リポジトリの直下の `AGENTS.md` にある。

## フォークして使う

AI のエージェント（Claude Code など）に作業を頼む前提で、手順を `AGENTS.md` と `.claude/skills/` に書いてある。フォークした直後は `fork-setup`（パッケージ名・アプリ名・署名の鍵の変更）、自分の画像でコマ割りを作るなら `make-layout` から始める。

## ビルドと確認

```sh
nix develop -c ./gradlew assembleDebug          # debug の APK
nix develop -c ./gradlew assembleRelease        # R8 で縮小した APK
nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest
nix develop -c ./gradlew spotlessApply lint
scripts/e2e-waydroid.sh                         # Waydroid で一連の操作（21 項目。確かめる間だけ既定のホームにし、アプリのファイルは退避して終わったら戻す）
scripts/measure.sh [ラベル]                      # 起動時間・メモリ・フレーム → docs/measurements/
scripts/record-animations.sh <出力先>             # アニメーションの各案を録画（debug ビルドと su が要る）
perl scripts/gen-launcher-icon.pl                # アプリのアイコン（vector drawable）を書き出す
```

- 署名の鍵は `~/.config/koma-launcher/release.keystore`（リポジトリの外）。`local.properties` の `koma.keystore` と `koma.keystorePasswordFile` で場所を渡す。debug も同じ鍵で署名する。実機に入れた後で鍵が変わると、更新にアンインストールが要り、フォルダとウィジェットの割り当てが消えるため。**鍵とパスワードのファイルはバックアップしておくこと。**
- AGP が Maven から取る aapt2 は FHS 前提のバイナリなので、devShell が `android.aapt2FromMavenOverride` で SDK の aapt2 を使わせている。
- minSdk は 33。実機は Android 15 以降だが、Waydroid が API 33 のため。

## 構成

依存は上から下への一方向だけ。

| モジュール | 中身 | 依存してよいもの |
|---|---|---|
| `:core:model` | 純粋な Kotlin。多角形の判定・内接長方形・cover の変換、線の木と絵の置き場所、layout.json、フォルダの編集（純粋な関数）、設定とジェスチャー、境界のインターフェース | なし |
| `:core:ui` | 機能をまたぐ Compose のコンポーネント。ドラッグ＆ドロップの状態、アイコンの表示とそのインターフェース | `:core:model` |
| `:core:platform` | Android の API を包むアダプタ（下の表の実装） | `:core:model`, `:core:ui` |
| `:feature:home` | ホーム画面、ジェスチャー、フォルダ、アニメーション、ViewModel | `:core:ui` |
| `:feature:drawer` | アプリドロワー | `:core:ui` |
| `:feature:widgets` | ウィジェットの確保・bind・表示・選択画面 | `:core:ui` |
| `:feature:settings` | 設定画面 | `:core:ui` |
| `:feature:editor` | コマ割りの編集画面（線で割る、画像の選択と位置合わせ、フチ） | `:core:ui` |
| `:app` | `AppContainer` で実装を組み立てる。Activity と Manifest | すべて |

機能のモジュールどうしは互いを知らない。ホームにアプリドロワーやウィジェットを差し込むのは `:app` で、`HomeSlots` を通して渡す。

### 境界のインターフェース

| インターフェース | 実装 |
|---|---|
| `LayoutSource`・`LayoutStore` | `FileLayoutStore`（内部の layout.json を持ち、外部ファイル領域の layout.json を FileObserver で監視して取り込む。編集画面の書き換えと画像の追加も受ける） |
| `FolderStore` / `SettingsStore` / `WidgetStore` | `JsonFileStore`（AtomicFile、300 ms まとめて書く） |
| `AppCatalog` | `LauncherAppsCatalog`（LauncherApps。ショートカットも） |
| `IconLoader` | `LauncherIconLoader`（表示する大きさで 1 回だけ Bitmap にし、LruCache に約 4 MB） |
| `ActionPerformer` | `DefaultActionPerformer`（:app） |
| `SystemShade` | `ReflectionSystemShade`（StatusBarManager の非公開のメソッド） |
| `ScreenEvents` | `BroadcastScreenEvents`（Application の寿命で SCREEN_OFF と USER_PRESENT を受ける） |
| `LayoutImporter` / `BackupService` | `SafLayoutImporter` / `SafBackupService` |

### 増やし方

| 増やすもの | 手順 |
|---|---|
| コマの種類 | `PanelContent` に 1 つ加え、`LayoutParser` の `Kind` と、ホームでの扱い（`HomeViewModel.onEvent` の `TapPanel`、`HomeScreen` の描画）を加える。 |
| アニメーション | `PanelAnimation` を実装したオブジェクトを `feature/home/.../anim/PanelAnimation.kt` に 1 つ書いて `PanelAnimations` に登録し、`AnimationCatalog`（`:core:model`）に名前を 1 行加える。設定画面の選択肢に並ぶ。 |
| ジェスチャーに割り当てる操作 | `LauncherAction` に 1 つ加え、ホームの中で完結するなら `HomeViewModel.perform`、外へ出るなら `DefaultActionPerformer` に 1 行加える。 |
| 設定の項目 | `feature/settings/.../SettingsTable.kt` の `settingsSections()` に 1 行加える。画面のコードは触らない。 |

### 依存を絞った理由

| 決めたこと | 理由 |
|---|---|
| Hilt を使わない | 公式の `testing-setup` スキルは DI が無ければ Hilt を入れるよう勧めるが、この規模では、Hilt の KSP の設定と生成コードの保守のほうが手間が大きい。実装の組み立ては `AppContainer` の 30 行に収まっている。 |
| Room・DataStore を使わない | 保存する値は数 KB で、全体をメモリに持って丸ごと書き直せば足りる。 |
| Coil/Glide・Navigation・Material3 を使わない | アイコンの読み込みは自前の 60 行で済み、画面の切り替えは「重ねるものを 1 つだけ持つ」状態（`Overlay`）で足りる。APK とメモリを小さくするため。 |
| ML Kit を使わない | 段階 4 では端末の中で人物を切り抜くために ML Kit の被写体の切り抜きを入れたが、イラストでは輪郭が粗かった。切り抜きは PC の BiRefNet で作り、「フォルダから復元」で取り込む。 |
| 書体を 2 つ同梱する | Dela Gothic One（時計と日本語の名前、約 2.5 MB）と Bodoni Moda の斜体（大見出しと英語の名前）。どちらも SIL Open Font License で、全文を `licenses/` に置いた。 |

## 動作の軽さ

`scripts/measure.sh` の結果は `docs/measurements/` にある。Waydroid の release ビルドでの最初の計測（2026-11-19）は次のとおり。

| 項目 | 値 |
|---|---|
| APK | 2.2 MB |
| コールドスタート | 約 240 ms |
| 待機中の PSS（時計のウィジェット 1 つ） | 65 MB |
| フォルダの開閉 20 回 | ジャンク 0 / 62 フレーム、90 パーセンタイル 8 ms |

工夫は次のとおり。背景の絵は画面の大きさに縮小して `ALLOCATOR_HARDWARE` で読み、GPU 側に置く。アニメーションの値は `graphicsLayer` の中で読み、画面の作り直しを起こさない。アイコンは見えている分だけ読む。ホームが裏に回ったら（`onTrimMemory`）アイコンのキャッシュを空にする。

## AI のエージェント向けのスキル

`.claude/skills/` に、このリポジトリの手順のスキル 5 つ（`fork-setup`、`build-and-install`、`change-feature`、`make-layout`、`export-mockup`）と、github.com/android/skills（Apache-2.0）から写した 4 つ（`edge-to-edge`、`testing-setup`、`android-intent-security`、`jetpack-compose-performance`）を置いた。使い分けは `AGENTS.md`、出所は `.claude/skills/README.md` にある。

## まだ実機で確かめていないこと

- ロック解除で `USER_PRESENT` が届き、アニメーションが再生されるか（顔認証・指紋認証でロック画面を飛ばした場合も含む） allow-slop: 生体認証の術語としての「指紋認証」
- リフレクションで通知センターを開けるか。開けなければ AccessibilityService の実装に切り替える
- HyperOS の設定でホームアプリを切り替えられるか、ジェスチャーナビゲーションで戻る・ホーム・履歴が動くか
- 導入は `adb install`。HyperOS は「USB 経由のインストール」の許可が別に要る
