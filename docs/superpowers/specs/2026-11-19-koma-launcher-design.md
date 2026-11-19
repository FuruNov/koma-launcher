# コマ割りランチャー（koma-launcher）一晩目の計画

## Context

利用者は、好きなキャラクターの画像を漫画のコマ割りで並べたホーム画面を作りたい。コマは大きさが不ぞろいで、斜めに切った辺もある。各コマ全体がタップに反応し、タップするとアプリの一覧（フォルダ）が開くようにしたい。

これまでの試作（Waydroid）で分かったこと:

- Nova 8.9.9 の `OPEN_FOLDER` は内部専用で、外部から呼ぶと例外になる（逆コンパイルで確認）。DB でフォルダの span を広げても 1 マスのまま。
- KWGT のエディタは Waydroid 上で `libpairipcore.so`（Play の改ざん防止）により停止する。

そこで、自作のランチャーアプリを作る。利用者の要望は次のとおり。

- ウィジェットを配置できる
- ロックを解除したときに、コマが「にゅっ」と出てくるアニメーション
- タップしたコマのアニメーション
- 下スワイプで通知センターが開く
- 最終的に POCO X7 Pro（1220×2712、HyperOS、Android 15 以降）の実機で動く
- Android 開発のスキルを使う
- 保守性・疎結合・拡張性に優れたコード
- アプリの入れ替えはまれだが、入れ替えるときは Nova のように快適にしたい（JSON の書き換えでは不便）
- 最終的にはリッチな機能を持ちつつ、動作が軽く、メモリの使用量が少ないこと
- Nova のように、ジェスチャーと操作の対応を GUI で設定する画面。ほかの項目の設定画面も欲しい

朝までの成果物は、Waydroid で動く最初の版、確かめた項目の報告、配置とアニメーションのモックアップ。

## 技術の選択

- **Kotlin + Jetpack Compose + Gradle（AGP）**。Google 公式の Android スキル（github.com/android/skills, Apache-2.0）が Gradle と Compose を前提にしているため、それに合わせる。
- ビルド環境はリポジトリの `flake.nix` の devShell に置く（`androidenv.composeAndroidPackages` で platform 36・build-tools、JDK 21）。nixos-config は変更しないので、リビルドは不要。
  - AGP が Maven から取得する aapt2 は動的リンクのバイナリ。nix-ld が有効（`nixos-config/modules/nixos/core.nix:242`）なので動く見込み。動かなければ `gradle.properties` の `android.aapt2FromMavenOverride` に SDK の aapt2 を指定する。
- 公式スキルのうち、次の 4 つをリポジトリの `.claude/skills/` に LICENSE と一緒に置き、プロジェクトのスコープで使う。
  - `system/edge-to-edge`
  - `testing/testing-setup`
  - `security/android-intent-security`
  - `jetpack-compose/jetpack-compose-performance`
  - `devtools/android-cli` は curl でのインストールが前提なので使わず、adb を直接使う。
- DI は Hilt を使わず、`AppContainer` での手動のコンストラクタ注入にする。`testing-setup` のスキルは「DI が無ければ Hilt を入れる」と書くが、この規模では Hilt の kapt/KSP の設定と生成コードが保守の負担のほうが大きいため。理由は README に書く。
- 整形と静的検査は Android lint と ktlint（Spotless 経由）。
- minSdk 34、targetSdk 36、縦向き固定。パッケージ名は `dev.furunov.komalauncher`。
- **署名の鍵**: 最初から専用の鍵（`~/.config/koma-launcher/release.keystore`、リポジトリの外）で debug と release の両方に署名する。実機に入れた後で鍵が変わると、更新にアンインストールが要り、フォルダとウィジェットの割り当てが消えるため。鍵の場所とパスワードは `local.properties`（git に入れない）で渡す。
- **画像を git に入れない**: 版権のある画像は `.gitignore` で除外し、リポジトリには仮の絵だけを入れる。後で GitHub に上げることになっても、版権のある画像が履歴に残らないようにするため。

