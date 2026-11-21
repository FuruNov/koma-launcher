package dev.furunov.komalauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import dev.furunov.komalauncher.feature.widgets.WidgetPanel
import dev.furunov.komalauncher.feature.widgets.WidgetPicker
import dev.furunov.komalauncher.model.folders.FolderEdits
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.settings.LauncherAction
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
                        overlay = { overlay ->
                            when (overlay) {
                                Overlay.Drawer ->
                                    DrawerScreen(
                                        apps = apps,
                                        columns = state.settings.drawer.columns,
                                        hidden = state.settings.drawer.hidden,
                                        launches = remember(launches) { launches.asMap() },
                                        loadShortcuts = { withContext(Dispatchers.IO) { container.appCatalog.allShortcuts() } },
                                        actions =
                                        DrawerActions(
                                            onLaunch = { home.onEvent(HomeEvent.Launch(it)) },
                                            onDismiss = { home.onEvent(HomeEvent.Dismiss) },
                                            onLaunchShortcut = { home.onEvent(HomeEvent.LaunchShortcut(it)) },
                                            onOpenSettings = {
                                                home.onEvent(HomeEvent.Dismiss)
                                                container.actions.perform(LauncherAction.OpenSettings)
                                            },
                                        ),
                                    )

                                Overlay.HomeMenu ->
                                    HomeMenu(
                                        onAdjust = { home.onEvent(HomeEvent.Adjust(true)) },
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
                                    add(PanelMenuItem("ウィジェットを選択") { container.widgets.pickerFor.value = id })
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
                pickerFor?.let { panelId ->
                    WidgetPicker(panelId, container.widgets, onDismiss = { container.widgets.pickerFor.value = null })
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
