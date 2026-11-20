# 再生中のコマ 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 音楽が再生中か一時停止中のあいだ、利用者が選んだフォルダのコマにジャケットと下の辺のビジュアライザーを表示し、タップと左右のスワイプで再生を操作できるようにする。

**Architecture:** 再生状態の型・判定・ビジュアライザーの計算は `:core:model` の純粋な関数にする。Android との接続（MediaSession、通知へのアクセス、Visualizer）は `:core:platform` に置き、`TiltSource` と同じく start/stop でホームが前面にある間だけ動かす。ホーム画面は、プレイヤー表示のコマの上に重ねる層を 1 つ追加し、ViewModel がタップとスワイプを再生の操作へ振り分ける。

**Tech Stack:** Kotlin, Jetpack Compose（Material なし）, kotlinx.serialization, `android.media.session.MediaSessionManager`, `android.service.notification.NotificationListenerService`, `android.media.audiofx.Visualizer`

**Spec:** `docs/superpowers/specs/2026-11-20-now-playing-panel-design.md`

## Global Constraints

- minSdk 33（全モジュール）。
- UI の文言は Android の標準の書き方（漢語・カタカナ語の体言止め）にする。「横に割る」のような動詞句のラベルを並べない。
- 曲名・アーティスト名・進み具合は表示しない。ボタンは置かない。
- 一時停止から元に戻すまでの時間の既定は 30 分。
- 棒は 9dp ごとに 1 本、最低 10 本。色は `Appearance.highlightColor`。
- 実際の音（Visualizer）は、設定「音に合わせて動かす」がオンで、マイクの権限があるときだけ使う。既定はオフ。
- 再生中なのに Visualizer の値が 5 秒続けてすべて 0 なら、それらしい動きに切り替える。
- ホームが前面にない間は、セッションの監視・Visualizer・描き直しをすべて止める。
- version 1（`LayoutShape.Fixed`）のレイアウトではプレイヤー表示をしない。
- コードのコメントとコミットメッセージは日本語で、既存のコメントの書き方に合わせる。

## Review Focus

- 音楽プレイヤーをオンにしたフォルダのコマを、レイアウトの編集で消したり種類を変えたりしたとき: プレイヤー表示はされず、例外も起きない（Task 4 のテスト）。
- 一時停止のまま 30 分を超えてからホームに戻ったとき: いつものコマで表示され、タップでフォルダが開く（Task 2・Task 4 のテスト）。
- 通知へのアクセスが取り消されたとき: `getActiveSessions` の `SecurityException` で落ちず、いつものコマに戻る（Task 3 で try/catch、Task 7 で手で確認）。
- ジャケット画像がない曲・極端に横長の画像: 元の絵の上に棒を表示し、cover の計算で 0 除算をしない（Task 5）。
- 直接起動とプレイヤーの両方がオンのコマ: プレイヤー表示の間はタップが再生・一時停止、表示でないときは先頭のアプリを起動する（Task 4 のテスト）。

---

### Task 1: フォルダと設定に「音楽プレイヤー」を追加する

**Files:**
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/folders/Folders.kt`
- Modify: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/settings/Settings.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/folders/FolderEditsTest.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/settings/SettingsTest.kt`

**Interfaces:**
- Produces: `Folder.musicPlayer: Boolean = false`、`Folders.musicPanel(): String?`（`musicPlayer` が true の最初のコマの id）、`FolderEdits.setMusicPlayer(f: Folders, panelId: String, on: Boolean): Folders`、`Settings.music: MusicSettings`、`data class MusicSettings(val pauseTimeoutMinutes: Int = 30, val reactToAudio: Boolean = false)`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
@Test
fun musicPlayerIsOnlyOnePanel() {
    val a = FolderEdits.setMusicPlayer(Folders(), "p1", true)
    val b = FolderEdits.setMusicPlayer(a, "p2", true)
    assertEquals("p2", b.musicPanel())
    assertFalse(b.byPanel.getValue("p1").musicPlayer)
    assertNull(FolderEdits.setMusicPlayer(b, "p2", false).musicPanel())
}