## 構成（疎結合・拡張性のための設計）

リポジトリは `~/ghq/github.com/FuruNov/koma-launcher`。git は手元だけで管理し、main で作業する。

### Gradle モジュール

依存は上から下への一方向だけにする。

| モジュール | 種類 | 中身 | 依存してよいもの |
|---|---|---|---|
| `:core:model` | 純粋な Kotlin（JVM） | 多角形の判定、cover の座標変換、レイアウトとフォルダの中身のモデル、フォルダの編集操作（追加・並べ替え・移動・削除を純粋な関数で）、JSON の読み書き（kotlinx.serialization）。Android の API を一切使わない | なし |
| `:core:ui` | Android ライブラリ（Compose） | 機能をまたぐドラッグ＆ドロップの状態（`DragDropState`）、アイコンの Composable、共通の見た目 | `:core:model` |
| `:core:platform` | Android ライブラリ | Android の API を包むアダプタ。下の「境界のインターフェース」の実装 | `:core:model` |
| `:feature:home` | Android ライブラリ（Compose） | ホーム画面、フォルダのポップアップ、アニメーション、ViewModel | `:core:model`、インターフェースだけ |
| `:feature:drawer` | Android ライブラリ（Compose） | 全アプリ一覧 | `:core:model`、インターフェースだけ |
| `:feature:widgets` | Android ライブラリ（Compose） | ウィジェットの選択画面とコマへの表示 | `:core:model`、インターフェースだけ |
| `:feature:settings` | Android ライブラリ（Compose） | 設定画面（ジェスチャー、見た目、アニメーション、一覧、バックアップ） | `:core:model`、`:core:ui`、インターフェースだけ |
| `:app` | アプリ | `AppContainer` で実装を組み立てる、Activity、Manifest | すべて |

機能のモジュールは実装クラスを知らない。インターフェースは `:core:model` に置く（`suspend` と `Flow` だけを使い、Android の型を出さない）。アイコンのように Android の型が要るものは、`:core:platform` の Compose 用の小さな拡張に閉じ込める。

### 境界のインターフェース

| インターフェース | 役割 | 実装（`:core:platform`） | テスト用 |
|---|---|---|---|
| `LayoutSource` | コマの形と画像（`Flow<Layout>`） | 外部ファイル → 無ければ assets の見本 | 固定値の fake |
| `FolderStore` | フォルダの中身（`StateFlow<Map<PanelId, List<AppKey>>>` と編集操作） | アプリ内部の JSON ファイル（`AtomicFile` で書き込みが途中で切れても壊れないようにし、連続した編集は 300 ms まとめて書く） | メモリ上の fake |
| `SettingsStore` | 設定（`StateFlow<Settings>` と更新） | アプリ内部の `settings.json`（`FolderStore` と同じ保存の仕組みを共有する） | メモリ上の fake |
| `ActionPerformer` | 操作（`LauncherAction`）を実行する | 操作の種類ごとの実装を表に登録したもの | 実行した操作を記録する fake |
| `IconLoader` | アイコンを必要な大きさで読み、少数を保持する | `LauncherApps` + `LruCache` | 色の四角を返す fake |
| `AppCatalog` | アプリの一覧、ラベル、起動 | `LauncherApps` | fake |
| `WidgetBinder` | ウィジェットの確保・bind・削除・保存 | `AppWidgetHost` と SharedPreferences | fake |
| `ScreenEvents` | `Flow<ScreenEvent>`（画面が消えた、ロックが解除された） | Application で動的に登録した BroadcastReceiver | 手で流す fake |
| `SystemShade` | 通知センターを開く | 下記の「通知センター」 | 呼ばれた回数を数える fake |

### 拡張点

