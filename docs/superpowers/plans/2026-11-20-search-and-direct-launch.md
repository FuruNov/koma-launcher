# 検索の改善・直接起動のコマ・コマの背景の暗さ 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 全アプリ一覧の検索を fzf 型の照合と起動回数で速くし、押すと先頭のアプリを起動するフォルダと、コマごとの背景の暗さを追加する。

**Architecture:** 照合・起動回数・フォルダの編集・レイアウトの読み書きは `:core:model` の純粋な関数とインターフェースに置き、単体テストで確かめる。保存は既存の `JsonFileStore`、起動回数の記録は `AppCatalog` を包むデコレータで行い、画面（`:feature:drawer`・`:feature:home`・`:feature:editor`）は結果を表示するだけにする。

**Tech Stack:** Kotlin、Jetpack Compose（Material を使わない Foundation のみ）、kotlinx.serialization、JUnit。

**Spec:** `docs/superpowers/specs/2026-11-20-search-and-direct-launch-design.md`

## Global Constraints

- 依存を増やさない（README の「依存を絞った理由」）。照合とローマ字の変換表は自前で書く。
- モジュールの依存は上から下への一方向（`:core:model` は何にも依存しない。機能のモジュールどうしは互いを知らない）。
- 保存ファイルの互換: 既存の `folders.json`・`layout.json` は追加の項目が無くても読める（既定値で補う）。
- 起動回数は端末の外へ送らない。ショートカットの起動は数えない。
- 日本語の文（コメント、UI の文字、コミット）は CLAUDE.md の日本語のルールに従う。
- サブエージェントのレビューは全タスクの後に 1 回だけ（タスクごとには起動しない）。
- ビルドとテストは `nix develop -c ./gradlew ...`。

## Review Focus

1. 起動の記録がまだ無いとき（初回）: 「よく使う」の行を表示しない（空の行を残さない）。→ Task 4 のテスト `frequentIsEmptyWithoutHistory`
2. アンインストール済み・隠したアプリの起動回数が残っているとき: 「よく使う」と検索結果に表示しない。→ Task 4 `frequentSkipsHiddenAndMissing`
3. 空白だけの入力: 空の入力と同じ表示にする。→ Task 1 `blankQueryMatchesNothing`、Task 4 で `trim()`
4. 直接起動のフォルダの先頭のアプリがアンインストール済み: 次のインストール済みのアプリを起動し、1 つも無ければフォルダを開く。→ Task 5 `directLaunchSkipsMissingApps`
5. ローマ字の途中の入力（「rai」「rain」「lin」）: 打っている途中でも候補に残る。→ Task 1 `romajiPrefixesMatch`

---

### Task 1: 照合の関数（`AppSearch`）

