# アイコンの形・縁・影 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 設定画面でアイコンの形（7 つ）・縁（3 つ）・影（有無）を選べるようにし、ホーム・フォルダ・全アプリ一覧・検索・メニューの全てのアイコンに反映する。

**Architecture:** 形の輪郭は `:core:model` の純粋な関数（0〜1 の正方形の中の線と三次曲線）で持ち、`:core:platform` の `IconRenderer` が Android の `Path` に直してアイコンを切り抜く（試作 `spike/icon-shapes` の `IconShaper` を元にする）。影はアイコンに焼き込まず、ぼかしたシルエットを別の画像として作り、描くときに光の向きと反対へずらす。設定は `Appearance` に置き、`LauncherIconLoader` が設定の変化でキャッシュを消して `styleRevision` を増やす。

**Tech Stack:** Kotlin、Jetpack Compose、Android Canvas（`Path`・`BlurMaskFilter`・`AdaptiveIconDrawable`）、kotlinx.serialization、JUnit。

**Spec:** `docs/superpowers/specs/2026-11-21-icon-shapes-design.md`

## Global Constraints

- 既定は今の見た目（端末の標準・縁なし・影なし）。既定のとき、アイコンの画像は今と同じく Android のアイコンをそのまま描いたものにする。
- 設定画面の文言: 行は「アイコンの形」「アイコンの縁」「アイコンの影」。形の選択肢は「端末の標準」「円」「角丸の四角」「スクワークル」「涙形」「吹き出し」「コマ形」。縁は「なし」「墨の線」「白フチ」。
- 影: ずらす量はアイコンの一辺の 10%、ぼかしは 4%、不透明度 0.7。ホームのコマのアイコンだけ傾きで向きを回し、それ以外は右下に固定する。
- 既存の `settings.json` とバックアップは、変更なしで読めること。
- モジュールの依存の向き（README の「構成」）を守る。`:core:model` は Android に依存しない。
- 計画での仕様の変更: 縁は形の外側ではなく、形の縁の内側に描く（形はアイコンの画像いっぱいに描き、見た目の大きさを縁なしとそろえる）。仕様の「縁は形の外側に描く」の段落を、この計画の Task 2 で書き換える。
- コメント・コミットメッセージは日本語。コミットの末尾は `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## Review Focus

1. 形・縁・影を変えた直後に、開いている全アプリ一覧やフォルダのアイコンも新しい見た目に変わること（Task 3 で `styleRevision` による読み直しを Waydroid で確かめる）。
2. 2 層でない古い形式のアイコンが、形の白い台の上に欠けずに載ること（Task 2 の Waydroid の確認で、古い形式のアプリを探して見る）。
3. 追加した PWA（固定したショートカット）のアイコンにも形が掛かること（Task 2 で `shortcutIcon` の経路も `IconRenderer` を通す）。
4. 影を付けたアイコンを人物の後ろに描くとき、影も人物の後ろに隠れること（Task 3 で影をアイコンと同じ層に描く）。
5. 「端末の標準」に縁か影を付けたとき、角丸の四角として切り抜かれること（Task 1 のテスト `systemWithRimUsesRounded`）。

---

### Task 1: 設定と形の輪郭（`:core:model`）

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/settings/Settings.kt`（`Appearance`）
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/icons/IconOutline.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/icons/IconOutlineTest.kt`、`core/model/src/test/kotlin/dev/furunov/komalauncher/model/settings/SettingsTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable enum class IconShape { SYSTEM, CIRCLE, ROUNDED, SQUIRCLE, TEAR, BUBBLE, KOMA }`、`@Serializable enum class IconRim { NONE, INK, STICKER }`（`@SerialName` は小文字の id: `system` など）
  - `Appearance.iconShape: IconShape = IconShape.SYSTEM`、`iconRim: IconRim = IconRim.NONE`、`iconShadow: Boolean = false`
  - `data class IconStyle(val shape: IconShape, val rim: IconRim, val shadow: Boolean)`、`val Appearance.iconStyle: IconStyle`、`val IconStyle.isPlain: Boolean`（端末の標準・縁なし・影なし）
  - `sealed interface OutlineOp { data class Move(x, y); data class Line(x, y); data class Cubic(x1, y1, x2, y2, x, y); data object Close }`（座標は 0〜1 の Float）
  - `object IconOutline { fun of(shape: IconShape, needsClip: Boolean): List<OutlineOp> }` — `SYSTEM` で `needsClip` なら `ROUNDED` の輪郭を返す。輪郭は試作 `IconShaper.shapePath` と同じ形（0〜100 を 0〜1 に）。円と涙形の弧、吹き出しの楕円は三次曲線（係数 0.5523）で近似する。吹き出しは楕円（中心 0.5, 0.45、半径 0.47, 0.43）の外周を一周し、角度 110° と 125° の間で尻尾の先 (0.10, 0.99) へ寄り道する 1 本の輪郭にする（`Path.op` を使わない）

- [ ] **Step 1: 失敗するテストを書く**
  - `SettingsTest.oldSettingsWithoutIconFieldsDecode`: `{"appearance":{"iconSizeDp":52}}` を読み、`iconStyle == IconStyle(IconShape.SYSTEM, IconRim.NONE, false)`。
  - `IconOutlineTest.allShapesFitInUnitSquare`: 全ての形（`needsClip = true`）の全ての点（制御点を含む）が -0.001〜1.001 に入る。
  - `IconOutlineTest.bubbleTailPointsToBottomLeft`: 吹き出しの点のうち y が最大の点の x が 0.2 未満、y が 0.95 より大きい。
  - `IconOutlineTest.systemWithRimUsesRounded`: `of(SYSTEM, needsClip = true) == of(ROUNDED, true)`。
  - `IconOutlineTest.outlinesAreClosed`: 全ての形の最後が `Close`、最初が `Move`。
- [ ] **Step 2: 失敗を確かめる** — Run: `nix develop -c ./gradlew :core:model:test` / Expected: コンパイルエラーか FAIL
- [ ] **Step 3: 実装する**（上の Interfaces どおり）
- [ ] **Step 4: 通ることを確かめる** — Run: `nix develop -c ./gradlew :core:model:test` / Expected: BUILD SUCCESSFUL
- [ ] **Step 5: コミット** `feat(model): アイコンの形・縁・影の設定と形の輪郭`

### Task 2: アイコンの描き方（`:core:platform`）

**Files:**
- Create: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/IconRenderer.kt`
- Modify: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/LauncherIconLoader.kt`、`core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/icons/IconLoader.kt`、`app/src/main/kotlin/dev/furunov/komalauncher/AppContainer.kt`、`docs/superpowers/specs/2026-11-21-icon-shapes-design.md`

**Interfaces:**
- Consumes: Task 1 の `IconStyle`・`IconOutline`
- Produces:
  - `object IconRenderer { fun icon(drawable: Drawable, size: Int, style: IconStyle): Bitmap; fun shadow(drawable: Drawable?, size: Int, style: IconStyle): Bitmap? }` — `icon`: `isPlain` なら今と同じ描き方。それ以外は、輪郭で切り抜き（adaptive icon は層を 1.5 倍に広げて重ね、古い形式は白い台に 16% の余白で載せる）、縁を輪郭の内側に描く（墨の線: 黒 `0xFF141416` の幅 = 一辺 × 0.06 の線を、輪郭で切り抜いて内側半分を残し、その内側に白の細い線を添える。白フチ: 白の幅 = 一辺 × 0.08 の帯を内側に描く）。`shadow`: `style.shadow` が false なら null。輪郭の黒いシルエットをぼかし（半径 = 一辺 × 0.04）、ぼかしがはみ出す分の余白（一辺 × 0.1）を四方に取った `size × 1.2` の正方形の画像を返す
  - `IconLoader` に追加: `val styleRevision: StateFlow<Long>`（既定は 0 の定数）、`fun cachedShadow(key: AppKey, sizePx: Int): ImageBitmap?`（既定 null）、`suspend fun loadShadow(key: AppKey, sizePx: Int): ImageBitmap?`（既定 null）、`const val SHADOW_PAD = 0.1f`（影の画像の余白の割合）
  - `LauncherIconLoader(context: Context, settings: StateFlow<Settings>, scope: CoroutineScope)` — `settings` の `appearance.iconStyle` が変わったら、キャッシュを全て消して `styleRevision` を 1 増やす。キャッシュのキーに形・縁・影を含める。影の画像も同じキャッシュに `…#shadow` のキーで持つ。ショートカットのアイコン（`shortcutIcon`）も同じ描き方を通す

