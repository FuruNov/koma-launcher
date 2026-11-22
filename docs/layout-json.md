# layout.json の書き方と取り込み

コマ割りを PC で作るときや、たくさんの絵をまとめて入れるときに読む。画面の上での操作は [usage.md](usage.md) にある。

## 取り込み口

レイアウトはアプリの内部（`files/layout.json` と `files/art/`）に持つ。外から入れるときは、`/sdcard/Android/data/dev.furunov.komalauncher/files/` を取り込み口に使う。ここに `layout.json` を置くと、アプリは中身を確かめ、参照する画像を内部へ写してから、ホームをその形に変える。取り込んだ `layout.json` は `layout.json.imported` に名前が変わり、写し終えた画像は消える。設定画面の「フォルダから復元」も、同じ取り込み口を通る。

モックアップ（`docs/mockups/`）で作ったコマ割りは、`scripts/import-mockup.sh <state.json> <画像のディレクトリ>` で送れる。`state.json` は Artifact の db の `editor/current`。画像はページに埋め込まれているので、`scripts/extract-mockup-images.sh <ページの HTML>` で書き出す。Claude に頼むときの手順と、新しいモックアップが守る保存の形は `.claude/skills/export-mockup/SKILL.md` にある。

## version 2（線の木とコマごとの画像）

```json
{ "version": 2,
  "frame": { "refWidth": 1220, "margin": 24, "gutter": 9, "gutterColor": "#ffffff", "bleed": false,
             "border": { "style": "single", "width": 24, "color": "#111111" } },
  "tree": { "cut": { "ta": 0.3246, "tb": 0.7999 },
            "a": { "panel": { "id": "fav", "kind": "folder", "label": "よく使う", "labelEn": "FAVORITES", "apps": ["com.android.chrome"],
                              "art": { "image": "pic-a-5", "zoom": 1, "dx": -0.067, "dy": 0 } } },
            "b": { "panel": { "id": "all", "kind": "drawer", "label": "その他" } } } }
```

