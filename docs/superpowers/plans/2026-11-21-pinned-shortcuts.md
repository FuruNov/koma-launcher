# ホーム画面へのショートカットの追加 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ブラウザなど（Vivaldi の PWA）からの「ホーム画面に追加」の依頼を受け付け、追加したショートカットをアプリと同じように一覧・検索・フォルダで扱えるようにする。

**Architecture:** `AppKey` に省略できる `shortcutId` を追加し、固定したショートカットを `AppCatalog.apps` にアプリと並べて出す。起動・アイコン・長押しのメニュー・起動回数の id だけを分岐させる。依頼は `:app` の `PinShortcutActivity` で受け、コマ割りの縮小図（`:core:ui` の `PanelPicker`）で入れるコマを選ぶ。

**Tech Stack:** Kotlin、Jetpack Compose、`LauncherApps`（`getPinItemRequest`・`startShortcut`・`pinShortcuts`・`getShortcutIconDrawable`）、kotlinx.serialization、JUnit。

**Spec:** `docs/superpowers/specs/2026-11-21-pinned-shortcuts-design.md`

## Global Constraints

- 起動は必ず `LauncherApps.startShortcut(packageName, shortcutId, null, null, user)`。ショートカットの URL や Intent を取り出して開かない（PWA がタブで開くのを避ける）。
- 画面の文言に「ショートカット」を使わない。依頼の画面の見出しは「ホーム画面に追加」、ボタンは「キャンセル」「追加」、長押しのメニューは「削除」、確認は「<名前>を削除しますか。」と「キャンセル」「削除」、失敗は「追加できませんでした」。
- 既存の `folders.json`・`launches.json`・バックアップの JSON は、変更なしで読めること。
- モジュールの依存の向き（README の「構成」）を守る。`:feature:*` どうしを参照しない。
- 一覧に無いショートカットは表示のときに隠し、フォルダのデータからは外さない。アプリ（`shortcutId == null`）の扱いは変えない。
- ウィジェットの追加の依頼は作らない。
- コメント・コミットメッセージは日本語。コミットの末尾は `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`（Claude-Session の行は書かない）。

## Review Focus

1. 同じブラウザで 2 つ以上の PWA を追加したとき、一覧・フォルダ・起動回数・アイコンのキャッシュで混ざらないこと（Task 1・2・4 のテストと確認）。
2. フォルダに隠れたショートカットがあるとき、落とした位置が見えている並びどおりになること（Task 1 の `dataIndex` のテスト、Task 3 の `dropIndexSkipsHiddenShortcuts`）。
3. 既定のホームアプリでない間・ロック解除の前に一覧を作り直しても、フォルダのデータが変わらないこと（Task 2 で `folders` に触れないことをコードで確かめる）。
4. 依頼を受ける画面で「キャンセル」や戻るボタンを押したとき、何も追加されないこと（Task 5 の Waydroid の確認）。
5. 長押しの「削除」の確認で「キャンセル」を押したとき、固定もフォルダも変わらないこと（Task 3 のテスト）。

---