@Test
fun oldFoldersJsonHasNoMusicPlayer() {
    val f = Json { ignoreUnknownKeys = true }.decodeFromString<Folders>("""{"byPanel":{"p1":{"apps":[]}}}""")
    assertNull(f.musicPanel())
}
```

SettingsTest に、`music` の無い古い JSON を読むと `MusicSettings(30, false)` になるテストを追加する（既存の古い JSON のテストと同じ書き方）。

- [ ] **Step 2: 失敗を確かめる** — Run: `nix develop -c ./gradlew -q :core:model:test` / Expected: コンパイルエラー（`setMusicPlayer` が無い）
- [ ] **Step 3: 実装する** — `setMusicPlayer` は on のとき他のすべてのフォルダの `musicPlayer` を false にする。`Settings` に `val music: MusicSettings = MusicSettings()` を追加する。
- [ ] **Step 4: 通ることを確かめる** — 同じコマンドで PASS
- [ ] **Step 5: コミット** — `feat(model): フォルダの音楽プレイヤーの設定と、一時停止から戻すまでの時間`

### Task 2: 再生状態の型と、プレイヤー表示にする条件

**Files:**
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/media/Media.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/media/MediaTest.kt`

**Interfaces:**
- Produces:
  - `enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, STOPPED }`
  - `data class NowPlaying(val packageName: String, val status: PlaybackStatus, val trackKey: String, val pausedAtMillis: Long?)` — `trackKey` はジャケットのクロスフェードの合図（タイトルとアーティストをつないだ文字列）。`pausedAtMillis` は PAUSED のときだけ値を持つ。
  - `interface MediaSessions { val nowPlaying: StateFlow<NowPlaying?>; val hasAccess: StateFlow<Boolean>; fun start(); fun stop(); fun togglePlayPause(); fun next(); fun previous(); fun openPlayer(); fun openAccessSettings(); fun openAppInfo() }`
  - `object PlayerRules { fun showsPlayer(np: NowPlaying?, nowMillis: Long, pauseTimeoutMinutes: Int): Boolean; fun hideAtMillis(np: NowPlaying?, pauseTimeoutMinutes: Int): Long? }` — `hideAtMillis` は一時停止中なら `pausedAtMillis + timeout`、それ以外は null。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
private fun np(s: PlaybackStatus, pausedAt: Long? = null) = NowPlaying("com.google.android.apps.youtube.music", s, "t", pausedAt)

