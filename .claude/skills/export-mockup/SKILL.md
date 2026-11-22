---
name: export-mockup
description: claude.ai の Artifact で作ったコマ割りのモックアップ（編集画面の HTML）を、実機か Waydroid の koma-launcher に送る手順。「モックアップを実機に入れたい」「Artifact で作ったコマ割りをエクスポートしたい」と言われたとき、または新しいモックアップを作るときに使う。新しいモックアップを作るときは、後で送れるように保存の形を合わせる。
---

# モックアップを実機に送る

モックアップの保存状態（Artifact の db）と、ページに埋め込まれた画像から、version 2 の `layout.json` と画像を作り、端末の取り込み口 `/sdcard/Android/data/dev.furunov.komalauncher/files/` に `adb push` する。アプリは `layout.json` が置かれたことを検知して取り込む。取り込みの仕組みは `docs/layout-json.md` の「取り込み口」にある。

使うスクリプトは 2 つ。

- `scripts/extract-mockup-images.sh <ページの HTML> [出力先]`: ページの `const IMG = {...};`・`const INV = {...};`・`const MASK = {...};` の data URI を、`<出力先>/<名前>.webp`・`inv/<名前>.webp`・`mask/<名前>.webp` に書き出す。出力先の既定は `art/mockup/`（`.gitignore` にある）。
- `scripts/import-mockup.sh <state.json> <画像のディレクトリ> [layout.json の保存先]`: state を jq で `layout.json` に変換し、画像・切り抜き・`layout.json` の順に送る。

## 手順

1. モックアップの URL を確かめる。利用者に聞くか、`Artifact` の `action: "list"` で探す。まだ無ければ、`docs/mockups/editor.template.html` の `{{IMAGES_JSON}}`・`{{MASKS_JSON}}`・`{{INVMASKS_JSON}}` を、利用者の画像の data URI の JSON（`{"<名前>": "data:image/webp;base64,..."}`、切り抜きが無ければ `{}`）に置き換え、Artifact として公開する。保存に db を使うので、公開のときに db の capability を付ける。テンプレートの見本のコマ（`TEMPLATES` の `pic-a-5` など）は、利用者の画像の名前に書き換える。
2. 保存状態を取り出す。`ArtifactData` で `action: "get"`、`collection: "editor"`、`doc_id: "current"`、`out_dir: <スクラッチパッド>/db` とすると、`<out_dir>/editor/current.json` に `{ "state": {...}, "at": ... }` が書かれる。
3. ページの HTML を取り出す。`Artifact` の `action: "read"` に URL を渡すと、結果の先頭に「full HTML saved to <パス>」と保存先が書かれる。画像を含むので数 MB あり、`Read` で全体を読まない。
4. 画像を書き出す。

   ```sh
   scripts/extract-mockup-images.sh <手順 3 のパス> art/mockup
   ```

5. 端末がつながっているか確かめる。`adb devices` に 1 台だけ出ていればよい。複数あるときは `ANDROID_SERIAL` で選ぶ。アプリが入っていなければ、先に `docs/development.md` の「ビルドと確認」で入れる。
6. 送る。

   ```sh
   scripts/import-mockup.sh <手順 2 の current.json> art/mockup
   ```

   変換した `layout.json` は `build/mockup-layout.json` にも残る。送る前に中身だけ見たいときは、この手順の前にスクリプトの jq の部分を実行する。
7. 取り込めたか確かめる。`adb shell ls /sdcard/Android/data/dev.furunov.komalauncher/files/` で `layout.json.imported` に名前が変わっていれば、アプリが取り込んだ。`layout.json` のまま残っているか、ホームの上端に誤りが表示されたら、取り込みに失敗している。画面は `adb exec-out screencap -p > <スクラッチパッド>/home.png` で撮って見る。

画像には版権があるので、`art/` の外のリポジトリの中に書き出さない。コミットもしない。

## 新しいモックアップを作るとき

`import-mockup.sh` は、`docs/mockups/editor.template.html` と同じ保存の形を前提にしている。新しいモックアップをこのテンプレートから作れば、上の手順がそのまま使える。一から作るときは、次の 3 点を合わせる。

- 保存先: `window.claude.use("db")` の `db.doc("editor/current").set({ state, at })`。
- `state` の形（省いた項目は jq が既定値で埋める）。

  ```js
  {
    tree,                         // 下を参照
    gutter: 26, gutterColor: "#111111", bleed: false,
    border: { style: "single", width: 10, color: "#111111" },
    filter: "none" | "soft", strength: 1,          // look.tone と look.strength になる
    shade: { inner: 0.5, sheen: 0.4 },
    text: { label: "vertical" | "horizontal" | "none", lang: "en" | "ja",
            masthead: true, word: "KOMA", mastheadY: 0.09, panel: null },
    clock: { style: "none" | ほかの値, panel: null, x: 0.5, y: 0.83, size: 0.78 },  // style が "none" 以外なら時計を出す
    fx: { parallax: true, strength: 1 }
  }
  ```

  `tree` の葉は `{ panel: { id, kind: "folder" | "widget" | "drawer", label, art: { card, zoom, dx, dy } } }`、枝は `{ cut: { ta, tb }, a, b }`。`ta`・`tb` の意味は `docs/layout-json.md` の version 2 の説明と同じ。`card` は画像の名前で、`IMG` のキーと一致させる。テンプレートの型（`hcut`・`vcut`）は画面上の座標 `pts` で線を書くが、保存する前に `cut.ta`・`cut.tb` に変換しておく（テンプレートでは `layout()` が最初の描画で変換する）。
- 画像の埋め込み: `const IMG = {"名前": "data:image/webp;base64,..."};` を 1 行に書く。人物の切り抜きは同じ形で `const INV`（白が人物以外）か `const MASK`（白が人物）に書く。`extract-mockup-images.sh` は、この 3 つの変数を行の先頭から探す。

モックアップに新しい設定を追加したときは、3 か所をそろえて変更する。`import-mockup.sh` の jq、`docs/layout-json.md` の version 2 の説明、アプリの `LayoutParser`（`core/model/.../layout/LayoutParser.kt`）。アプリが知らない項目を `layout.json` に書いても、ホームには反映されない。