- コマ割りは、コマを線で 2 つに割ることを繰り返した木で書く。葉は `panel`、枝は `cut`・`a`・`b`。`ta`・`tb` は線の両端の位置で、親のコマの周に対する割合（左上の頂点から時計回り）。`a` は線の進む向きの左側のコマ。画面の縦横比が変わっても、線の端は縁の同じ割合の位置に来る。
- 長さ（`margin`・`gutter`・`border.width`）は、画面の幅が `refWidth` のときの画素数で書く。`gutterColor` を省くと設定の「すき間の色」を使う。枠線（`border`）はコマの内側に引く。
- `look` はコマの見た目。`tone`（`none`・`soft` の淡い色合い）、`strength`（かかり具合）、`inner`（内側の影）、`sheen`（光の反射）。どれも 0〜1 で、省くと何もしない。
- `text` は文字。`label`（`vertical`・`horizontal`・`none`）はコマの名前の向き、`lang`（`en`・`ja`）は名前の言語（`en` ならコマの `labelEn` を、無ければ `label` を出す）。以前の `masthead`（大見出しの文字）・`mastheadY`・`mastheadPanel` は、ホームで開いたときに同じ位置と大きさの内蔵のテキストのウィジェット（`widgets` の `koma:text`）に書き換え、`masthead` を空にする。
- `clock` は以前の時計（`enabled`、`panel`、`x`・`y`、`size`）。`enabled` が true なら、ホームで開いたときに同じ位置と大きさの内蔵の時計のウィジェット（`widgets` の `koma:clock`）に書き換え、`enabled` を false にする。
- `widgets` はコマから独立して置くウィジェットの並び（省くと置かない）。1 つごとに `id`（英数字・`_`・`-`）、`provider`（`パッケージ名/クラス名`。ランチャーに内蔵の時計は `koma:clock`、テキストは `koma:text`）、`x`・`y`（画面に対する左上の位置）、`w`・`h`（画面に対する幅と高さ）、`front`（true なら人物の前、省くと人物の後ろ）を書く。後に書いたものほど上に描く。端末ごとに変わるウィジェットの番号は layout.json に書かず、端末の側で `id` に結び付ける。ホームの余白の長押しの「ウィジェットを追加」で置き、ウィジェットの長押しで「配置を調整」「変更」「削除」を選ぶ。「配置を調整」では、ドラッグで移動、2 本指でつまんで拡大縮小、右下の丸で幅と高さを変え、重ね順のボタンで人物の前と後ろを切り替える。
- 内蔵のウィジェットは `style` で見た目を変える（省くと以前の時計と大見出しと同じ見た目）。`font`（`dela`・`bodoni`・`sans`・`serif`・`mono`・`condensed`）、`weight`（100〜900。`sans` などの端末の書体だけ）、`date`（`long`・`short`・`none`）、`h24`、`seconds`、`color`（`auto`・`white`・`black`・`accent`）、`shadow`、`text`（テキストの文字）、`italic`。ホームでは、ウィジェットの長押しの「スタイル」で変える。内蔵のウィジェットはコマの文字の層で描くので、以前の時計と同じく、コマの形で切り取り、人物の後ろに回し、コマと一緒に動き、傾けたときの視差も付く。重ね順は、かかるコマの `order` の `clock`（時計）・`masthead`（テキスト）で決まる。
- `fx` は視差。`parallax` を true にすると、端末を傾けたとき絵（人物の切り抜きと一緒に）を傾けた向きへ、文字と時計を逆へ動かす（基準の幅で絵 26・時計 18・文字 7 画素 × `strength`）。光の反射も逆へ流れる。センサーはホームが見えている間だけ読む。センサーの無い Waydroid では、debug ビルドに `adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y 0` で傾きを送って確かめる。
- 人物の切り抜きがあると、名前・大見出し・時計を人物の後ろに描く。取り込み口の `art/<image>.inv.(png|webp)`（白が人物以外）か `art/<image>.mask.(png|webp)`（白が人物。アプリが濃さ 0.35〜0.65 を 0〜1 に詰めて反転する）に置く。切り抜きは PC で作る（BiRefNet）。端末の中で作る ML Kit の切り抜きは、イラストでは輪郭が粗かったので外した。
- `depth` は前景の人物の立体感で、切り抜きのある絵にだけ掛かる。どれも省くと切ってある。`shadow`（`on`・`alpha`・`distance`・`blur`）は人物の影を光と反対へずらして背景と文字に落とす。`rim`（`on`・`alpha`・`width`）は光の側の輪郭を明るく描く。光の向きは傾きで回る。`blur`（`on`・`amount`・`haze`）は人物以外をぼかし、紙の色を重ねる。`outline`（`on`・`width`・`alpha`）は人物に白い縁を付ける。`pop.width` は、コマの `pop` を true にしたコマで人物を枠の外へはみ出させる量。はみ出した部分は隣のコマの上にも描くが、タップの判定はコマの形のまま変えない。長さは基準の幅のときの画素で書き、`alpha`・`amount`・`haze` は 0〜1 で書く。編集画面の「全体」と「選択中のコマ」でも変えられる。
- コマの `order` は、そのコマの中の重ね順。`icons`・`label`・`masthead`・`clock`・`person` を後ろから順に並べる。先頭が背景の絵のすぐ上で、背景より後ろには置けない。`person` より後ろのものは人物の形で抜いて人物の後ろに見せ、前のものは人物の上に描く。省くと `["icons", "label", "masthead", "clock", "person"]`（文字とアイコンはすべて人物の後ろ）。ホームの「配置を調整」で、触ったコンポーネントを「最背面」「背面」「前面」「最前面」のボタンで動かせる。
- `kind` は `folder`（フォルダ）・`drawer`（押すとアプリドロワーを開く）。以前の `widget`（ウィジェットのコマ）は廃止し、フォルダとして読む。ウィジェットは `widgets` で置く。
- `label` はコマに表示する名前、`labelEn` は英語表示のときの名前。どちらも省ける。アプリは名前を補ったり訳したりしない。
- `art.image` の画像は、取り込み口の `art/<image>.(jpg|jpeg|png|webp)` に置く。アプリは長辺 2048 以下に縮めて内部へ写し、縦横比（`art.aspect`）を書き込む。`zoom`（1〜3）と `dx`・`dy`（コマの外接長方形の幅・高さに対するずらし）は、絵の端がコマに見えない範囲に収めて使う。`dim`（0〜1、省くと 0）は絵の上に重ねる黒の不透明度。
- 画像だけを差し替えるときも、`layout.json` を置き直す（取り込みは `layout.json` が置かれたときに始まる）。

## version 1（1 枚の絵と多角形）

一晩目の形式も読み込める。

```json
{ "version": 1,
  "image": "page.webp",
  "aspect": 0.4499,
  "panels": [
    { "id": "fav", "kind": "folder", "label": "よく使う",
      "polygon": [[0.02,0.02],[0.98,0.02],[0.98,0.20],[0.02,0.24]],
      "apps": ["com.android.chrome"] },
    { "id": "other", "kind": "drawer",
      "polygon": [[0.02,0.255],[0.58,0.232],[0.50,0.46],[0.02,0.46]] } ] }
```

- 画像（`image`）は `layout.json` と同じ場所に置く。座標は画像に対する 0〜1 の相対値。`aspect` は画像の幅 / 高さ（1220×2712 なら 0.4499）。画面と縦横比が違えば、画像を画面いっぱいに広げて余りを切る（cover）。
- コマが重なる場所では、後に書いたコマが優先される（描画も上になる）。

## どちらの形式でも

- `apps` は初期値で、最初に読み込んだときに 1 度だけフォルダへ入れる。その後の入れ替えは画面の上で行う（アプリドロワーから長押しで運ぶ、フォルダの中で並べ替える、上端の「フォルダから削除」へ運ぶ）。
- 壊れた `layout.json` を置いても、前のレイアウトのまま、上端に誤りを表示する。フォルダとウィジェットの割り当ては消さない。
- id の変わったコマのフォルダの中身は「割り当てのないアプリ」として残す。見本のコマ（`sample-` で始まる id）の中身は、利用者が選んだものではないので残さない。
- 版権のある画像はリポジトリに入れない（`art/` と `*.private.webp` は `.gitignore` にある）。
