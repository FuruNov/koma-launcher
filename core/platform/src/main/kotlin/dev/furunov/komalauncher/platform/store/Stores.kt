package dev.furunov.komalauncher.platform.store

import android.content.Context
import dev.furunov.komalauncher.model.apps.LaunchStore
import dev.furunov.komalauncher.model.apps.Launches
import dev.furunov.komalauncher.model.folders.FolderStore
import dev.furunov.komalauncher.model.folders.Folders
import dev.furunov.komalauncher.model.settings.Settings
import dev.furunov.komalauncher.model.settings.SettingsStore
import dev.furunov.komalauncher.model.widgets.WidgetAssignments
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class FileFolderStore(
    context: Context,
    scope: CoroutineScope,
) : FolderStore {
    private val store = JsonFileStore(File(context.filesDir, "folders.json"), Folders.serializer(), ::Folders, scope)
    override val folders: StateFlow<Folders> = store.value

    override fun update(transform: (Folders) -> Folders) = store.update(transform)

    fun flush() = store.flush()

    val brokenFile: File? get() = store.brokenFile
}

class FileSettingsStore(
    context: Context,
    scope: CoroutineScope,
) : SettingsStore {
    private val store = JsonFileStore(File(context.filesDir, "settings.json"), Settings.serializer(), ::Settings, scope)
    override val settings: StateFlow<Settings> = store.value

    override fun update(transform: (Settings) -> Settings) = store.update(transform)

    fun flush() = store.flush()

    val brokenFile: File? get() = store.brokenFile
}

class FileWidgetStore(
    context: Context,
    scope: CoroutineScope,
) : WidgetStore {
    private val store = JsonFileStore(File(context.filesDir, "widgets.json"), WidgetAssignments.serializer(), ::WidgetAssignments, scope)
    override val assignments: StateFlow<WidgetAssignments> = store.value

    override fun update(transform: (WidgetAssignments) -> WidgetAssignments) = store.update(transform)

    fun flush() = store.flush()

    val brokenFile: File? get() = store.brokenFile
}

class FileLaunchStore(
    context: Context,
    scope: CoroutineScope,
) : LaunchStore {
    // 起動のたびに変わるので、フォルダより長くまとめてから書く
    private val store = JsonFileStore(File(context.filesDir, "launches.json"), Launches.serializer(), ::Launches, scope, debounceMs = 2000)
    override val launches: StateFlow<Launches> = store.value

    override fun update(transform: (Launches) -> Launches) = store.update(transform)

    fun flush() = store.flush()

    val brokenFile: File? get() = store.brokenFile
}
