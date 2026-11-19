# koma-launcher 一晩目 Implementation Plan

> **For agentic workers:** 実行方法は Native（同じセッションで実装し、最後にサブエージェント 1 体がレビューする）。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 漫画のコマ割りの絵を全画面に表示し、多角形のコマごとにフォルダ・ウィジェットを割り当てられるホームアプリの最初の版を、Waydroid で動かす。

**Architecture:** 依存が一方向のマルチモジュール（`:core:model` は純粋な Kotlin、`:core:platform` が Android のアダプタ、`:feature:*` はインターフェースだけに依存、`:app` が手動 DI で組み立てる）。拡張点（コマの種類・アニメーション・操作）は表への登録で増やす。

**Tech Stack:** Kotlin 2.4.21、AGP 9.5.1（Kotlin 組み込み）、Gradle 9.9.1、Compose BOM 2026.09.00、kotlinx.serialization 1.13.0、Spotless 8.11.0（ktlint）、JDK 21、Nix flake の devShell（androidenv）。

**Spec:** `docs/superpowers/specs/2026-11-19-koma-launcher-design.md`

## Global Constraints

- minSdk 33（Waydroid が API 33 のため）、targetSdk 36、compileSdk 37、compileSdk 36、縦向き固定、`applicationId = "dev.furunov.komalauncher"`
- 依存に Hilt・Room・Coil/Glide・Navigation を入れない
- `:core:model` は Android の API を import しない
- 機能のモジュールは `:core:platform` に依存しない
- 署名は `~/.config/koma-launcher/release.keystore`（`local.properties` で渡す）を debug と release の両方で使う
- 版権のある画像を git に入れない（`.gitignore`）
- コメントは日本語で、なぜそうしたかを書く
- 待機中 PSS 100 MB 以下を目標に測る

## Review Focus

1. layout.json が壊れている・パッケージ名が存在しない → 見本に戻り、割り当てを消さない（Task 3, 4 のテスト）
2. コマの頂点・辺の上・コマの外の点 → 判定が仕様どおり（Task 3 のテスト）
3. 画面と画像の縦横比が違う（1080×1920 と 1220×2712）→ cover の変換が同じ位置を指す（Task 3 のテスト）
4. フォルダに無いアプリの移動・同じ位置への移動・空のフォルダからの削除 → 状態が壊れない（Task 6 のテスト）
5. 保存の途中で強制終了 → `AtomicFile` で前の版が残る（Task 6 の FolderStore のテスト）

---

### Task 1: ビルド環境と骨格
**Files:** `flake.nix`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`, `app/`, 各モジュールの `build.gradle.kts`
- [ ] devShell（SDK platform 36・build-tools 36、JDK 21）を作り、`gradle wrapper --gradle-version 9.9.1`
- [ ] 全モジュールの空の骨格と、空の HOME Activity
- [ ] 署名の鍵を作る（`keytool`）
- [ ] `./gradlew assembleDebug` が通り、Waydroid に入れて起動する
- [ ] Commit

### Task 2: 公式スキルの配置
- [ ] android/skills から 4 つを `.claude/skills/` へ（LICENSE を添える）。Commit

### Task 3: `:core:model` の幾何と layout
**Produces:** `Point(x: Float, y: Float)`, `Polygon(points: List<Point>)` with `contains(p): Boolean`, `bounds(): Rect`, `largestInscribedRect(): Rect`; `CoverTransform(imageW, imageH, viewW, viewH)` with `toView(p)`, `toImage(p)`; `Layout`, `Panel(id: PanelId, kind: PanelContent, polygon: Polygon, label: String?)`, `sealed interface PanelContent { Folder(initialApps), Widget }`; `LayoutParser.parse(json: String): LayoutResult`（`Ok(layout)` / `Error(message)`）; `hitTest(panels, p): Panel?`（後のコマを優先）
- [ ] テスト: 凸・凹・斜めの辺、頂点と辺の上は内側扱い、外側は null、重なりは後のコマ、cover で 1080×1920 と 1220×2712 が同じ画像座標に戻る、内接長方形が多角形に含まれる、壊れた JSON は Error
- [ ] 実装、テストが通る、Commit

### Task 4: ホーム画面とフォルダ
**Produces:** インターフェース `LayoutSource`, `AppCatalog`, `IconLoader`；`HomeViewModel(state: StateFlow<HomeUiState>, onEvent(HomeEvent))`
- [ ] ViewModel のテスト（fake で、コマのタップでフォルダが開く、外のタップで閉じる、HOME で閉じる、壊れた layout で見本と誤りのラベル）
- [ ] コマの層の描画、タップ、FolderPopup、`:core:platform` の実装、AppContainer
- [ ] Waydroid で確認、Commit

### Task 5: ジェスチャーと操作、設定の保存
**Produces:** `GestureKind`, `sealed interface LauncherAction`, `ActionPerformer`, `Settings`, `SettingsStore`, `JsonFileStore<T>`（AtomicFile、300 ms でまとめて書く）, `SystemShade`
- [ ] 既定の対応のテスト、保存と読み直しのテスト
- [ ] 上スワイプで AppDrawer、下スワイプで通知センター。Commit

### Task 6: フォルダの編集
**Produces:** `FolderEdits`（`add`, `move`, `reorder`, `remove` の純粋な関数と undo）, `FolderStore`, `DragDropState`
- [ ] Review Focus 4・5 のテストを含む編集のテスト
- [ ] 一覧→コマ、フォルダ内の並べ替え、コマ間の移動、外す・元に戻す、長押しメニュー。Commit（ここで「最初に動く版」）

### Task 7〜16
設計の文書の「一晩の作業順」7〜16 に従う（ウィジェット、設定画面、計測、アニメーション、edge-to-edge、e2e、モックアップ、README、レビュー、Waydroid を戻す）。各項目を動く状態にしてから Commit する。