### Task 1: データの形（`AppKey`・起動回数・表示する中身）

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/Apps.kt`
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/apps/Launches.kt`
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/folders/Folders.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/apps/LaunchesTest.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/folders/FolderEditsTest.kt`

**Interfaces:**
- Produces:
  - `AppKey(packageName: String, className: String, shortcutId: String? = null)`、`val AppKey.isShortcut: Boolean`（`shortcutId != null`）
  - `fun AppKey.Companion.shortcut(packageName: String, id: String): AppKey`（`className = ""`）
  - `AppCatalog.unpin(key: AppKey)`（インターフェースに追加。`CountingAppCatalog` は委譲のまま）
  - `fun Folders.shownAppsOf(panelId: String, available: Set<AppKey>): List<AppKey>`: `appsOf(panelId)` から、`isShortcut` かつ `available` に無いものを除く
  - `fun FolderEdits.dataIndex(apps: List<AppKey>, shownIndex: Int, shown: (AppKey) -> Boolean): Int`: 見えている並びの位置 `shownIndex` を、`apps` の中の位置に直す。`shownIndex` が見えている数以上なら `apps.size`

- [ ] **Step 1: 失敗するテストを書く**

  - `FolderEditsTest.oldFoldersJsonWithoutShortcutIdDecodes`: `{"byPanel":{"p1":{"apps":[{"packageName":"a","className":"A"}]}}}` を読み、`AppKey("a", "A", null)` になる。
  - `FolderEditsTest.shortcutKeyRoundTrips`: `AppKey.shortcut("com.vivaldi.browser", "pwa-1")` を含む `Folders` を encode → decode して等しい。
  - `FolderEditsTest.shortcutsOfSamePackageAreDistinct`: 同じパッケージの `shortcut(p, "1")` と `shortcut(p, "2")` を `add` すると 2 つ入り、`remove(…, shortcut(p, "1"))` で 1 つだけ外れる。
  - `FolderEditsTest.shownAppsHidesMissingShortcutsOnly`: 中身 `[app(a), shortcut(v,"1"), app(b 一覧に無い), shortcut(v,"2")]`、`available = {app(a), shortcut(v,"2")}` → `[app(a), app(b), shortcut(v,"2")]`。
  - `FolderEditsTest.dataIndexSkipsHidden`: `apps = [x, hidden, y, z]`、`shown = { it != hidden }` → `dataIndex(…, 0) == 0`、`1 → 2`、`2 → 3`、`3 → 4`（末尾）。
  - `LaunchesTest.shortcutIdIsSeparateFromApp`: `record(shortcut(p, "1"))` の後、`countOf(shortcut(p,"1")) == 1`、`countOf(shortcut(p,"2")) == 0`、`countOf(AppKey(p, "Main")) == 0`、`asMap()` に `shortcut(p,"1")` が 1 で戻る。保存の id は `"p/@1"`。

- [ ] **Step 2: 失敗を確かめる**

  Run: `nix develop -c ./gradlew :core:model:test`
  Expected: 上の新しいテストがコンパイルエラーか FAIL。

- [ ] **Step 3: 実装する**

  - `Launches.kt`: id は、ショートカットなら `"$packageName/@$shortcutId"`、それ以外は今のまま。`parseId` は `/` の後が `@` で始まればショートカットに戻す。
  - `LaunchesTest` の `FakeCatalog` と `HomeViewModelTest` の `FakeApps` に `unpin` を追加する（`HomeViewModelTest` の `FakeApps` は、解除した key を記録するリスト `unpinned` を持つ。Task 3 で使う）。

- [ ] **Step 4: 通ることを確かめる**

  Run: `nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット** `feat(model): アプリの識別子に固定したショートカットの id を持たせる`

### Task 2: 一覧・起動・解除・アイコン（`:core:platform`）

**Files:**
- Modify: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/LauncherAppsCatalog.kt`
- Modify: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/apps/LauncherIconLoader.kt`

**Interfaces:**
- Consumes: Task 1 の `AppKey.shortcut`・`isShortcut`・`AppCatalog.unpin`
- Produces: `AppCatalog.apps` に固定したショートカットが `AppEntry(AppKey.shortcut(pkg, id), label)` で並ぶ。`launch` と `IconLoader.load` がショートカットを扱う。

- [ ] **Step 1: 一覧を作る処理を直す**

  `reload()` で、今の Activity の一覧（自分のパッケージを除くのは Activity だけ）に、`hasShortcutHostPermission()` が true のときだけ `ShortcutQuery().setQueryFlags(FLAG_MATCH_PINNED)` の結果のうち `isEnabled` のものを加える。名前は `shortLabel ?: longLabel ?: id`。全体をラベル順に並べる。問い合わせの例外（ロック中など）は、ショートカットを加えずに Activity だけで一覧を作る。この処理はフォルダのデータに触れない。

- [ ] **Step 2: 追従する**

  `LauncherApps.Callback` に `onShortcutsChanged(packageName, shortcuts, user)` を追加し、`reload()` を呼ぶ。