- [ ] **Step 1: 仕様の「縁は形の外側に描く」の段落を、「縁は形の縁の内側に描き、形はアイコンの画像いっぱいに描く（見た目の大きさを縁なしとそろえる）」に書き換える**
- [ ] **Step 2: `IconRenderer` を書く**（試作 `git show spike/icon-shapes:core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/IconShaper.kt` を元にし、輪郭は `IconOutline` から作る）
- [ ] **Step 3: `IconLoader` と `LauncherIconLoader`、`AppContainer` を直す**（`LauncherIconLoader(context, settingsStore.settings, scope)`）
- [ ] **Step 4: ビルドを確かめる** — Run: `nix develop -c ./gradlew assembleDebug :core:model:test :feature:home:testDebugUnitTest` / Expected: BUILD SUCCESSFUL
- [ ] **Step 5: コミット** `feat(platform): アイコンを設定の形で切り抜き、縁と影の画像を作る`

### Task 3: 描く側（`:core:ui`・`:feature:home`）と設定画面

**Files:**
- Modify: `core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/icons/IconLoader.kt`（`AppIcon`）、`core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/icons/PanelIcons.kt`（`rememberIconBitmaps`）、`core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/art/PanelTexts.kt`（`drawPanelTexts` のアイコン）、`feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeScreen.kt`（アイコンの層）、`feature/settings/src/main/kotlin/dev/furunov/komalauncher/feature/settings/SettingsTable.kt`、`README.md`