- **コマの種類**: JSON の `"kind"` を判別子にした `sealed interface PanelContent`（`Folder`、`Widget`、後から `SingleApp` などを追加する）。UI 側は `PanelKindHandler`（描画、タップ、長押しを持つ）を種類ごとに 1 つ書き、`Map<KClass, PanelKindHandler>` に登録する。新しい種類の追加は、モデルのクラス 1 つと handler 1 つで済み、既存のコードには手を入れない。
- **アニメーション**: `interface PanelAnimation { suspend fun enter(...); suspend fun press(...) }` を、名前から引く表に登録する。設定画面で選び、`settings.json` に保存する。案ごとに 1 クラスで、案の追加・差し替えはそのファイルだけで済む。
- **ジェスチャーと操作**: ジェスチャーの種類（`GestureKind`）と操作（`sealed interface LauncherAction`）を分け、その対応を `Settings` に持つ。ホーム画面はジェスチャーを見分けて `GestureKind` を出すだけで、何を実行するかは知らない。新しい操作は、`LauncherAction` のクラス 1 つと実行の実装 1 つで追加でき、設定画面の選択肢にも自動で並ぶ。
- **状態**: 画面ごとに ViewModel が `StateFlow<UiState>` を持ち、UI からの入力は `sealed interface UiEvent` で受ける。Composable は状態を表示するだけにする。これで ViewModel を fake とコルーチンのテストで確かめられる。

### データの分け方

変える頻度と変え方が違うものを、別々に持つ。

| データ | 変える頻度 | 変え方 | 置き場所 |
|---|---|---|---|
| コマの形と画像（layout.json） | 絵を替えるときだけ | adb push（将来は画面内の編集機能を追加できる） | `/sdcard/Android/data/dev.furunov.komalauncher/files/` |
| フォルダの中身 | まれだが、手で快適に変えたい | 画面内のドラッグ＆ドロップ | アプリ内部の `folders.json` |
| ウィジェットの割り当て | 置くときだけ | 長押しの選択画面 | SharedPreferences |
| 設定（ジェスチャー、見た目など） | ときどき | 設定画面 | アプリ内部の `settings.json` |

フォルダの中身は画面上の操作で書き換えるので、adb push する layout.json から外す。layout.json の `"apps"` は初期値として 1 度だけ読み込む。書き出し・読み込みの機能（設定の画面から folders.json を共有・取り込み）はバックアップのために用意する。

### layout.json

ファイルの変更を監視して、再起動せずに読み直す。

```json
{ "version": 1,
  "image": "page.png",
  "panels": [
    { "id": "p1", "kind": "folder", "label": "仕事",
      "polygon": [[0,0],[0.62,0],[0.55,0.3],[0,0.3]],
      "apps": ["com.android.chrome"] },
    { "id": "p2", "kind": "widget", "polygon": [[0.62,0],[1,0],[1,0.3],[0.55,0.3]] } ] }
```

- 座標は画像に対する 0〜1 の相対値。画面と縦横比が違えば、画像を cover で広げ、同じ変換で座標を写す。Waydroid（1080×1920）と実機（1220×2712）で同じ JSON を使える。
- `"version"` を付け、将来形式を変えたときに古いファイルを読み替えられるようにする。
- 読み込みの失敗（JSON の誤り、存在しないパッケージ名）は画面を止めず、見本のレイアウトと、誤りの内容を示すラベルを表示する。

## 機能ごとの設計

