package dev.furunov.komalauncher.feature.widgets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.furunov.komalauncher.model.widgets.WidgetStore

/** ウィジェットのコマの中身。置いていなければ、置き方の案内を薄く出す。 */
@Composable
fun WidgetPanel(
    panelId: String,
    widthDp: Float,
    heightDp: Float,
    controller: WidgetHostController,
    store: WidgetStore,
) {
    val assignments by store.assignments.collectAsStateWithLifecycle()
    val ref = assignments.byPanel[panelId]
    if (ref == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 白い紙のコマ（BlankPage.LIGHT）とベタのコマのどちらの上でも読める中間の灰色
            BasicText("長押しでウィジェットを配置", style = TextStyle(color = Color(0xFF8A8D96), fontSize = 13.sp))
        }
        return
    }
    LaunchedEffect(ref.appWidgetId, widthDp, heightDp) { controller.updateSize(ref.appWidgetId, widthDp, heightDp) }
    // AndroidView の factory は 1 回しか呼ばれないので、ウィジェットを入れ替えたら作り直させる
    key(ref.appWidgetId) {
        AndroidView(
            factory = { ctx ->
                val view = controller.view(ref.appWidgetId)
                // 同じビューを作り直した入れ物へ付け替えることがあるので、前の親から外してから返す
                (view?.parent as? ViewGroup)?.removeView(view)
                view ?: FrameLayout(ctx)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
