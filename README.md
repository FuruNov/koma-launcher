# koma-launcher

漫画のコマ割りの形でホーム画面を区切る Android のホームアプリ。コマは線で割ることを繰り返して作り、コマごとに別の画像を置く。コマを押すとアプリの一覧（フォルダ）が開き、ウィジェットを置くコマも作れる。

目標の端末は POCO X7 Pro（1220×2712、HyperOS、Android 15 以降）。確認には Waydroid（Android 13、1080×1920）を使う。

## ビルドと確認

```sh
nix develop -c ./gradlew assembleDebug          # debug の APK
nix develop -c ./gradlew assembleRelease        # R8 で縮小した APK
nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest
nix develop -c ./gradlew spotlessApply lint
scripts/e2e-waydroid.sh                         # Waydroid で一連の操作（21 項目。確かめる間だけ既定のホームにし、アプリのファイルは退避して終わったら戻す）
scripts/measure.sh [ラベル]                      # 起動時間・メモリ・フレーム → docs/measurements/
scripts/record-animations.sh <出力先>             # アニメーションの各案を録画（debug ビルドと su が要る）
```

- 署名の鍵は `~/.config/koma-launcher/release.keystore`（リポジトリの外）。`local.properties` の `koma.keystore` と `koma.keystorePasswordFile` で場所を渡す。debug も同じ鍵で署名する。実機に入れた後で鍵が変わると、更新にアンインストールが要り、フォルダとウィジェットの割り当てが消えるため。**鍵とパスワードのファイルはバックアップしておくこと。**
- AGP が Maven から取る aapt2 は FHS 前提のバイナリなので、devShell が `android.aapt2FromMavenOverride` で SDK の aapt2 を使わせている。
- minSdk は 33。実機は Android 15 以降だが、Waydroid が API 33 のため。

## コマ割りを編集する

ホームのメニュー（コマの間の余白を長押し）か設定画面の「コマ割りを編集」で変えられる。操作の欄は「選択中のコマ」と「全体」の 2 つのタブに分かれている。

- **選択中のコマ**: 縮小図のコマを押して選ぶと、このタブに切り替わる。「横に分割」「縦に分割」で割る、線の端の丸を縁に沿って動かす、「結合」で線を消す、種類（フォルダ・ウィジェット・全アプリ一覧）を切り替える。画像は Android の写真の選択画面から選び、縮小図の上のドラッグと 2 本の指で位置と大きさを合わせる。「暗さ」のスライダーで絵の上に黒を重ねると、透過させたウィジェットやコマの名前を明るい絵の上でも読める（名前と時計は暗くならない。画像を替えても暗さは残る）。大見出しと時計を、選んだコマへ移せる。フォルダのコマでは、名前、中のアプリ、押したときの動作（直接起動）、音楽プレイヤーも変えられる。
- **全体**: テンプレート（大ゴマ・1 コマ）、コマの「間隔」と「間隔の色」、枠、裁ち落とし、名前の書き方、大見出し、時計、視差、色調。

「元に戻す」は、編集画面を開いている間のコマ割りの操作だけを戻す。フォルダの中身と設定の変更は戻さない。閉じると、どのコマも使っていない画像を消す。

間隔の色は、以前は設定画面にもあった。レイアウトに色があるとそちらを使い、見本から書き出したレイアウトはいつも色を持つので、設定画面で変えても反映されなかった。今は編集画面の「全体」だけで変える。

## フォルダにアプリを入れる

- フォルダのコマを長押しして「アプリを追加」を選ぶか、編集画面でコマを選んで「アプリを追加」を押す。一覧のアプリを押すたびに、フォルダに入れる・外すが切り替わる。
- 全アプリ一覧のアイコンを長押しして、指を離さずにホームのコマまで運んでもよい。運ぶ間、一覧は透明になる。
- フォルダの中ではドラッグで並べ替え、上端の「フォルダから削除」まで運ぶと外れる。

## アプリを開く