- **タップ**: `pointerInput` で座標を取り、`Polygon.contains` で該当のコマを選ぶ。コマが重なる場合は JSON で後に書いたコマを優先する。コマの間の余白では何もしない。
- **タップしたコマのアニメーション**: 押した瞬間にコマを少し沈め（縮小と影）、離したら弾んで戻す。その動きに続けて、フォルダをコマの位置から広がる形で開く。`PanelAnimation.press` で実装する。
- **フォルダ**: コマの重心の近くに、アイコンとラベルを格子状に並べたパネルを表示する。外側のタップ、戻るボタン（`BackHandler`）、ホームボタン（`onNewIntent` で HOME を受けたら状態を戻す）で閉じる。アンインストール済みのアプリは表示しない。
- **全アプリ一覧**: 下端付近からの上スワイプで開く。検索欄付きの縦の格子にする。
- **フォルダの中身の編集（Nova に近い操作）**: 画面全体に 1 つの `DragDropState` を置き、一覧・フォルダ・ホームの間でドラッグを受け渡す。
  - 全アプリ一覧でアイコンを長押しすると、一覧が閉じてホームが見える。指の下にあるコマが光り、離すとそのフォルダに加わる。
  - フォルダの中でアイコンを長押しして動かすと、並べ替えられる。
  - フォルダの外まで引き出すと、フォルダが閉じる。そのまま別のコマに離すと移動し、画面上端の「外す」に離すとフォルダから外れる。
  - 外した直後は画面下に「元に戻す」を数秒表示する。
  - アイコンを長押ししてすぐ離すと、メニューを表示する。項目はアプリのショートカット（`LauncherApps.getShortcuts`、ホームアプリだけが使える）、アプリ情報、アンインストール、フォルダから外す。
  - 長押しの判定は「長押しして動かさなければメニュー、動かせばドラッグ」とし、Nova と同じ操作感にする。
  - コマのラベル（フォルダ名）は、コマの長押しメニューから変更する。
  - 編集操作そのもの（追加・並べ替え・移動・削除・元に戻す）は `:core:model` の純粋な関数にし、JVM のテストで確かめる。UI はその関数を呼ぶだけにする。
- **通知センター**: 画面上の下スワイプで開く。`SystemShade` の実装は `StatusBarManager.expandNotificationsPanel` をリフレクションで呼ぶ（`EXPAND_STATUS_BAR` は通常の権限で、Nova なども同じ方法を使う）。Android の非公開 API の制限で呼べない場合に備え、`AccessibilityService` の `GLOBAL_ACTION_NOTIFICATIONS` を使う実装を 2 つ目として用意する。このサービスは利用者が設定で有効にしたときだけ使う。どちらを使うかは `SystemShade` の実装の差し替えで済む。
- **コマの描き方**: 背景の絵は 1 枚の Bitmap で持ち、コマごとに「その多角形で切り抜いた層」（`graphicsLayer` + `clipPath`）として描く。コマを 1 枚ずつ動かす（タップ、ロック解除）には、コマが独立した層である必要があるため。コマの間の余白は単色で塗る。
- **ダブルタップとタップの両立**: ダブルタップを見分けるには最初のタップの後に約 300 ms 待つ必要があり、コマのタップが遅れて感じられる。そこで、ダブルタップはコマの間の余白でだけ見分け、コマの上のタップは待たずにすぐ反応させる。ダブルタップに操作が割り当てられていなければ、余白でも待たない。
- **ジェスチャーの見分け**: ホーム画面は、上スワイプ・下スワイプ・2 本指の上下スワイプ・ピンチイン・ダブルタップ・コマの間の余白の長押しを見分け、`GestureKind` として出す。移動量がしきい値未満ならタップとして扱う。フォルダや一覧が開いている間は無視する。既定の対応は、上スワイプ → 全アプリ一覧、下スワイプ → 通知センター、余白の長押し → ホームのメニュー（設定・ウィジェット）。
- **設定画面**（`:feature:settings`。ホームのメニュー、全アプリ一覧の右上、アプリのアイコンから開く）:
  - **ジェスチャー**: ジェスチャーごとに操作を選ぶ。選べる操作は、なし、全アプリ一覧、通知センター、クイック設定、指定のコマのフォルダを開く、アプリを起動、アプリのショートカット、画面ロック（AccessibilityService が要る旨を表示）、設定画面。アプリとショートカットは選択画面から選ぶ。
  - **見た目**: フォルダの列数、アイコンの大きさ、ラベルの表示、フォルダの背景の透明度、コマが光る色。
  - **アニメーション**: ロック解除とタップの動きの種類、速さ（0.5〜2 倍）、オフ。選ぶとその場で見本を再生する。
  - **全アプリ一覧**: 列数、並び順、非表示にするアプリ。
  - **コマの絵**: 端末の画像と layout.json を選んで取り込む（Storage Access Framework を使い、adb を使わずに替えられるようにする）。
  - **バックアップ**: フォルダの中身・設定・layout.json をまとめて 1 つのファイルに書き出し、読み込む。ウィジェットは appWidgetId でなくウィジェットの提供元（コンポーネント名）を記録し、読み込み時に配置し直す（許可の画面が出る）。
  - **ホームアプリ**: 既定のホームに設定する画面を開く。
  - 設定の各項目は `Settings` のデータと、項目を並べる宣言の表から画面を作る。項目の追加は表に 1 行を加えるだけで済むようにする。
  - 今夜の範囲は、ジェスチャー・アニメーション・見た目・バックアップまでとする。届かなかった項目は朝に報告する。
