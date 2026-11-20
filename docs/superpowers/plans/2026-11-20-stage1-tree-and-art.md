# 段階 1: コマ割りの木とコマごとの画像 Implementation Plan

> **For agentic workers:** 実行方法は Native（同じセッションで実装し、最後に Sonnet のサブエージェント 1 体がレビューする）。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** モックアップで作ったコマ割り（線の木）とコマごとの画像を、アプリのホーム画面で同じ形に表示する。あわせて、ウィジェットの上の長押しと、全アプリ一覧のコマを使えるようにする。

**Spec:** `docs/superpowers/specs/2026-11-20-koma-launcher-integration-design.md`（「段階 1 の詳細」）

## Global Constraints

- 一晩目の制約（`docs/superpowers/plans/2026-11-19-koma-launcher-night1.md`）をそのまま守る。特に `:core:model` は Android の API を import しない、画像をリポジトリに入れない
- 計算の式はモックアップ（`docs/mockups/editor.template.html` の `clip`・`pointAt`・`paramOf`・`layout`・`artLimits`・`placement`・`clampArt`）から移し、式を変えない。違えるときはコメントに理由を書く
- version 1 の layout.json を読めなくしない

## Review Focus

1. 木の切り抜き: 割った 2 つのコマが親を覆い、コマの間の幅だけ離れる。線が親の外へ出ない。面積が 0 になったコマがあってもアプリが止まらない（Task 1 のテスト）
2. 端を見せない制限: どの拡大率・ずらし・コマの形でも、絵の長方形がコマの外接長方形を覆う（Task 1 のテスト）
3. 壊れた JSON を取り込んでも、内部のレイアウトと割り当てが消えない。画像が見つからないときは、レイアウトを取り込み、そのコマは仮の色で描いて上端に知らせる（Task 3）
4. ウィジェットの上の長押しでコマのメニューが開き、短いタップはウィジェットに届く（Task 5）

---

### Task 1: `:core:model` の線の木と絵の置き場所
**Files:** `model/layout/Layout.kt`, `model/layout/LayoutTree.kt`（新規。木の計算・`LayoutResolver`・`ArtLimits`）, テスト
**Produces:** `LayoutNode`（`Leaf(panel)` / `Split(ta, tb, a, b)`）、`Frame`、`Border`、`PanelArt(image, aspect, zoom, dx, dy)`、`LayoutShape`（`Tree` / `Fixed`）、`TreeGeometry.clip`・`pointAt`・`paramOf`、`LayoutResolver.resolve(layout, w, h): ResolvedLayout`、`ArtLimits.placement`・`clamp`、`PanelContent.Drawer`
- [ ] テスト: 割った 2 つのコマの面積の和 + 境目の帯 = 親、幅 0 なら境目を共有、`pointAt(paramOf(p)) = p`、斜めの線、縦横比の違う画面で同じ木が同じ割合の位置を指す、0 個のコマを飛ばす、置き場所が外接長方形を覆う（拡大率 1・3、ずらしの最大・範囲外、縦長・横長のコマ、視差の余白あり）
- [ ] 実装、テストが通る

### Task 2: layout.json の version 2
**Files:** `model/layout/LayoutParser.kt`, `app/src/main/assets/sample/layout.json`, テスト
- [ ] テスト: v2 を読む、v1 を読む、v2 を書いて読み直すと同じ、id の重複・枝の子の欠け・色の誤りは Error、`aspect` の省略
- [ ] 見本を v2 の大ゴマ（id は `sample-` で始める）にする

### Task 3: 内部の layout.json と取り込み
**Files:** `platform/layout/FileLayoutStore.kt`（`FileLayoutSource` を置き換え）, `platform/layout/ArtImporter.kt`（新規）, `platform/files/Files.kt`, `model/ports/Ports.kt`, `AppContainer.kt`
- [ ] `LayoutState` に画像のパスの対応（`images`）と `error` を持たせる
- [ ] 外部の layout.json の監視 → 確かめる → 画像を縮めて写す → 内部を置き換える → `.imported` に名前を変える
- [ ] バックアップの書き出しは内部のレイアウト、読み込みは外部へ書いて取り込みに回す

### Task 4: ホーム画面の描画と全アプリ一覧のコマ
**Files:** `feature/home/PanelGeometry.kt`, `Art.kt`, `HomeScreen.kt`, `HomeViewModel.kt`, テスト
- [ ] `HomeGeometry` を `LayoutResolver` から作る。コマごとの絵を cover＋拡大率＋ずらしで描く
- [ ] 画像ごとに 1 回読む（使うコマの中で最大の大きさ、元の画像の大きさまで）
- [ ] 全アプリ一覧のコマ（タップで `Overlay.Drawer`）。ViewModel のテスト

### Task 5: ウィジェットの長押し
**Files:** `feature/widgets/WidgetHostController.kt`, `WidgetScreens.kt`, `MainActivity.kt`
- [ ] Waydroid で不具合を再現し、原因を確かめる
- [ ] `AppWidgetHostView` の継承で長押しを見分け、コマのメニューを開く

### Task 6: モックアップの取り込みスクリプトと確認
**Files:** `scripts/import-mockup.sh`（新規）, `scripts/e2e-waydroid.sh`, `README.md`
- [ ] state の JSON と手元の画像から v2 の layout.json を作り、`art/` と一緒に adb で送る
- [ ] e2e スクリプトの座標を新しい見本に合わせる
- [ ] Waydroid（1220×2712 を再現）で、取り込んだ画面とモックアップを並べたスクリーンショット
- [ ] `nix develop -c ./gradlew spotlessApply lint testDebugUnitTest assembleDebug assembleRelease`
- [ ] Sonnet のサブエージェント 1 体にレビューを頼み、指摘を直す（段階 2 以降は、段階 5 の後の 1 回にまとめる）。Commit