- **検索**: 全アプリ一覧（上スワイプ）を開くとキーボードが現れ、名前の一部を打って Enter で先頭のアプリを起動する。照合は fzf と同じく、打った文字が順番どおりに含まれていれば候補に残し、名前の先頭・単語の頭・連続した一致を上に並べる（例: 「gm」で Gmail）。ひらがな・カタカナ・全角英数を区別せず、ローマ字でも探せる（「rain」で LINE（ライン））。パッケージ名の最後の部分（`settings` など）でも探せるので、漢字の名前のアプリは英語で探せる。アプリのショートカット（「新規作成」など）も候補に並ぶ。
- **よく使う**: 入力が空のとき、一覧の上に起動回数の多いアプリを 1 行並べる。起動回数はアプリの内部の `launches.json` にだけ記録し、端末の外へは送らない。
- **直接起動**: フォルダのコマを長押しして「直接起動」をオンにする（編集画面では「押したとき」を「先頭のアプリを起動」にする）と、押したときにフォルダを開かず、先頭のアプリを起動する。中身を並べ替えるときは、同じメニューの「フォルダを開く」から開く。ジェスチャーに割り当てた「フォルダを開く」は、切り替えに関係なくフォルダを開く。
- **音楽プレイヤー**: フォルダのコマを長押しして「音楽プレイヤー」をオンにする（編集画面では「プレイヤーにする」を押す）と、音楽の再生中と一時停止中に、そのコマにジャケットと、下の辺から立つ棒（ビジュアライザー）を表示する。タップで再生・一時停止、左へスワイプで次の曲、右へスワイプで前の曲。フォルダは長押しのメニューの「フォルダを開く」から開く。一時停止から 30 分たつと、いつものコマに戻る（設定画面で変えられる）。再生中のアプリを知るために「通知へのアクセス」の許可が要る（通知は読まない）。GitHub の Release の APK をブラウザで入れた端末では、先にアプリ情報の右上のメニューから「制限付き設定を許可」を選ぶ。棒は既定ではそれらしく動くだけで、設定の「音に合わせて動かす」をオンにすると、マイクの権限を使って実際の音に合わせる（録音はしない。端末によっては取得できず、その場合は 5 秒で元の動きに戻る）。

## コマの絵を入れる

レイアウトはアプリの内部（`files/layout.json` と `files/art/`）に持つ。外から入れるときは、`/sdcard/Android/data/dev.furunov.komalauncher/files/` を取り込み口に使う。ここに `layout.json` を置くと、アプリは中身を確かめ、参照する画像を内部へ写してから、ホームをその形に変える。取り込んだ `layout.json` は `layout.json.imported` に名前が変わり、写し終えた画像は消える。設定画面の「フォルダから復元」も、同じ取り込み口を通る。

モックアップ（`docs/mockups/`）で作ったコマ割りは、`scripts/import-mockup.sh <state.json> <画像のディレクトリ>` で送れる。`state.json` は Artifact の db の `editor/current`。画像はページに埋め込まれているので、`scripts/extract-mockup-images.sh <ページの HTML>` で書き出す。Claude に頼むときの手順と、新しいモックアップが守る保存の形は `.claude/skills/export-mockup/SKILL.md` にある。

### バックアップと復元

設定画面の「自動バックアップ」でフォルダを選ぶと、ホームが裏に回ったときに `koma-launcher-backup.json`（フォルダの中身・設定・`layout.json`・ウィジェットの提供元・起動回数）をそのフォルダに上書きする。書くのは内容が変わったときだけで、起動回数だけの変化は 1 日に 1 回にまとめる。上書きの前の内容は、日付ごとに `koma-launcher-backup-<日付>.json` として 7 世代残す。画像は容量が大きいので含めない。選んだフォルダに既存のバックアップがあれば、すぐには上書きせず、復元するか上書きするかを尋ねる。

設定画面の「フォルダから復元」でフォルダを選ぶと、中にあるものをすべて取り込む。adb を使わずに画像を入れるときも、これを使う。pCloud などのクラウドのアプリが入っていれば、そのフォルダも選べる。