- **ウィジェット**: ウィジェットのコマを長押しすると、自前の選択画面（`AppWidgetManager.installedProviders` の一覧）を表示する。`bindAppWidgetIdIfAllowed` で bind し、拒否されたら `ACTION_APPWIDGET_BIND` で許可を求める。configure の Activity があれば起動する（Activity Result API）。
  - 表示はコマの多角形に内接する最大の軸平行の長方形に置き、多角形で切り抜かない。切り抜くとウィジェットのボタンや文字が欠けるため。内接の長方形の計算は `:core:model` に置き、テストする。
  - `AppWidgetHost` は Activity の `onStart` で `startListening`、`onStop` で `stopListening` する。
  - コマの絵を替えて、あるコマが無くなった場合の扱い: 同じ id のコマは中身（フォルダ、ウィジェット）を引き継ぐ。無くなったコマのフォルダの中身は「割り当てのないアプリ」として残し、設定画面から別のコマへ移せるようにする。無くなったコマのウィジェットは、取り込みの確認の画面で「外す」と答えたときだけ `deleteAppWidgetId` で解放する。
  - layout.json が壊れていて見本のレイアウトに戻った場合は、フォルダとウィジェットの整理を一切しない。整理すると、書き損じ 1 つで全部の割り当てが消えるため。
  - appWidgetId は SharedPreferences に `panel id → appWidgetId` で保存する（端末ごとに値が違うため、JSON には書かない）。
- **ロック解除のアニメーション**: `ScreenEvents` を Application で動的に登録し、`SCREEN_OFF` と `USER_PRESENT` を受ける。Activity で登録しないのは、画面が消えると Activity が `onStop` になり、登録を外すと解除の通知を受け取れないため。
  - 画面が消えたら「次に表示したときに再生する」印を立てる。ホームが表示された時点で印があれば再生する。解除の直後に別のアプリが前面に出た場合は、次にホームへ戻ったときに再生する。
  - 各コマを、時間をずらして画面の外側の辺の方向から入れる。`PanelAnimation.enter` で実装し、2〜3 通り作る。
  - Waydroid にはロック画面が無い可能性があるので、debug ビルドだけに再生用の broadcast（`dev.furunov.komalauncher.DEBUG_PLAY_UNLOCK`）を受ける口を作る。受け口は `android.permission.DUMP` を持つ送り手（adb の shell）に限り、他のアプリからは呼べないようにする。release ビルドには含めない（`src/debug/` の Manifest に置く）。
- **edge-to-edge**: 絵は画面全体（システムバーの下も含む）に描く。フォルダ・一覧・選択画面は insets を避けて配置する。手順は公式スキルに従う。
- Activity は不透明にし、システムの壁紙は使わない。

## 動作の軽さとメモリ

目標: 待機中（ウィジェット 2 つ、フォルダは閉じた状態）の PSS を 100 MB 以下にする。フォルダを開くときのジャンクフレームは 0 を目指す。数値は `dumpsys meminfo` と `dumpsys gfxinfo` で測り、README に記録する。Waydroid の数値は目安とし、実機で測り直す。