- [ ] **Step 3: 起動・解除・それ以外の分岐**

  - `launch(key)`: `isShortcut` なら `startShortcut(key.packageName, key.shortcutId, null, null, user)`。例外は無視する（`launchShortcut` と同じ）。
  - `unpin(key)`: 同じパッケージの固定したショートカットの id（`ShortcutQuery().setPackage(pkg).setQueryFlags(FLAG_MATCH_PINNED)`）から `key.shortcutId` を除いたリストを `pinShortcuts(pkg, ids, user)` に渡す。例外は無視する。
  - `openAppInfo`・`requestUninstall`: `isShortcut` なら何もしない。`shortcuts(key)`: 空を返す。`resolve(packageName)`: Activity だけから探す（ショートカットを返さない）。

- [ ] **Step 4: アイコン**

  `LauncherIconLoader.load`: `isShortcut` なら `ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id)).setQueryFlags(FLAG_MATCH_PINNED)` で `ShortcutInfo` を取り、`launcherApps.getShortcutIconDrawable(info, density)` を `sizePx` の Bitmap にする。取れなければ null。`cacheKey` に `shortcutId` を含める。

- [ ] **Step 5: ビルドを確かめる**

  Run: `nix develop -c ./gradlew :core:platform:compileDebugKotlin assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 6: コミット** `feat(platform): 固定したショートカットを一覧に並べ、起動・解除・アイコンを扱う`

### Task 3: ホーム（隠す・並べ替えの位置・削除の確認）

**Files:**
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModel.kt`
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeScreen.kt`（`panelIcons`）
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/FolderPopup.kt`
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeOverlays.kt`（`AppMenu` と確認のダイアログ）
- Test: `feature/home/src/test/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 の `shownAppsOf`・`dataIndex`・`unpin`
- Produces:
  - `HomeUiState.shownAppsOf(panelId: String): List<AppKey>` = `folders.shownAppsOf(panelId, labels.keys)`
  - `Overlay.ConfirmRemove(val app: AppKey) : Overlay`
  - `HomeEvent.RemoveShortcut(val app: AppKey)`（確認を出す）、`HomeEvent.ConfirmRemoveShortcut(val app: AppKey)`（解除してフォルダから外す）

- [ ] **Step 1: 失敗するテストを書く**（`HomeViewModelTest`）

  - `removeShortcutAsksBeforeUnpinning`: `RemoveShortcut(sc)` の後、`overlay` が `Overlay.ConfirmRemove(sc)`、`FakeApps.unpinned` が空、フォルダは変わらない。
  - `confirmRemoveUnpinsAndRemovesFromAllFolders`: `sc` が p1 と p2 に入っている状態で `ConfirmRemoveShortcut(sc)` → `unpinned == [sc]`、どちらのフォルダにも無い、`overlay == Overlay.None`。
  - `cancelRemoveKeepsEverything`: `RemoveShortcut(sc)` の後に今の閉じる操作（既存の `HomeEvent` の閉じる操作。無ければ `Dismiss` 相当）→ `unpinned` が空、フォルダは同じ。
  - `dropIndexSkipsHiddenShortcuts`: p1 の中身 `[h1, h2, a, b]`（`h1`・`h2` は一覧に無いショートカット）に、全アプリ一覧から `x` を `Drop(x, null, DropTarget.Panel("p1", index = 1))` で落とす → `state.shownAppsOf("p1") == [a, x, b]`（位置を直さないと `[x, a, b]` になる）。
  - `directLaunchSkipsHiddenShortcut`: 直接起動のフォルダの先頭が一覧に無いショートカットなら、次の項目を起動する（今の `installed` の判定で満たされるはず。テストで固定する）。

- [ ] **Step 2: 失敗を確かめる**

  Run: `nix develop -c ./gradlew :feature:home:testDebugUnitTest`
  Expected: 新しいテストが FAIL。