@Test fun playingShows() = assertTrue(PlayerRules.showsPlayer(np(PlaybackStatus.PLAYING), 0, 30))
@Test fun bufferingShows() = assertTrue(PlayerRules.showsPlayer(np(PlaybackStatus.BUFFERING), 0, 30))
@Test fun stoppedHides() = assertFalse(PlayerRules.showsPlayer(np(PlaybackStatus.STOPPED), 0, 30))
@Test fun noneHides() = assertFalse(PlayerRules.showsPlayer(null, 0, 30))
@Test fun pausedShowsUntilTimeout() {
    val p = np(PlaybackStatus.PAUSED, pausedAt = 1_000L)
    assertTrue(PlayerRules.showsPlayer(p, 1_000L + 30 * 60_000L - 1, 30))
    assertFalse(PlayerRules.showsPlayer(p, 1_000L + 30 * 60_000L, 30))
    assertEquals(1_000L + 30 * 60_000L, PlayerRules.hideAtMillis(p, 30))
}
```

- [ ] **Step 2: 失敗を確かめる** — Run: `nix develop -c ./gradlew -q :core:model:test` / Expected: コンパイルエラー
- [ ] **Step 3: 実装する**
- [ ] **Step 4: 通ることを確かめる**
- [ ] **Step 5: コミット** — `feat(model): 再生状態の型と、プレイヤー表示にする条件`

### Task 3: ビジュアライザーの計算

**Files:**
- Create: `core/model/src/main/kotlin/dev/furunov/komalauncher/model/media/Spectrum.kt`
- Test: `core/model/src/test/kotlin/dev/furunov/komalauncher/model/media/SpectrumTest.kt`

**Interfaces:**
- Consumes: `dev.furunov.komalauncher.model.geometry.Point`
- Produces:
  - `interface AudioSpectrum { fun start(): Boolean; fun stop(); fun read(out: ByteArray): Boolean }` — `read` は Visualizer の FFT（`getFft` の形式: [0]=DC 実部, [1]=ナイキスト実部, 以降 実部・虚部の組）を `out` に書く。`start` は作れなければ false。
  - `object Spectrum`:
    - `fun bottomEdge(points: List<Point>): Pair<Point, Point>` — 中点の y が最大の辺。戻り値は x の小さい端を first にする。
    - `fun barCount(edgeLengthPx: Float, density: Float): Int` — `max(10, round(edgeLengthPx / (9 * density)))`
    - `fun bands(fft: ByteArray, count: Int, out: FloatArray)` — 1 番目の周波数から最後までを対数の間隔で `count` 個にまとめ、各組の大きさの最大値を `log10(1 + mag) / log10(1 + 128)` で 0〜1 にして書く。
    - `fun smooth(current: FloatArray, target: FloatArray, dtSec: Float)` — 上がるときは係数 `min(1, dt*30)`、下がるときは `min(1, dt*6)` で current を target へ寄せる。
    - `fun simulated(count: Int, timeSec: Float, level: Float, out: FloatArray)` — モックアップの `spectrum()` と同じ式（拍は 2Hz の `cos^6`、低い 1/4 に強く加える）。
    - `fun isSilent(fft: ByteArray): Boolean` — すべて 0 なら true。

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
@Test fun bottomEdgeOfSlantedQuad() {
    val e = Spectrum.bottomEdge(listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 45f), Point(0f, 60f)))
    assertEquals(Point(0f, 60f) to Point(100f, 45f), e)
}
@Test fun barCountHasMinimum() = assertEquals(10, Spectrum.barCount(30f, 3f))
@Test fun silentFftGivesZeroBands() {
    val out = FloatArray(12) { 1f }
    Spectrum.bands(ByteArray(1024), 12, out)
    assertTrue(out.all { it == 0f })
    assertTrue(Spectrum.isSilent(ByteArray(1024)))
}
@Test fun loudLowFrequencyRaisesFirstBand() {
    val fft = ByteArray(1024).also { it[2] = 100; it[3] = 0 }
    val out = FloatArray(12)
    Spectrum.bands(fft, 12, out)
    assertTrue(out[0] > 0.5f && out.drop(1).all { it < 0.01f })
}
@Test fun simulatedIsZeroAtLevelZero() {
    val out = FloatArray(16) { 1f }
    Spectrum.simulated(16, 3.2f, 0f, out)
    assertTrue(out.all { it == 0f })
}
@Test fun smoothRisesFasterThanFalls() {
    val up = floatArrayOf(0f); Spectrum.smooth(up, floatArrayOf(1f), 0.016f)
    val down = floatArrayOf(1f); Spectrum.smooth(down, floatArrayOf(0f), 0.016f)
    assertTrue(up[0] > 1f - down[0])
}
```

- [ ] **Step 2: 失敗を確かめる**
- [ ] **Step 3: 実装する** — 毎フレーム呼ぶので、配列を新しく作らない（`out` に書く）。
- [ ] **Step 4: 通ることを確かめる**
- [ ] **Step 5: コミット** — `feat(model): ビジュアライザーの棒の計算`

### Task 4: ホームの操作の振り分け（ViewModel とジェスチャー）