- **依存を絞る**: Hilt、Room、Coil/Glide、Navigation は使わない。画像とアイコンの読み込みは小さい自前の実装、保存は JSON ファイルにする。Compose は foundation を主に使い、Material3 は一覧の検索欄などに限る。
- **背景の絵**: `ImageDecoder` で画面の大きさちょうどに縮小し、`ALLOCATOR_HARDWARE` で読む。GPU 側のメモリに置かれ、Java ヒープを使わない（1220×2712 で約 13 MB）。端末に置く画像は WebP にする。
- **アイコン**: 表示する大きさ（48 dp）で 1 回だけ Bitmap にし、`LruCache` で保持する（上限は約 64 個、約 4 MB）。全アプリ一覧は `LazyVerticalGrid` で、画面に見えている分だけ読む。読み込みは `Dispatchers.IO` で行い、描画を止めない。
- **メモリが逼迫したとき**: `onTrimMemory` でアイコンのキャッシュと全アプリ一覧の状態を捨て、次に必要になったら読み直す。
- **アニメーション**: 位置・拡大率・影は `graphicsLayer { }` のラムダの中で読み、描画の段階だけで更新する。コマごとの値の変化で画面全体を作り直さない（recomposition を起こさない）。UI の状態は `@Immutable` のデータにし、Compose が不要な作り直しを省けるようにする。
- **多角形の判定**: 画面の大きさが決まったときに 1 回だけ画面座標へ変換し、外接長方形で先に絞り込んでから多角形の判定をする。
- **起動の速さ**: release ビルドは R8（コードの縮小と最適化）とリソースの縮小を有効にする。Baseline Profile（起動と主要な操作のコードを事前にコンパイルさせる仕組み）は、Macrobenchmark の設定が要るので、今夜は入れずに次の段階に回す。
- **計測**: `scripts/measure.sh` で `am start -W`（起動時間）、`dumpsys meminfo`、`dumpsys gfxinfo`（フォルダの開閉を 20 回）を取り、結果をファイルに残す。変更のたびに比べられるようにする。

## 一晩の作業順

優先度の順に並べる。時間が足りなければ後ろの項目から削り、その旨を報告する。

一晩で全部は終わらない見込みが高い。どの段階で止まっても使える状態を残すため、**1〜6 を「最初に動く版」の区切り**とし、ここまでを Waydroid で一通り確かめてコミットしてから 7 以降に進む。7 以降の各項目も、1 項目ずつ動く状態にしてからコミットする。途中まで作った項目を残したまま次へ進まない。

Waydroid について:
- 現在の Waydroid はマルチウィンドウのモード（`persist.waydroid.multi_windows=true`）で、ホームアプリも 1 つのウィンドウとして表示される。ホームボタンの扱いなどが実機と違う可能性があるので、違いを見つけたら報告に書く。設定は変えない（ほかのアプリの起動の仕組みが前提にしているため）。
- `wm size` の上書きは Android の設定に保存され、再起動後も残る。使ったら必ず `wm size reset` と `wm density reset` で戻し、ほかのアプリの表示に影響が残らないようにする。