- `koma-launcher-backup.json`: フォルダの中身・設定・起動回数・コマ割りを戻す
- `layout.json`: コマ割り。バックアップのコマ割りより優先する。取り込んだ後、フォルダの中で `layout.json.imported` に名前を変え、次の復元では使わない
- `<名前>.(webp|jpg|jpeg|png)`: 画像（`art/` の下でもよい）。`mask/<名前>.(png|webp)` は白が人物の切り抜き（BiRefNet の出力）、`inv/<名前>.(png|webp)` は白が人物以外の切り抜き。最終的なコマ割りが参照する分だけを取り込む

画像のフォルダを自動バックアップの保存先と同じにしておけば、機種変更のときは次の順に戻せる。

1. APK を入れ、既定のホームアプリにする
2. 「自動バックアップ」で前の端末と同じフォルダを選び、「このバックアップから復元」を選ぶ（「フォルダから復元」と同じ処理で、設定も画像も戻る）
3. ウィジェットを置いたコマを長押しして置き直し、音楽プレイヤーのコマを使うなら通知へのアクセスを許可し直す

編集画面で端末のギャラリーから選んだ画像（`img-` で始まる名前）は、画像のフォルダにもバックアップにも無いので戻らない。

Waydroid（Android 13）では、選んだフォルダが端末内の共有ストレージにあると、ファイルを読み取り専用で開く処理が MediaProvider の中で NullPointerException になる。読み取りに失敗したときだけ読み書きのモードで開き直す（`platform/files/Documents.kt`）。

### version 2（線の木とコマごとの画像）

```json
{ "version": 2,
  "frame": { "refWidth": 1220, "margin": 24, "gutter": 9, "gutterColor": "#ffffff", "bleed": false,
             "border": { "style": "single", "width": 24, "color": "#111111" } },
  "tree": { "cut": { "ta": 0.3246, "tb": 0.7999 },
            "a": { "panel": { "id": "fav", "kind": "folder", "label": "よく使う", "apps": ["com.android.chrome"],
                              "art": { "image": "pic-a-5", "zoom": 1, "dx": -0.067, "dy": 0 } } },
            "b": { "panel": { "id": "all", "kind": "drawer", "label": "その他" } } } }
```

- コマ割りは、コマを線で 2 つに割ることを繰り返した木で書く。葉は `panel`、枝は `cut`・`a`・`b`。`ta`・`tb` は線の両端の位置で、親のコマの周に対する割合（左上の頂点から時計回り）。`a` は線の進む向きの左側のコマ。画面の縦横比が変わっても、線の端は縁の同じ割合の位置に来る。
- 長さ（`margin`・`gutter`・`border.width`）は、画面の幅が `refWidth` のときの画素数で書く。`gutterColor` を省くと設定の「間隔の色」を使う。枠線（`border`）はコマの内側に引く。
- `look` はコマの見た目。`tone`（`none`・`soft` の淡い色合い）、`strength`（かかり具合）、`inner`（内側の影）、`sheen`（光の反射）。どれも 0〜1 で、省くと何もしない。
- `text` は文字。`label`（`vertical`・`horizontal`・`none`）はコマの名前の向き、`lang`（`en`・`ja`）は名前の言語、`masthead` は大見出しの文字（空なら出さない）、`mastheadY` はその高さ（コマに対する割合）、`mastheadPanel` は置くコマ（省くと時計のコマ）。
- `clock` はランチャーが描く時計。`enabled`、`panel`（省くといちばん大きいコマ）、`x`・`y`（コマに対する中心の位置）、`size`。
- `fx` は視差。`parallax` を true にすると、端末を傾けたとき絵（人物の切り抜きと一緒に）を傾けた向きへ、文字と時計を逆へ動かす（基準の幅で絵 26・時計 18・文字 7 画素 × `strength`）。光の反射も逆へ流れる。センサーはホームが見えている間だけ読む。センサーの無い Waydroid では、debug ビルドに `adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_TILT --ef x 1 --ef y 0` で傾きを送って確かめる。
- 人物の切り抜きがあると、名前・大見出し・時計を人物の後ろに描く。取り込み口の `art/<image>.inv.(png|webp)`（白が人物以外）か `art/<image>.mask.(png|webp)`（白が人物。アプリが濃さ 0.35〜0.65 を 0〜1 に詰めて反転する）に置く。切り抜きは PC で作る（BiRefNet）。端末の中で作る ML Kit の切り抜きは、イラストでは輪郭が粗かったので外した。
- `kind` は `folder`（フォルダ）・`widget`（ウィジェット）・`drawer`（押すと全アプリ一覧を開く）。
- `art.image` の画像は、取り込み口の `art/<image>.(jpg|jpeg|png|webp)` に置く。アプリは長辺 2048 以下に縮めて内部へ写し、縦横比（`art.aspect`）を書き込む。`zoom`（1〜3）と `dx`・`dy`（コマの外接長方形の幅・高さに対するずらし）は、絵の端がコマに見えない範囲に収めて使う。`dim`（0〜1、省くと 0）は絵の上に重ねる黒の不透明度。
- 画像だけを差し替えるときも、`layout.json` を置き直す（取り込みは `layout.json` が置かれたときに始まる）。