**Files:**
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/AppSearch.kt`
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/Kana.kt`（正規化とローマ字の変換）
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/apps/AppSearchTest.kt`

**Interfaces:**
- Produces:
  - `data class Match(val score: Int, val positions: List<Int>)` — `positions` は元の文字列の中の一致した文字の位置（太字にする）。
  - `object AppSearch { fun match(query: String, text: String): Match?; fun rank(query: String, apps: List<AppEntry>, launches: Map<AppKey, Int>): List<Ranked>; fun rankShortcuts(query: String, shortcuts: List<AppShortcut>): List<RankedShortcut> }`
  - `data class Ranked(val app: AppEntry, val match: Match)`、`data class RankedShortcut(val shortcut: AppShortcut, val match: Match)`
  - `object Kana { fun normalize(s: String): String; fun romajiToHiragana(s: String): String? }` — `normalize` は全角英数→半角、小文字化、カタカナ→ひらがな。文字数を変えない（位置をそのまま元の文字列に使うため）。`romajiToHiragana` は入力が ASCII の英字だけのときだけ変換し、それ以外は null。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
@Test fun subsequenceMatches() { assertNotNull(AppSearch.match("gm", "Gmail")); assertNull(AppSearch.match("mg", "Gmail")) }
@Test fun prefixBeatsMiddle() = assertTrue(AppSearch.match("ma", "Maps")!!.score > AppSearch.match("ma", "Gmail")!!.score)
@Test fun wordStartBeatsMiddle() = assertTrue(AppSearch.match("m", "Google Maps")!!.score > AppSearch.match("m", "Gmail")!!.score)
@Test fun katakanaAndHiraganaMatch() { assertNotNull(AppSearch.match("らいん", "ライン")); assertNotNull(AppSearch.match("ライン", "らいん")) }
@Test fun romajiMatchesKana() { assertNotNull(AppSearch.match("rain", "ライン")); assertNotNull(AppSearch.match("kamera", "カメラ")) }
@Test fun romajiPrefixesMatch() { listOf("r", "ra", "rai", "rain").forEach { assertNotNull(it, AppSearch.match(it, "ライン")) } }
@Test fun fullWidthMatches() = assertNotNull(AppSearch.match("ｇｍ", "Gmail"))
@Test fun blankQueryMatchesNothing() = assertNull(AppSearch.match("  ", "Gmail"))
@Test fun positionsPointAtOriginal() = assertEquals(listOf(0, 1), AppSearch.match("gm", "Gmail")!!.positions)
@Test fun gmPutsGmailFirst() {
    val apps = listOf(AppEntry(AppKey("com.google.android.apps.maps", "M"), "Google Maps"), AppEntry(AppKey("com.google.android.gm", "G"), "Gmail"))
    assertEquals("Gmail", AppSearch.rank("gm", apps, emptyMap()).first().app.label)
}
@Test fun launchesBreakTies() {
    val a = AppEntry(AppKey("a", "A"), "Alpha"); val b = AppEntry(AppKey("b", "B"), "Alpha")
    assertEquals(b, AppSearch.rank("al", listOf(a, b), mapOf(b.key to 3)).first().app)
}
@Test fun packageNameMatchesBelowLabel() {
    val settings = AppEntry(AppKey("com.android.settings", "S"), "設定")
    val r = AppSearch.rank("settings", listOf(settings), emptyMap())
    assertEquals(settings, r.single().app); assertTrue(r.single().match.positions.isEmpty())
}
```

- [ ] **Step 2: テストが失敗することを確かめる**

Run: `nix develop -c ./gradlew :core:model:test --tests '*AppSearchTest*'`
Expected: コンパイルエラー（`AppSearch` が無い）

- [ ] **Step 3: `Kana` と `AppSearch` を実装する**

照合は貪欲ではなく、各文字の一致位置を動的計画法で選んで点数を最大にする（fzf v2 と同じ考え方）。点数の値:

```
一致 1 文字: +16
文字列の先頭で一致: +24
単語の頭（空白・記号・数字の後、小文字の後の大文字）で一致: +16
直前の文字も一致（連続）: +12
一致の間の飛ばした文字 1 つ: -2（最初の一致より前の文字は数えない）
```

`match` は `Kana.normalize(text)` に対して、`Kana.normalize(query)` と `Kana.romajiToHiragana(normalize(query))` の両方で照合し、点数の高いほうを返す。ローマ字の変換は、末尾の子音だけの残り（「rain」の「n」、「r」）を落とさず、残りの英字も照合に使う（例: 「rain」→「らいn」は「らいん」に照合できないので、末尾の「n」は「ん」に、他の子音 1 つはその子音で始まる仮名のどれかに一致すると扱う）。`rank` はラベルで一致しなければパッケージ名の最後の「.」より後で照合し、点数を半分にして `positions` を空にする。並べ替えは点数の降順、起動回数の降順、ラベルの順。

- [ ] **Step 4: テストが通ることを確かめる**

Run: `nix develop -c ./gradlew :core:model:test --tests '*AppSearchTest*'`
Expected: PASS