1. リポジトリ、flake の devShell、Gradle のマルチモジュールの骨格、Spotless と lint、署名の鍵、`.gitignore` を作る。空の Activity の APK を Waydroid に入れて起動を確かめる
2. 公式スキル 4 つを `.claude/skills/` に配置する
3. `:core:model` をテスト先行で書く（多角形の判定、cover の座標変換、内接の長方形、JSON の読み込みと誤りの扱い）
4. `:core:platform` の各アダプタと、`:feature:home`（描画、タップ、フォルダ、ViewModel のテスト）
5. ジェスチャーの見分け、`LauncherAction` と `ActionPerformer`、`SettingsStore`（既定の対応で、全アプリ一覧と通知センターが開くところまで）
6. フォルダの中身の編集（`FolderStore`、編集操作の純粋な関数とテスト、`DragDropState`、一覧→コマ、フォルダ内の並べ替え、コマ間の移動、外す・元に戻す、長押しメニュー）
7. `:feature:widgets`（Waydroid の時計のウィジェットで確かめる）
8. `:feature:settings`（ジェスチャー → 見た目 → バックアップの順。アニメーションの項目は 10 の実装の後に加える。項目の宣言の表から画面を作る仕組みを先に作る）
9. `scripts/measure.sh` で起動時間・メモリ・フレームを測り、目標と比べる
10. アニメーション（タップ 1〜2 通り、ロック解除 2〜3 通り）と debug 用の再生の口。`adb shell screenrecord` で録画して確かめる
11. edge-to-edge の確認
12. `scripts/e2e-waydroid.sh` で一連の操作を流し、スクリーンショットを残す。可能なら `adb shell wm size 1220x2712` と `wm density` で実機の縦横比も再現する（終わったら `wm size reset`）
13. 配置案 3 つとアニメーション案のモックアップを Artifact で作る（実機の比率 1220:2712、アニメーションはページ上で再生できるようにする。各案に「この案にする」のボタンと、場面ごとのおすすめと理由を付ける）
14. README（構成、モジュールの依存の向き、コマの種類とアニメーションの追加方法、layout.json の書き方、Hilt を使わない理由）
15. Sonnet のサブエージェント 1 体に、疎結合と保守性の観点でコードレビューを頼み、指摘を直す
16. Waydroid のホームを Launcher3 に戻す（`cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher`）。Nova と KWGT はアンインストールせず、報告に書く

作業は主に自分で行い、区切りごとに main へコミットする。設計の文書は `docs/superpowers/specs/2026-11-19-koma-launcher-design.md`、実装計画は `docs/superpowers/plans/2026-11-19-koma-launcher-night1.md` に置く。

## 検証

- `nix develop -c ./gradlew spotlessCheck lint testDebugUnitTest` が通ること（`:core:model` の判定・変換・JSON、ViewModel の fake を使ったテスト）
- `nix develop -c ./gradlew assembleDebug` で APK ができること
- Waydroid で次を、スクリーンショット・録画・uiautomator の出力で確かめる。
  - ホームに設定できる
  - 各コマの内側の点（頂点の近く、斜めの辺の近くを含む）でフォルダが開き、コマの外では開かない
  - タップしたコマのアニメーションが再生され、続けてフォルダが開く
  - フォルダのアイコンからアプリが起動し、戻るボタンとホームボタンでフォルダが閉じる
  - 上スワイプで全アプリ一覧、下スワイプで通知センターが開く
  - 時計のウィジェットをコマに配置でき、アプリの再起動後も残る
  - debug の broadcast でロック解除のアニメーションが再生される
  - layout.json を push すると、再起動せずに表示が変わる。壊れた JSON を push しても止まらず、フォルダとウィジェットの割り当ても消えない
  - コマのタップが待たずに反応する（ダブルタップの判定でタップが遅れない）
  - debug の受け口に、adb 以外のアプリから broadcast を送っても反応しない
  - 一覧からコマへのドラッグでアプリが加わり、フォルダ内の並べ替え・コマ間の移動・外す・元に戻すができ、アプリを再起動しても残る
  - 設定画面でジェスチャーの操作を変えると、ホームの動きがすぐ変わる。設定はアプリを再起動しても残る。バックアップを書き出して読み込むと元に戻る
  - `scripts/measure.sh` の結果（PSS、起動時間、ジャンクの割合）
- 朝の報告では、確かめたことと、実機でしか分からないことを分けて書く

## 実機（POCO X7 Pro）で後から確かめること

- ロック解除で `USER_PRESENT` が届き、アニメーションが再生されるか（顔認証・指紋認証でロック画面を飛ばした場合も含む） allow-slop: 生体認証の術語としての「指紋認証」
- リフレクションで通知センターを開けるか（HyperOS の制限の有無）。開けなければ AccessibilityService の実装に切り替える
- HyperOS の設定でホームアプリを切り替えられるか、ジェスチャーナビゲーションで戻る・ホーム・履歴が動くか
- 1220×2712 での見え方
- 導入は `adb install`（USB デバッグ）で行う。HyperOS は「USB 経由のインストール」の許可が別に要る