### version 1（1 枚の絵と多角形）

一晩目の形式も読み込める。

```json
{ "version": 1,
  "image": "page.webp",
  "aspect": 0.4499,
  "panels": [
    { "id": "fav", "kind": "folder", "label": "よく使う",
      "polygon": [[0.02,0.02],[0.98,0.02],[0.98,0.20],[0.02,0.24]],
      "apps": ["com.android.chrome"] },
    { "id": "clock", "kind": "widget",
      "polygon": [[0.02,0.255],[0.58,0.232],[0.50,0.46],[0.02,0.46]] } ] }
```

- 画像（`image`）は `layout.json` と同じ場所に置く。座標は画像に対する 0〜1 の相対値。`aspect` は画像の幅 / 高さ（1220×2712 なら 0.4499）。画面と縦横比が違えば、画像を画面いっぱいに広げて余りを切る（cover）。
- コマが重なる場所では、後に書いたコマが優先される（描画も上になる）。

### どちらの形式でも

- `apps` は初期値で、最初に読み込んだときに 1 度だけフォルダへ入れる。その後の入れ替えは画面の上で行う（全アプリ一覧から長押しで運ぶ、フォルダの中で並べ替える、上端の「フォルダから削除」へ運ぶ）。
- 壊れた `layout.json` を置いても、前のレイアウトのまま、上端に誤りを表示する。フォルダとウィジェットの割り当ては消さない。
- id の変わったコマのフォルダの中身は「割り当てのないアプリ」として残す。見本のコマ（`sample-` で始まる id）の中身は、利用者が選んだものではないので残さない。
- 版権のある画像はリポジトリに入れない（`art/` と `*.private.webp` は `.gitignore` にある）。

## 構成

依存は上から下への一方向だけ。

| モジュール | 中身 | 依存してよいもの |
|---|---|---|
| `:core:model` | 純粋な Kotlin。多角形の判定・内接長方形・cover の変換、線の木と絵の置き場所、layout.json、フォルダの編集（純粋な関数）、設定とジェスチャー、境界のインターフェース | なし |
| `:core:ui` | 機能をまたぐ Compose のコンポーネント。ドラッグ＆ドロップの状態、アイコンの表示とそのインターフェース | `:core:model` |
| `:core:platform` | Android の API を包むアダプタ（下の表の実装） | `:core:model`, `:core:ui` |
| `:feature:home` | ホーム画面、ジェスチャー、フォルダ、アニメーション、ViewModel | `:core:ui` |
| `:feature:drawer` | 全アプリ一覧 | `:core:ui` |
| `:feature:widgets` | ウィジェットの確保・bind・表示・選択画面 | `:core:ui` |
| `:feature:settings` | 設定画面 | `:core:ui` |
| `:feature:editor` | コマ割りの編集画面（線で割る、画像の選択と位置合わせ、フチ） | `:core:ui` |
| `:app` | `AppContainer` で実装を組み立てる。Activity と Manifest | すべて |

