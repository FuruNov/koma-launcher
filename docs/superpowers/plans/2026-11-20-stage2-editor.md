# 段階 2: 編集画面（線で割る） Implementation Plan

> **For agentic workers:** 実行方法は Native。レビューは段階 5 の後に 1 回だけ頼む。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ホームのメニューから入る編集画面で、モックアップと同じ操作（型の選択、コマを選ぶ、横・縦に割る、線の端を縁に沿って動かす、線を消す、種類の切り替え、元に戻す、フチとコマの間、コマごとの画像の選択と位置合わせ）ができるようにする。

**Spec:** `docs/superpowers/specs/2026-11-20-koma-launcher-integration-design.md`（段階 2）

## 設計

- 編集の操作は `:core:model` の `LayoutEdits`（純粋な関数）にする。木の形を変える操作は、選んだコマの画面上の多角形を受け取り、モックアップの `split()` と同じく外接長方形の中心を通る線を引く
  - `split(layout, panelId, dir, polygon)`: 元のコマの中身（id・フォルダ・画像）は a の子（上または左）が引き継ぎ、b の子は空のフォルダ「新しいコマ」
  - `merge(layout, panelId)`: 選んだコマを残し、兄弟のコマを取り除く。兄弟が枝なら何もしない（モックアップと同じ）
  - `moveCut(layout, path, end, t)`: 線の端を親の縁の上の位置 t へ
  - `setContent`・`setArt`・`setFrame`、型（大ゴマ・1 コマ）の生成、新しい id（`p` + 最大の番号 + 1）
  - 元に戻すは `EditHistory`（最大 50 件）
- レイアウトの書き換えの口は `LayoutStore`（`LayoutSource` に `update` と画像の取り込み `addImage(uri)` を加えたもの）。`FileLayoutStore` が実装する。見本を表示している間に編集を始めたら、見本の形から始める
- 編集画面は `:feature:editor`。`EditorActivity`（:app）が開く。画面の描画は、ホームと編集画面で共有する `:core:ui` の `PanelCanvas`（コマの形で切り抜いて画像を描く）を使う
- 画像の選択は `PickVisualMedia`（権限不要）。選んだ画像は長辺 2048 以下に縮めて内部へ写す
- 画像の位置合わせは、選んだコマの上のドラッグ（ずらし）とピンチ（拡大率）。`ArtLimits.clamp` で端を見せない範囲に収める
- 編集画面を閉じたら、どのコマも使っていない画像を `files/art/` から消す（元に戻す用の履歴が無くなってから）

## Tasks

### Task 1: `LayoutEdits` と `EditHistory`
- [ ] テスト: 割る（id の引き継ぎ、新しいコマ、線が外接長方形の中心を通る）、消す（兄弟が枝なら何もしない、根では何もしない）、線の端を動かす、種類と画像の変更、型、id の採番、履歴の上限
### Task 2: `LayoutStore` と画像の追加・掃除
### Task 3: `:core:ui` の `PanelCanvas` と、ホームの描画の共有
### Task 4: `:feature:editor` の画面と操作、ホームのメニューの入口
### Task 5: e2e に編集の操作を加え、Waydroid で確かめる
