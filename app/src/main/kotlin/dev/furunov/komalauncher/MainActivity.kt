package dev.furunov.komalauncher

import android.content.Intent
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
import dev.furunov.komalauncher.feature.home.HomeScreen
import dev.furunov.komalauncher.feature.home.HomeSlots
import dev.furunov.komalauncher.feature.home.HomeViewModel
import dev.furunov.komalauncher.feature.home.Overlay
import dev.furunov.komalauncher.feature.home.PanelMenuItem
import dev.furunov.komalauncher.feature.widgets.WidgetPanel
import dev.furunov.komalauncher.feature.widgets.WidgetPicker
import dev.furunov.komalauncher.model.layout.PanelContent
import dev.furunov.komalauncher.model.settings.LauncherAction
import dev.furunov.komalauncher.ui.icons.LocalIconLoader

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as KomaApplication).container }

    private val home: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                HomeViewModel(container.layoutSource, container.folderStore, container.settingsStore, container.appCatalog, container.actions)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by home.state.collectAsStateWithLifecycle()
            val apps by container.appCatalog.apps.collectAsStateWithLifecycle()
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
                                        actions =
                                        DrawerActions(
                                            onLaunch = { home.onEvent(HomeEvent.Launch(it)) },
                                            onDismiss = { home.onEvent(HomeEvent.Dismiss) },
                                            onOpenSettings = {
                                                home.onEvent(HomeEvent.Dismiss)
                                                container.actions.perform(LauncherAction.OpenSettings)
                                            },
                                        ),
                                    )

                                Overlay.HomeMenu ->
                                    HomeMenu(
                                        onSettings = {
                                            home.onEvent(HomeEvent.Dismiss)
                                            container.actions.perform(LauncherAction.OpenSettings)
                                        },
                                        onDismiss = { home.onEvent(HomeEvent.Dismiss) },
                                    )

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
                                    add(PanelMenuItem("ウィジェットを選ぶ") { container.widgets.pickerFor.value = id })
                                    if (container.widgetStore.assignments.value.byPanel.containsKey(id)) {
                                        add(PanelMenuItem("ウィジェットを外す") { container.widgets.remove(id) })
                                    }
                                }
                            }
                        },
                    )
                }
            val pickerFor by container.widgets.pickerFor
            CompositionLocalProvider(LocalIconLoader provides container.iconLoader) {
                HomeScreen(
                    state = state,
                    onEvent = home::onEvent,
                    unlockPending = container.screenEvents.unlockPending,
                    onUnlockPlayed = container.screenEvents::consumeUnlock,
                    slots = slots,
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
    }

    override fun onStop() {
        super.onStop()
        container.widgets.stopListening()
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