**Files:**
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeGestures.kt`
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModel.kt`
- Test: `feature/home/src/test/kotlin/dev/furunov/komalauncher/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1・2 の型
- Produces:
  - `HomeGestureHandler.onPanelSwipe(panel: PanelGeometry, toLeft: Boolean)`（既定の実装なし。HomeScreen が実装する）
  - `HomeUiState.player: PlayerUi?` — `data class PlayerUi(val panelId: PanelId, val playing: Boolean, val packageName: String, val trackKey: String)`。プレイヤー表示のときだけ非 null。
  - `HomeEvent.SwipePanel(panelId: PanelId, toLeft: Boolean)`、`HomeEvent.SetMusicPlayer(panelId: PanelId, on: Boolean)`、`HomeEvent.OpenPlayerApp`、`HomeEvent.OpenMusicAccess`、`HomeEvent.OpenAppInfoForAccess`
  - `Overlay.MusicAccess`（説明のシート）
  - `HomeViewModel(layoutSource, folderStore, settingsStore, apps, actions, media: MediaSessions, clock: () -> Long = System::currentTimeMillis)`

- [ ] **Step 1: 失敗するテストを書く**

`FakeMedia : MediaSessions`（`nowPlaying` と `hasAccess` を MutableStateFlow で持ち、呼ばれた操作を `calls: MutableList<String>` に記録）を追加し、`setUp` で渡す。テスト:

```kotlin
@Test fun tapPlayerPanelTogglesPlayback() {
    folders.update { FolderEdits.setMusicPlayer(it, "p1", true) }
    media.nowPlaying.value = NowPlaying("m", PlaybackStatus.PLAYING, "t", null)
    vm.onEvent(HomeEvent.TapPanel(PanelId("p1")))
    assertEquals(listOf("toggle"), media.calls)
    assertEquals(Overlay.None, vm.state.value.overlay)
}
@Test fun swipeLeftOnPlayerPanelSkipsNext() { /* 同じ準備で SwipePanel(p1, toLeft = true) → calls == ["next"]、toLeft = false → "previous" */ }
@Test fun withoutSessionPlayerPanelOpensFolder() { /* setMusicPlayer だけ → TapPanel で Overlay.Folder(p1) */ }
@Test fun pausedPastTimeoutOpensFolder() { /* clock = { 31 * 60_000L }, PAUSED pausedAt = 0 → state.player == null、TapPanel で Overlay.Folder */ }
@Test fun directLaunchStillWorksWhenNotPlaying() { /* directLaunch と musicPlayer の両方オン、セッションなし → TapPanel で chrome を起動 */ }
@Test fun playerPanelRemovedFromLayoutIsIgnored() { /* musicPlayer を "gone" に付け、PLAYING → state.player == null */ }
@Test fun turningOnWithoutAccessShowsExplanation() { /* hasAccess = false, SetMusicPlayer(p1, true) → overlay == Overlay.MusicAccess、folders.musicPanel() == "p1" */ }
@Test fun swipeOnFolderPanelDoesNothing() { /* セッションなしで SwipePanel → calls が空、overlay は None */ }
```

- [ ] **Step 2: 失敗を確かめる** — Run: `nix develop -c ./gradlew -q :feature:home:testDebugUnitTest` / Expected: コンパイルエラー
- [ ] **Step 3: ViewModel を実装する**
  - `player` は、`folders.musicPanel()` がレイアウトに存在するフォルダのコマで、レイアウトが `LayoutShape.Tree` で、`hasAccess` が true で、`PlayerRules.showsPlayer(np, clock(), timeout)` が true のときに作る。
  - 一時停止の期限を過ぎたときに再計算させるため、`media.nowPlaying` を `flatMapLatest` で包み、`PlayerRules.hideAtMillis` があれば `delay(hideAt - clock())` の後にもう一度流す。
  - `TapPanel`: `player?.panelId == panelId` なら `media.togglePlayPause()` を呼んで終わる。それ以外は今と同じ。
  - `SwipePanel`: プレイヤー表示のコマなら `next()`／`previous()`、それ以外は何もしない。
  - `SetMusicPlayer`: `FolderEdits.setMusicPlayer` を適用し、on かつ `hasAccess` が false なら `Overlay.MusicAccess` を開く。
- [ ] **Step 4: ジェスチャーに左右のスワイプを追加する** — `Outcome.HorizontalSwipe(toLeft: Boolean)` を追加し、指を離したとき `abs(d.x) > swipeDistance && abs(d.x) > abs(d.y)` で返す（長押しの後の続きでも同じ）。コマの上なら `onPanelRelease(panel, false)` の後に `onPanelSwipe(panel, toLeft)` を呼ぶ。余白の上では何もしない。
- [ ] **Step 5: 通ることを確かめる** — Step 2 のコマンドで PASS
- [ ] **Step 6: コミット** — `feat(home): プレイヤー表示のコマのタップとスワイプを再生の操作にする`

### Task 5: プレイヤー表示の描画とメニュー

**Files:**
- Create: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/PlayerLayer.kt`
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeScreen.kt`
- Modify: `feature/home/src/main/kotlin/dev/furunov/komalauncher/feature/home/HomeOverlays.kt`

**Interfaces:**
- Consumes: `HomeUiState.player`、`Spectrum`、`AudioSpectrum`、Task 4 の events
- Produces:
  - `class HomeMedia(val art: StateFlow<ImageBitmap?>, val spectrum: AudioSpectrum?)` — `HomeScreen` の新しい引数 `media: HomeMedia? = null`。`spectrum` は「音に合わせて動かす」がオンで権限があるときだけ非 null（:app が決める）。
  - `@Composable internal fun PlayerLayer(g: PanelGeometry, motion: PanelMotion, player: PlayerUi, art: ImageBitmap?, fallbackArt: ImageBitmap?, barColor: Color, spectrum: AudioSpectrum?, flash: PlayerFlash?)`
  - `enum class PlayerFlash { TOGGLE, NEXT, PREVIOUS }` と、表示した時刻

- [ ] **Step 1: `PlayerLayer` を書く**
  - `PanelBox` と同じく `g.bounds` に置き、`motion` の移動・拡大・不透明度と `g.shape` の切り抜きを適用する。押している間の暗さ（`motion.press`）もここで描く。
  - 絵: `art` があれば cover で描き、無ければ `fallbackArt`（コマの元の絵）を `g.face.drawArt` で描く。`Crossfade(targetState = player.trackKey to art, animationSpec = tween(250))`。一時停止中は `ColorMatrix().apply { setToSaturation(0.15f) }` と明るさ 0.8 を掛け、中央に再生の三角形を描く。
  - 棒: `Spectrum.bottomEdge(g.polygon.points)` の辺に、`Spectrum.barCount` 本を辺に垂直に立てる（向きはコマの内側）。高さの上限は `min(g.inscribed.height * 0.55f, 120.dp)`。値が大きいほど不透明（`0.6 + 0.4 * v`）。
  - 値の更新: `LaunchedEffect(player.playing, spectrum)` の中で `withFrameNanos` を回す。`spectrum` があれば `start()` し、`read` → `Spectrum.bands` → `smooth`。再生中に `isSilent` が 5 秒続いたら `stop()` してそれらしい動きに切り替える。`spectrum` が無ければ `Spectrum.simulated`。一時停止したら level を 0 へ寄せ、すべて 0.001 未満になったらループを抜ける。`DisposableEffect` で `stop()`。
  - 操作の記号: `flash` があれば中央に 0.6 秒、黒い丸と記号を描いて消す。
- [ ] **Step 2: HomeScreen に組み込む**
  - プレイヤー表示のコマは、`texts` の `labels` を空にして渡す（名前を出さない。時計と大見出しは残す）。
  - `PanelLayer` の後に、`state.player?.panelId == g.panel.id` なら `PlayerLayer` を重ねる。
  - `homeGestures` の handler に `onPanelSwipe` を実装し、`HomeEvent.SwipePanel` を送る。プレイヤー表示のコマのタップとスワイプでは `flash` を記録する。
- [ ] **Step 3: メニューと説明のシートを追加する**（`HomeOverlays.kt`）
  - `PanelMenuSheet` に引数 `musicPlayer: Boolean`、`showingPlayer: Boolean` を追加する。フォルダのコマに「音楽プレイヤー: オン／オフ」を追加し、`showingPlayer` なら「フォルダを開く」（直接起動と重ならないよう 1 回だけ）と「再生中のアプリを開く」を追加する。
  - `MusicAccessSheet(onEvent)`: 見出し「通知へのアクセスが必要です」、本文「再生中の曲を表示するには、通知へのアクセスを許可してください。許可できないときは、アプリ情報の右上のメニューから『制限付き設定を許可』を選んでから、もう一度お試しください。」、ボタン「通知へのアクセスを開く」「アプリ情報を開く」「キャンセル」。
- [ ] **Step 4: ビルドを確かめる** — Run: `nix develop -c ./gradlew -q :feature:home:assembleDebug :feature:home:testDebugUnitTest` / Expected: 成功
- [ ] **Step 5: コミット** — `feat(home): 再生中のコマのジャケットとビジュアライザー`

### Task 6: Android との接続（MediaSession・通知へのアクセス・Visualizer）

**Files:**
- Create: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/media/MediaSessionWatcher.kt`
- Create: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/media/KomaNotificationListener.kt`
- Create: `core/platform/src/main/kotlin/dev/furunov/komalauncher/platform/media/VisualizerSpectrum.kt`
- Modify: `core/platform/src/main/AndroidManifest.xml`（サービスの宣言）
- Modify: `app/src/main/AndroidManifest.xml`（`RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS`）

**Interfaces:**
- Consumes: `MediaSessions`、`NowPlaying`、`AudioSpectrum`
- Produces: `class MediaSessionWatcher(context: Context) : MediaSessions { val art: StateFlow<ImageBitmap?> }`、`class VisualizerSpectrum : AudioSpectrum`、`KomaNotificationListener`（中身なし）

- [ ] **Step 1: `KomaNotificationListener` とマニフェスト** — `<service android:name=".media.KomaNotificationListener" android:exported="false" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">` に `android.service.notification.NotificationListenerService` の intent-filter。
- [ ] **Step 2: `MediaSessionWatcher`**
  - `hasAccess` は `NotificationManagerCompat.getEnabledListenerPackages` を使わず、`Settings.Secure "enabled_notification_listeners"` に自分のコンポーネントが含まれるかで判定する（依存を増やさない）。`start()` のたびに更新する。
  - `start()`: `addOnActiveSessionsChangedListener` を登録し、`getActiveSessions` の各 `MediaController` に `Callback` を登録する。対象は返る順に見て PLAYING/PAUSED/BUFFERING（`STATE_CONNECTING` と `STATE_SKIPPING_TO_*` は BUFFERING に含める）の最初のもの。`SecurityException` は捕まえて `hasAccess = false`、`nowPlaying = null` にする。
  - `pausedAtMillis`: 状態が PAUSED に変わったときの `System.currentTimeMillis()`。`start()` で最初に読んだときから PAUSED なら、`PlaybackState.lastPositionUpdateTime`（elapsedRealtime）を壁時計に直した値を使う。
  - ジャケット: `METADATA_KEY_ART` → `ALBUM_ART` → `ART_URI` → `ALBUM_ART_URI` の順。URI は `ContentResolver.loadThumbnail(uri, Size(512, 512), null)` を IO で読む。`asImageBitmap()` して `art` に流す。
  - `stop()`: リスナーとコールバックを外す。
  - 操作: `transportControls.play()/pause()/skipToNext()/skipToPrevious()`。`openPlayer()` は `controller.sessionActivity` があればそれを送り、無ければパッケージの起動の Intent。`openAccessSettings()` は `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`、`openAppInfo()` は自分のパッケージの `ACTION_APPLICATION_DETAILS_SETTINGS`（どちらも `FLAG_ACTIVITY_NEW_TASK`）。