- [ ] **Step 3: 実装する**

  - `FolderPopup.kt:71` と `panelIcons` で、`state.folders.appsOf(…)` を `state.shownAppsOf(…)` にする。
  - `drop()`: 落とした先が `DropTarget.Panel(panelId, index != null)` のとき、`index` を `FolderEdits.dataIndex(folders.appsOf(panelId), index) { it in shown }` で直してから `add`/`move` に渡す（`shown` は `shownAppsOf` と同じ判定）。
  - `AppMenu`: `menu.app.isShortcut` なら「アプリ情報」「アンインストール」を出さず、「削除」（`Color(0xFFEF9A9A)`）で `RemoveShortcut` を送る。「フォルダから削除」は今のまま出す。
  - `Overlay.ConfirmRemove` のダイアログ: 本文「<labelOf(app)>を削除しますか。」、ボタン「キャンセル」（閉じる）と「削除」（`ConfirmRemoveShortcut`）。見た目は今のメニューのダイアログと同じ作り。
  - `ConfirmRemoveShortcut`: `apps.unpin(app)` の後、全てのコマについて `FolderEdits.remove` を掛けて保存し、`Overlay.None`。

- [ ] **Step 4: 通ることを確かめる**

  Run: `nix develop -c ./gradlew :feature:home:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット** `feat(home): 一覧に無いショートカットを隠し、削除の前に確認する`

### Task 4: 全アプリ一覧とアプリを選ぶ画面の key・検索

**Files:**
- Modify: `feature/drawer/src/main/kotlin/dev/furunov/komalauncher/feature/drawer/DrawerScreen.kt:113,212`
- Modify: `core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/apps/AppPicker.kt:92`

- [ ] **Step 1: 直す**

  - `DrawerScreen.kt` の `AppKey.id()` と `AppPicker.kt` の key に `shortcutId` を含める（`"$packageName/$className/${shortcutId.orEmpty()}"`）。
  - `appOf`（112 行目付近）は `visible.filterNot { it.key.isShortcut }.associateBy { it.key.packageName }` にする。

- [ ] **Step 2: ビルドを確かめる**

  Run: `nix develop -c ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット** `fix(drawer): 同じアプリのショートカットが一覧の key で重ならないようにする`

### Task 5: 依頼を受ける画面と debug の受け口

**Files:**
- Create: `core/ui/src/main/kotlin/dev/furunov/komalauncher/ui/art/PanelPicker.kt`
- Create: `app/src/main/kotlin/dev/furunov/komalauncher/PinShortcutActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/themes.xml`（透過したダイアログのテーマ）
- Modify: `app/src/debug/AndroidManifest.xml`、`app/src/debug/kotlin/dev/furunov/komalauncher/DebugReceiver.kt`（`DEBUG_PIN`）

**Interfaces:**
- Consumes: `LayoutResolver.resolve`（`core/model/.../layout/LayoutTree.kt:138`）、`PanelFace`・`PanelStyle.of`、`rememberArts`（`core/ui/.../art/Art.kt:25`）、`FolderEdits.add`、`AppKey.shortcut`
- Produces: `@Composable fun PanelPicker(layout: Layout, defaultGutter: Long, images: <rememberArts と同じ型>, selected: PanelId?, onSelect: (PanelId) -> Unit, modifier: Modifier)`。フォルダのコマだけ押せる。ほかは不透明度 0.35 で描く。選んだコマに `0xFFE0447A` の太さ 3dp の枠。

- [ ] **Step 1: `PanelPicker` を書く**

  編集画面の `Preview`（`EditorScreen.kt:414`）と同じく、`LayoutResolver.resolve` の結果を `PanelFace`（`drawArt` と `drawBorder`）で描く。文字・時計・アイコンは描かない。押した位置のコマは多角形の内側の判定（`model/geometry` の既存の関数）で決める。