機能のモジュールどうしは互いを知らない。ホームに全アプリ一覧やウィジェットを差し込むのは `:app` で、`HomeSlots` を通して渡す。

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

- **コマの種類**: `PanelContent` に 1 つ加え、`LayoutParser` の `Kind` と、ホームでの扱い（`HomeViewModel.onEvent` の `TapPanel`、`HomeScreen` の描画）を加える。
- **アニメーション**: `PanelAnimation` を実装したオブジェクトを `feature/home/.../anim/PanelAnimation.kt` に 1 つ書いて `PanelAnimations` に登録し、`AnimationCatalog`（`:core:model`）に名前を 1 行加える。設定画面の選択肢に並ぶ。
- **ジェスチャーに割り当てる操作**: `LauncherAction` に 1 つ加え、ホームの中で完結するなら `HomeViewModel.perform`、外へ出るなら `DefaultActionPerformer` に 1 行加える。
- **設定の項目**: `feature/settings/.../SettingsTable.kt` の `settingsSections()` に 1 行加える。画面のコードは触らない。

### 依存を絞った理由

- **Hilt を使わない**: 公式の `testing-setup` スキルは DI が無ければ Hilt を入れるよう勧めるが、この規模では、Hilt の KSP の設定と生成コードの保守のほうが手間が大きい。実装の組み立ては `AppContainer` の 30 行に収まっている。
- **Room・DataStore を使わない**: 保存する値は数 KB で、全体をメモリに持って丸ごと書き直せば足りる。
- **Coil/Glide・Navigation・Material3 を使わない**: アイコンの読み込みは自前の 60 行で済み、画面の切り替えは「重ねるものを 1 つだけ持つ」状態（`Overlay`）で足りる。APK とメモリを小さくするため。
- **ML Kit を使わない**: 段階 4 では端末の中で人物を切り抜くために ML Kit の被写体の切り抜きを入れたが、イラストでは輪郭が粗かった。切り抜きは PC の BiRefNet で作り、「フォルダから復元」で取り込む。
- **書体を 2 つ同梱する**: Dela Gothic One（時計と日本語の名前、約 2.5 MB）と Bodoni Moda の斜体（大見出しと英語の名前）。どちらも SIL Open Font License で、全文を `licenses/` に置いた。

## 動作の軽さ

`scripts/measure.sh` の結果は `docs/measurements/` にある。Waydroid の release ビルドでの最初の計測（2026-11-19）:

| 項目 | 値 |
|---|---|
| APK | 2.2 MB |
| コールドスタート | 約 240 ms |
| 待機中の PSS（時計のウィジェット 1 つ） | 65 MB |
| フォルダの開閉 20 回 | ジャンク 0 / 62 フレーム、90 パーセンタイル 8 ms |

工夫は次のとおり。背景の絵は画面の大きさに縮小して `ALLOCATOR_HARDWARE` で読み、GPU 側に置く。アニメーションの値は `graphicsLayer` の中で読み、画面の作り直しを起こさない。アイコンは見えている分だけ読む。ホームが裏に回ったら（`onTrimMemory`）アイコンのキャッシュを空にする。

## 公式の Android スキル

`.claude/skills/` に github.com/android/skills（Apache-2.0）の 4 つを写してある（`edge-to-edge`、`testing-setup`、`android-intent-security`、`jetpack-compose-performance`）。出所は `.claude/skills/README.md`。

## まだ実機で確かめていないこと

- ロック解除で `USER_PRESENT` が届き、アニメーションが再生されるか（顔認証・指紋認証でロック画面を飛ばした場合も含む） allow-slop: 生体認証の術語としての「指紋認証」
- リフレクションで通知センターを開けるか。開けなければ AccessibilityService の実装に切り替える
- HyperOS の設定でホームアプリを切り替えられるか、ジェスチャーナビゲーションで戻る・ホーム・履歴が動くか
- 導入は `adb install`。HyperOS は「USB 経由のインストール」の許可が別に要る