- [ ] **Step 3: `VisualizerSpectrum`** — `start()` で `Visualizer(0)` を作り、`captureSize = getCaptureSizeRange()[1]`、`enabled = true`。例外（権限なし・端末が非対応）なら false。`read(out)` は `getFft(out)` が `SUCCESS` なら true。`stop()` で `release()`。
- [ ] **Step 4: ビルドを確かめる** — Run: `nix develop -c ./gradlew -q :core:platform:assembleDebug` / Expected: 成功
- [ ] **Step 5: コミット** — `feat(platform): MediaSession と Visualizer の接続`

### Task 7: 設定画面、:app の組み立て、確認

**Files:**
- Modify: `feature/settings/src/main/kotlin/dev/furunov/komalauncher/feature/settings/SettingsTable.kt`
- Modify: `feature/settings/src/main/kotlin/dev/furunov/komalauncher/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/dev/furunov/komalauncher/AppContainer.kt`
- Modify: `app/src/main/kotlin/dev/furunov/komalauncher/MainActivity.kt`
- Modify: `app/src/main/kotlin/dev/furunov/komalauncher/SettingsActivity.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `MediaSessionWatcher`、`VisualizerSpectrum`、`HomeMedia`
- Produces: `ActionKey.MusicAccess`、`SettingRow.PermissionToggle(title: String, subtitle: String, permission: String, get, set)`、`SettingsDeps.hasMusicAccess: () -> Boolean`、`SettingsDeps.openMusicAccess: () -> Unit`

- [ ] **Step 1: 設定画面** — 「音楽プレイヤー」の欄を「アニメーション」の後に追加する。
  - Action「通知へのアクセス」、サブタイトルは `hasMusicAccess()` で「許可済み」／「未許可」。画面が RESUMED に戻るたびに読み直す。
  - Stepper「一時停止から元に戻すまで」5..120、step 5、unit「分」。
  - PermissionToggle「音に合わせて動かす」、サブタイトル「マイクの権限を使います。音の録音・保存はしません」、permission `RECORD_AUDIO`。オンにするとき権限がなければ `RequestPermission` で求め、断られたらオフのまま。
- [ ] **Step 2: :app の組み立て**
  - `AppContainer` に `val media = MediaSessionWatcher(context)`。
  - `MainActivity`: `HomeViewModel` に `container.media` を渡す。`lifecycle.repeatOnLifecycle(STARTED)` で `media.start()`、離れたら `stop()`。`HomeScreen` に `HomeMedia(container.media.art, spectrum)` を渡す。`spectrum` は `settings.music.reactToAudio` かつ `RECORD_AUDIO` が許可済みなら `remember { VisualizerSpectrum() }`、それ以外は null。
  - `SettingsActivity`: `hasMusicAccess = { container.media.hasAccess... }`（`start()` 中でなくても判定できるよう、判定の関数を `MediaSessionWatcher.checkAccess(): Boolean` として公開して使う）、`openMusicAccess = container.media::openAccessSettings`。
- [ ] **Step 3: README** — 「直接起動」の項目の後に「音楽プレイヤー」の項目を追加する（長押しのメニューでオン、通知へのアクセス、タップ・左右のスワイプ、一時停止から 30 分で戻る、音に合わせて動かす設定）。
- [ ] **Step 4: 全体の確認** — Run: `nix develop -c ./gradlew -q test assembleDebug` / Expected: 成功。続けて `scripts/e2e-waydroid.sh` / Expected: `fail=0`
- [ ] **Step 5: Waydroid で手で確かめる** — YT Music で曲を流し、右下のコマで音楽プレイヤーをオンにする。通知へのアクセスを許可したあと、表示・タップ・左右のスワイプ・一時停止の表示・長押しのメニューを確かめ、スクリーンショットを撮る。許可を取り消して、いつものコマに戻ることも確かめる。
- [ ] **Step 6: コミット** — `feat: 設定画面の音楽プレイヤーの欄と、:app の組み立て`

実機（POCO X7 Pro）での確認（Visualizer が値を返すか、HyperOS での動作、制限付き設定の案内）は、利用者が戻ってから行う。