- [ ] **Step 5: コミット**

---

### Task 2: 起動回数の記録

**Files:**
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/Launches.kt`
- Modify: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/store/Stores.kt`（`FileLaunchStore` を追加、`launches.json`）
- Modify: `app/src/main/kotlin/dev/furunov/komalauncher/AppContainer.kt`（`appCatalog` を包む、flush と brokenFile に加える）
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/apps/LaunchesTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class Launches(val version: Int = 1, val counts: Map<String, Int> = emptyMap())` — キーは `"パッケージ/クラス"`。`fun countOf(key: AppKey): Int`、`fun asMap(): Map<AppKey, Int>`、`fun record(key: AppKey): Launches`
  - `interface LaunchStore { val launches: StateFlow<Launches>; fun update(transform: (Launches) -> Launches) }`
  - `class CountingAppCatalog(private val inner: AppCatalog, private val store: LaunchStore) : AppCatalog by inner` — `launch` だけを上書きし、`inner.launch` の後に `store.update { it.record(key) }`。

- [ ] **Step 1: 失敗するテストを書く** — `recordIncrements`（2 回記録で 2）、`countingCatalogRecordsLaunchOnly`（fake の `AppCatalog` で `launch` は数え、`launchShortcut` は数えない）。
- [ ] **Step 2: 失敗を確かめる** — `nix develop -c ./gradlew :core:model:test --tests '*LaunchesTest*'`、コンパイルエラー。
- [ ] **Step 3: 実装する。** `AppContainer` では `val appCatalog: AppCatalog = CountingAppCatalog(LauncherAppsCatalog(context, scope), fileLaunches)` とし、`launchStore` を公開する。`DefaultActionPerformer` は `appCatalog` を受け取っているので、ジェスチャーからの起動も数えられる。
- [ ] **Step 4: テストが通ることを確かめる** — 同じコマンドで PASS。
- [ ] **Step 5: コミット**

---

### Task 3: 検索用のショートカットの一覧

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/Apps.kt`（`AppCatalog` に `fun allShortcuts(): List<AppShortcut>` を追加）
- Modify: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/LauncherAppsCatalog.kt`
- Modify: テストの fake の `AppCatalog`（`HomeViewModelTest.kt` ほか、`grep -rn ": AppCatalog" */*/src/test` で探す）に `override fun allShortcuts() = emptyList<AppShortcut>()`

**Interfaces:**
- Produces: `AppCatalog.allShortcuts(): List<AppShortcut>` — IO のスレッドから呼ぶ。ホームアプリでないときは空。

- [ ] **Step 1: 実装する。** `ShortcutQuery` にパッケージを指定せず `FLAG_MATCH_DYNAMIC or FLAG_MATCH_MANIFEST` で問い合わせる。例外か空なら、`apps.value` のパッケージごとに既存の `shortcuts` を呼んでまとめる。結果は `apps` に入っているパッケージのものだけに絞る。
- [ ] **Step 2: ビルドする** — `nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest assembleDebug`、PASS。
- [ ] **Step 3: コミット**

---

### Task 4: 全アプリ一覧の検索の画面

**Files:**
- Modify: `feature/drawer/src/main/kotlin/dev/furunov/komalauncher/feature/drawer/DrawerScreen.kt`
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/DrawerList.kt`（表示する並びを作る純粋な関数）
- Modify: `app/src/main/kotlin/dev/furunov/komalauncher/MainActivity.kt`（新しい引数を渡す）
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/apps/DrawerListTest.kt`

**Interfaces:**
- Consumes: Task 1 の `AppSearch.rank`・`rankShortcuts`、Task 2 の `Launches.asMap()`、Task 3 の `allShortcuts()`
- Produces:
  - `object DrawerList { fun frequent(apps: List<AppEntry>, hidden: Set<AppKey>, launches: Map<AppKey, Int>, limit: Int): List<AppEntry> }` — 起動回数 1 以上のアプリを回数の降順に最大 `limit` 個。
  - `DrawerScreen(apps, columns, hidden, launches: Map<AppKey, Int>, shortcuts: suspend () -> List<AppShortcut>, actions, modifier)`
  - `DrawerActions` に `onLaunchShortcut: (AppShortcut) -> Unit = {}` を追加（`MainActivity` は `HomeEvent.LaunchShortcut` を送る）。

- [ ] **Step 1: 失敗するテストを書く** — `frequentIsEmptyWithoutHistory`、`frequentSkipsHiddenAndMissing`（隠したアプリと `apps` に無いキーを除く）、`frequentLimitsToColumns`（limit=4 で 4 個）。
- [ ] **Step 2: 失敗を確かめる** — `nix develop -c ./gradlew :core:model:test --tests '*DrawerListTest*'`
- [ ] **Step 3: `DrawerList` を実装し、テストを通す。**
- [ ] **Step 4: 画面を変える。**
  - 開いたとき `FocusRequester` で検索欄にフォーカスし、キーボードを表示する。
  - `query.trim()` が空: 「よく使う」の見出しと `frequent(limit = columns)` の 1 行（空なら見出しごと出さない）、区切り線、今の全アプリ。
  - 空でない: `AppSearch.rank` の結果を格子に並べ、先頭のアイコンを白い角丸の枠（1.5dp）で囲む。ラベルは `AnnotatedString` で `positions` を太字にする。ショートカットが 1 つ以上一致すれば、全幅の「ショートカット」の見出しと、アイコン（アプリのもの）・「アプリ名 · 項目名」の行を最大 8 件。
  - `KeyboardOptions(imeAction = ImeAction.Go)` と `KeyboardActions(onGo = …)` で、先頭のアプリ、無ければ先頭のショートカットを起動する。
  - ショートカットは一覧を開いたときに `LaunchedEffect` から IO で 1 回だけ取得する。
- [ ] **Step 5: Waydroid で確かめる** — `nix develop -c ./gradlew assembleDebug` の後に入れ、上スワイプでキーボードが現れること、「se」で設定が先頭に来て Enter で開くことを `scripts/e2e-waydroid.sh` の項目に追加して確かめる（Task 7）。ここではビルドの成功まで。
- [ ] **Step 6: コミット**

---

### Task 5: 押したら直接起動するフォルダ

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/folders/Folders.kt`（`Folder.directLaunch: Boolean = false`、`FolderEdits.setDirectLaunch`）
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModel.kt`（`TapPanel`）
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeOverlays.kt`（`PanelMenuSheet`）
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/folders/FolderEditsTest.kt`、`feature/home/src/test/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces: `FolderEdits.setDirectLaunch(f: Folders, panelId: String, on: Boolean): Folders`

- [ ] **Step 1: 失敗するテストを書く**
  - `FolderEditsTest.setDirectLaunchKeepsApps`（切り替えても `apps` と `name` が変わらない。フォルダが無ければ作る）
  - `HomeViewModelTest.directLaunchStartsFirstApp`（オンのフォルダを押すと先頭のアプリが `launch` され、`overlay` は `None`）
  - `HomeViewModelTest.directLaunchSkipsMissingApps`（先頭がカタログに無ければ次を起動。1 つも無ければ `Overlay.Folder`）
  - `HomeViewModelTest.directLaunchEmptyOpensFolder`
  - `HomeViewModelTest.gestureOpenFolderIgnoresDirectLaunch`（`LauncherAction.OpenFolder` は `Overlay.Folder`）
- [ ] **Step 2: 失敗を確かめる** — `nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest`
- [ ] **Step 3: 実装する。** `TapPanel` で `Folder` かつ `directLaunch` なら、`folders.appsOf(id)` のうち `apps.apps.value` にあるキーの先頭を `apps.launch` する。起動はコマを離した時点（今のフォルダと同じく、押したときのアニメーションの完了を待たない。`HomeScreen.kt:178` の方針）。長押しメニューのフォルダの項目に、`"押したら直接起動: オン"`／`"押したら直接起動: オフ"`（押すと切り替え、`EditFolders` で `setDirectLaunch`）と、`directLaunch` のときだけ `"フォルダを開く"`（`OpenOverlay(Overlay.Folder(id))`）を追加する。
- [ ] **Step 4: テストが通ることを確かめる** — 同じコマンドで PASS。
- [ ] **Step 5: コミット**

---

### Task 6: コマの背景の暗さ

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/layout/Layout.kt`（`PanelArt.dim: Float = 0f`）
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/layout/LayoutParser.kt`（`ArtDto.dim`、読むとき 0〜1 に収める）
- Modify: `core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/art/PanelFace.kt`（コンストラクタに `dim: Float = 0f`、`drawArt` で絵の直後にコマ全体へ `Color.Black.copy(alpha = dim)`。絵の移動 `shift` の外で塗る）
- Modify: `feature/home/.../PanelGeometry.kt:41`、`feature/editor/.../EditorScreen.kt:204`（`panel.art?.dim ?: 0f` を渡す）
- Modify: `feature/editor/.../EditorScreen.kt`（画像の欄、`selectedPanel.art != null` のとき `SliderRow("暗さ", art.dim * 100f, 0f..100f)`）、`EditorViewModel.kt`（`fun setDim(v: Float)`、スライダーなので `record = false` の扱いは他のスライダーと同じ）
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/layout/LayoutV2ParserTest.kt`

**Interfaces:**
- Produces: `PanelArt.dim`、`EditorViewModel.setDim(value: Float)`（0〜1）

- [ ] **Step 1: 失敗するテストを書く** — `dimRoundTrips`（`"dim": 0.4` を読んで書いて 0.4）、`dimDefaultsToZero`、`dimIsClamped`（1.5 → 1、-1 → 0）。
- [ ] **Step 2: 失敗を確かめる** — `nix develop -c ./gradlew :core:model:test --tests '*LayoutV2ParserTest*'`
- [ ] **Step 3: 実装する。** 文字は `drawArt` の後に描くので暗くならない（人物の後ろの文字は文字の層を切り抜く方法なので、人物を描き直さない）。
- [ ] **Step 4: テストとビルド** — `nix develop -c ./gradlew :core:model:test :feature:editor:testDebugUnitTest assembleDebug`、PASS。
- [ ] **Step 5: コミット**

---

### Task 7: e2e・README・最後のレビュー

**Files:**
- Modify: `scripts/e2e-waydroid.sh`、`README.md`

- [ ] **Step 1: e2e に項目を追加する** — 一覧を開くとキーボードが表示される（`dumpsys input_method` の `mInputShown=true`）、「se」を入力して Enter で設定が前面に来る、見本のフォルダを長押しして「押したら直接起動: オフ」を押し、そのコマを押すとフォルダを開かずに先頭のアプリが前面に来る。
- [ ] **Step 2: 実行する** — `scripts/e2e-waydroid.sh build/e2e`、全項目 ok。スクリーンショット（一覧の空の入力・入力あり）を目で確かめる。
- [ ] **Step 3: README を更新する** — 「コマ割りを編集する」に暗さ、`layout.json` の説明の `art` に `dim`、新しい節で検索（照合の仕方、起動回数は端末の外に送らない）と直接起動。
- [ ] **Step 4: 全体のテスト・lint** — `nix develop -c ./gradlew spotlessApply :core:model:test :feature:home:testDebugUnitTest :feature:editor:testDebugUnitTest lint assembleRelease`
- [ ] **Step 5: コミット**
- [ ] **Step 6: サブエージェントのコードレビューを 1 回だけ依頼し、指摘を直してコミットする。**