- [ ] **Step 2: `PinShortcutActivity` を書く**

  - `onCreate` で `getSystemService(LauncherApps::class.java).getPinItemRequest(intent)` を取り、`null`・`requestType != REQUEST_TYPE_SHORTCUT`・`!isValid` なら `finish()`。
  - Compose の画面: 見出し「ホーム画面に追加」、アイコン（`getShortcutIconDrawable(request.shortcutInfo, density)`）と `shortLabel`、見出し「追加するコマ」、`PanelPicker`（レイアウトとすき間の色は `AppContainer` の `layoutStore`・`settingsStore`、絵は `layoutStore` の画像の場所）、説明「コマを選ばないと、アプリの一覧にだけ追加します」、ボタン「キャンセル」「追加」。
  - 「追加」: `request.accept()` が true なら、コマを選んでいれば `folderStore.update { FolderEdits.add(it, panelId, AppKey.shortcut(info.package, info.id)) }`。false なら `Toast` で「追加できませんでした」。どちらも `finish()`。
  - 「キャンセル」と戻るボタン: `finish()` だけ（`accept` を呼ばない）。
  - Manifest: `exported="true"`、`excludeFromRecents="true"`、`taskAffinity=""`、`<intent-filter><action android:name="android.content.pm.action.CONFIRM_PIN_SHORTCUT" /></intent-filter>`、透過したダイアログのテーマ。

- [ ] **Step 3: `DEBUG_PIN` を追加する**

  debug の `DebugReceiver` に `dev.furunov.komalauncher.DEBUG_PIN` を追加し、`ShortcutManager.requestPinShortcut` で自分自身のショートカット（id `debug-pin-<extra "n" の値、既定 1>`、名前「テスト <n>」、Intent は `MainActivity` を開く）の固定を依頼する。debug の Manifest に action を追加する。

- [ ] **Step 4: Waydroid で確かめる**（`build-and-install` スキルの手順で入れる）

  1. `adb shell am broadcast -p dev.furunov.komalauncher -a dev.furunov.komalauncher.DEBUG_PIN --ei n 1` → 依頼の画面が開く。縮小図の「連絡」のコマ（右上の斜めのコマ）を押して「追加」→ そのフォルダの末尾に「テスト 1」が入り、全アプリ一覧と検索（「テスト」）にも出る。
  2. `--ei n 2` で依頼 →「キャンセル」→ 一覧に「テスト 2」が無い。同じく戻るボタンでも無い。
  3. `--ei n 3` で依頼 → コマを選ばずに「追加」→ 一覧にだけ出る。
  4. 「テスト 1」を長押し →「削除」→ 確認で「キャンセル」→ 残る。もう一度「削除」→「削除」→ 一覧とフォルダから消える。
  5. フォルダの中で「テスト 1」とほかのアプリを並べ替え、位置どおりに入る（1 の後、削除の前に行う）。

  スクリーンショットを撮り、e2e の 21 項目（`scripts/e2e-waydroid.sh`）が通ることも確かめる。

- [ ] **Step 5: コミット** `feat: ブラウザなどからのホーム画面への追加の依頼を受け付ける`

### Task 6: README と実機の確認項目

**Files:**
- Modify: `README.md`（「アプリを開く」に追加の説明、「バックアップと復元」の機種変更の手順に「PWA は追加し直す」、debug の受け口の一覧に `DEBUG_PIN`）

- [ ] **Step 1: README を直す**

  「アプリを開く」に次の要点を書く: ブラウザの「ホーム画面に追加」（Vivaldi などの PWA）で依頼が来ると、追加するコマを選ぶ画面が開く。追加したものは全アプリ一覧と検索に並び、アプリと同じようにフォルダに入れられる。長押しの「削除」で消せる（ブラウザで追加し直すまで戻らない）。

- [ ] **Step 2: 全体の確認**

  Run: `nix develop -c ./gradlew spotlessApply :core:model:test :feature:home:testDebugUnitTest :feature:editor:testDebugUnitTest lint assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット** `docs: ホーム画面への追加の依頼を README に書く`

- [ ] **Step 4: 実機で確かめてほしいことを利用者に渡す**（次の Release のノートに入れる）

  - Vivaldi でアイマスイントロアプリと paper-catch-up を「ホーム画面に追加」し、コマを選んで追加できる。
  - コマや一覧から起動すると、ブラウザのタブではなく PWA の画面（アドレスバーの無い画面）で開く。
  - 長押しの「削除」で一覧とフォルダから消える。