**Interfaces:**
- Consumes: Task 2 の `styleRevision`・`cachedShadow`・`loadShadow`・`SHADOW_PAD`
- Produces:
  - `rememberIconBitmaps(keys)` は `State<List<IconPair>>`（`data class IconPair(val icon: ImageBitmap?, val shadow: ImageBitmap?)`）を返す。`styleRevision` が変わったら読み直す（ショートカットの `shortcutRevision` も今どおり）
  - `drawPanelTexts(…, iconShadows: List<ImageBitmap?> = emptyList(), shadowOffset: Offset = Offset.Zero)` — 各アイコンの直前に影を描く。影の画像は、アイコンの長方形を一辺 × `SHADOW_PAD` だけ四方に広げた長方形に、`shadowOffset` だけずらして不透明度 0.7 で描く

- [ ] **Step 1: `AppIcon` を直す** — `styleRevision` で読み直す。影があれば、アイコンの下に右下（一辺 × 0.1 × (0.64, 0.77)）へずらして描く（`Box` に重ね、レイアウトの大きさは変えない）
- [ ] **Step 2: `rememberIconBitmaps` と `drawPanelTexts` を直す**
- [ ] **Step 3: ホームのアイコンの層で、`shadowOffset` を光の向きから求めて渡す** — `-DepthStyle.lightDirection(tilt) × 一辺 × 0.1`。一辺はアイコンの長方形の幅。編集画面の縮小図は傾き 0 の向きで渡す
- [ ] **Step 4: 設定画面の「見た目」に 3 行を追加する**（`SettingRow.Choice` 2 つと `SettingRow.Toggle` 1 つ。形の説明に「端末の標準に縁や影を付けると、角丸の四角になります」を書けるなら書く）。README の「見た目」の説明にも追加する
- [ ] **Step 5: 確かめる** — Run: `nix develop -c ./gradlew spotlessApply :core:model:test :feature:home:testDebugUnitTest :feature:editor:testDebugUnitTest lint assembleDebug` / Expected: BUILD SUCCESSFUL。Waydroid（`build-and-install` スキル）で、設定画面から形・縁・影を切り替え、全アプリ一覧のアイコンが開いたまま変わることを確かめ、e2e の 21 項目を通す
- [ ] **Step 6: コミット** `feat: 設定画面でアイコンの形・縁・影を選べるようにする`

### Task 4: 実機での確認

- [ ] **Step 1:** release ビルドを実機に入れる（撮影の前に画面消灯までの時間を最長にし、終わったら 60000 に戻す）
- [ ] **Step 2:** 設定画面から「スクワークル・墨の線・影あり」「吹き出し・白フチ・影なし」「端末の標準・なし・影なし」を選び、試作のスクリーンショットと見比べる。影ありで端末を傾けると影の向きが回ることを、利用者に見てもらう
