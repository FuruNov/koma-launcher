package dev.furunov.komalauncher

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.furunov.komalauncher.feature.drawer.DrawerActions
import dev.furunov.komalauncher.feature.drawer.DrawerScreen
import dev.furunov.komalauncher.feature.home.HomeEvent
import dev.furunov.komalauncher.feature.home.HomeMedia
import dev.furunov.komalauncher.feature.home.HomeScreen
import dev.furunov.komalauncher.feature.home.HomeSlots
import dev.furunov.komalauncher.feature.home.HomeViewModel
import dev.furunov.komalauncher.feature.home.Overlay
import dev.furunov.komalauncher.feature.home.PanelMenuItem
import dev.furunov.komalauncher.feature.widgets.PickTarget
import dev.furunov.komalauncher.feature.widgets.WidgetChoice
import dev.furunov.komalauncher.feature.widgets.WidgetPanel
import dev.furunov.komalauncher.feature.widgets.WidgetPicker
import dev.furunov.komalauncher.model.apps.DrawerFolders
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.layout.LayoutShape
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.model.widgets.WidgetPlacement
import dev.furunov.komalauncher.ui.apps.AppPicker
import dev.furunov.komalauncher.ui.icons.LocalIconLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as KomaApplication).container }

    private val home: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                HomeViewModel(container.layoutSource, container.folderStore, container.settingsStore, container.appCatalog, container.actions, container.media)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by home.state.collectAsStateWithLifecycle()
            val apps by container.appCatalog.apps.collectAsStateWithLifecycle()
            val launches by container.launchStore.launches.collectAsStateWithLifecycle()
            val slots =
                remember {
                    HomeSlots(
                        drawer = { open ->
                            DrawerScreen(
                                apps = apps,
                                settings = state.settings.drawer,
                                launches = remember(launches) { launches.asMap() },
                                loadShortcuts = { withContext(Dispatchers.IO) { container.appCatalog.allShortcuts() } },
                                actions =
                                DrawerActions(
                                    onLaunch = { home.onEvent(HomeEvent.Launch(it)) },
                                    onDismiss = { home.onEvent(HomeEvent.Dismiss) },
                                    onLaunchShortcut = { home.onEvent(HomeEvent.LaunchShortcut(it)) },
                                    onRenameFolder = { home.onEvent(HomeEvent.EditDrawerFolderName(it)) },
                                    onReorderApps = { id, order -> container.settingsStore.update { it.copy(drawer = DrawerFolders.reorderApps(it.drawer, id, order)) } },
                                    onReorderFolders = { ids -> container.settingsStore.update { it.copy(drawer = DrawerFolders.reorderTabs(it.drawer, ids)) } },
                                    onSetFolderSymbol = { id, symbol -> container.settingsStore.update { it.copy(drawer = DrawerFolders.setSymbol(it.drawer, id, symbol)) } },
                                    onShowAppMenu = { home.onEvent(HomeEvent.ShowAppMenu(it, fromPanel = null, fromDrawer = true)) },
                                    onHideAppMenu = { home.onEvent(HomeEvent.HideAppMenu) },
                                    onMoveToFolder = { app, id -> home.onEvent(HomeEvent.MoveToDrawerFolder(app, id)) },
                                    onOpenSettings = {
                                        home.onEvent(HomeEvent.Dismiss)
                                        container.actions.perform(LauncherAction.OpenSettings)
                                    },
                                    onOpenStore = { query ->
                                        home.onEvent(HomeEvent.Dismiss)
                                        openPlayStore(query)
                                    },
                                ),
                                open = open,
                            )
                        },
                        overlay = { overlay ->
                            when (overlay) {
                                Overlay.HomeMenu ->
                                    HomeMenu(
                                        onAdjust = { home.onEvent(HomeEvent.Adjust(true)) },
                                        onAddWidget =
                                        if (state.layout.shape is LayoutShape.Tree) {
                                            {
                                                home.onEvent(HomeEvent.Dismiss)
                                                container.widgets.pickerFor.value = PickTarget.Free
                                            }
                                        } else {
                                            null
                                        },
                                        onEditLayout = {
                                            home.onEvent(HomeEvent.Dismiss)
                                            startActivity(Intent(this@MainActivity, EditorActivity::class.java))
                                        },
                                        onSettings = {
                                            home.onEvent(HomeEvent.Dismiss)
                                            container.actions.perform(LauncherAction.OpenSettings)
                                        },
                                        onDismiss = { home.onEvent(HomeEvent.Dismiss) },
                                    )

                                is Overlay.AddApps -> {
                                    val id = overlay.panelId.value
                                    val folder = state.folders.byPanel[id]
                                    AppPicker(
                                        title = folder?.name ?: state.layout.panel(overlay.panelId)?.label ?: "アプリを追加",
                                        apps = apps,
                                        checked = folder?.apps.orEmpty().toSet(),
                                        onToggle = { app -> home.onEvent(HomeEvent.EditFolders { FolderEdits.toggle(it, id, app) }) },
                                        onDone = { home.onEvent(HomeEvent.Dismiss) },
                                    )
                                }

                                else -> Unit
                            }
                        },
                        freeWidget = { spec, w, h -> WidgetPanel(spec.id, w, h, container.widgets, container.widgetStore) },
                        widget = { g ->
                            WidgetPanel(
                                panelId = g.panel.id.value,
                                widthDp = g.inscribed.width / resources.displayMetrics.density,
                                heightDp = g.inscribed.height / resources.displayMetrics.density,
                                controller = container.widgets,
                                store = container.widgetStore,
                            )
                        },
                        panelMenuItems = { g ->
                            val id = g.panel.id.value
                            if (g.panel.content !is PanelContent.Widget) {
                                emptyList()
                            } else {
                                buildList {
                                    add(PanelMenuItem("ウィジェットを選択") { container.widgets.pickerFor.value = PickTarget.Panel(id) })
                                    if (container.widgetStore.assignments.value.byPanel.containsKey(id)) {
                                        add(PanelMenuItem("ウィジェットを削除") { container.widgets.remove(id) })
                                    }
                                }
                            }
                        },
                    )
                }
            val pickerFor by container.widgets.pickerFor
            // 「音に合わせて動かす」がオンで、マイクの権限があるときだけ実際の音を使う
            val reactToAudio = state.settings.music.reactToAudio
            val media =
                remember(reactToAudio) {
                    val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    HomeMedia(container.media.art, if (reactToAudio && granted) container.spectrum else null)
                }
            CompositionLocalProvider(LocalIconLoader provides container.iconLoader) {
                HomeScreen(
                    state = state,
                    onEvent = home::onEvent,
                    unlockPending = container.screenEvents.unlockPending,
                    notice = container.storeNotice,
                    tilt = container.tilt,
                    onUnlockPlayed = container.screenEvents::consumeUnlock,
                    slots = slots,
                    media = media,
                    editLayout = container.layoutStore::update,
                )
                pickerFor?.let { target ->
                    WidgetPicker(container.widgets, onPick = { activity, c -> placeWidget(activity, target, c) }, onDismiss = { container.widgets.pickerFor.value = null })
                }
            }
        }
    }

    /** 選んだウィジェットを置く。自由に置くときは、許可と設定が済んでからコマ割りに書く。 */
    private fun placeWidget(
        activity: Activity,
        target: PickTarget,
        c: WidgetChoice,
    ) {
        when (target) {
            is PickTarget.Panel -> container.widgets.place(activity, target.panelId, c)

            PickTarget.Free -> {
                val id = WidgetPlacement.newId(container.layoutSource.state.value.layout)
                val provider = c.info.provider.flattenToString()
                val m = resources.displayMetrics
                container.widgets.place(activity, id, c) {
                    container.layoutStore.update { WidgetPlacement.add(it, WidgetPlacement.initial(it, id, provider, c.widthDp, c.heightDp, m.widthPixels / m.density, m.heightPixels / m.density)) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.widgets.startListening()
        container.media.start()
        // 既定のホームアプリに戻った直後は、追加したもの（PWA など）を取り直す
        container.appCatalog.refresh()
    }

    override fun onStop() {
        super.onStop()
        container.widgets.stopListening()
        container.media.stop()
        // 裏に回ったホームは、メモリが足りなくなると止められることがある。まとめて書く途中の変更をここで書く
        container.flushStores()
    }

    @Deprecated("AppWidgetHost.startAppWidgetConfigureActivityForResult が Activity の結果で返すため")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (!container.widgets.onActivityResult(this, requestCode, resultCode)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ホームボタン。開いているフォルダや一覧を閉じる
        if (intent.hasCategory(Intent.CATEGORY_HOME)) home.onEvent(HomeEvent.Home)
    }
}

/**
 * Google Play ストアを開く。[query] が空でなければ、その文字でアプリを検索する。
 * Play ストアの画面が開けなければ（無効にしているなど）、ブラウザで Play ストアの検索を開く。
 */
private fun ComponentActivity.openPlayStore(query: String) {
    val q = query.trim()
    val store = Intent(Intent.ACTION_VIEW, Uri.parse(if (q.isEmpty()) "market://search?c=apps" else "market://search?q=${Uri.encode(q)}&c=apps")).setPackage("com.android.vending")
    val web = Intent(Intent.ACTION_VIEW, Uri.parse(if (q.isEmpty()) "https://play.google.com/store/apps" else "https://play.google.com/store/search?q=${Uri.encode(q)}&c=apps"))
    if (q.isEmpty()) {
        // 空のときは検索の画面でなく、Play ストアの最初の画面を開く
        packageManager.getLaunchIntentForPackage("com.android.vending")?.let {
            startActivity(it)
            return
        }
    }
    try {
        startActivity(store)
    } catch (_: ActivityNotFoundException) {
        runCatching { startActivity(web) }
    }
}
