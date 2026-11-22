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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.furunov.komalauncher.model.widgets.WidgetStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

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
    // アプリに伝えて描き直された大きさ。大きさを変えている間は、描き直しが届くまで前の中身をこの大きさで作り、
    // 縦横比を保って枠に収める。枠に合わせて作り直すと、前のレイアウトが伸びて見える（2026-11-22 に利用者が指摘）
    var applied by remember(ref.appWidgetId) { mutableStateOf(widthDp to heightDp) }
    LaunchedEffect(ref.appWidgetId, widthDp, heightDp) {
        if (applied == (widthDp to heightDp)) {
            controller.updateSize(ref.appWidgetId, widthDp, heightDp)
            return@LaunchedEffect
        }
        // 指を動かしている間は伝えない（毎回伝えると、アプリの描き直しが追いつかない）
        delay(200)
        val updates = controller.updates(ref.appWidgetId)
        val before = updates?.value
        controller.updateSize(ref.appWidgetId, widthDp, heightDp)
        withTimeoutOrNull(1500) { updates?.first { it != before } }
        applied = widthDp to heightDp
    }
    val (aw, ah) = applied
    val k = min(widthDp / aw, heightDp / ah)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // AndroidView の factory は 1 回しか呼ばれないので、ウィジェットを入れ替えたら作り直させる
        key(ref.appWidgetId) {
            AndroidView(
                factory = { ctx ->
                    val view = controller.view(ref.appWidgetId)
                    // 同じビューを作り直した入れ物へ付け替えることがあるので、前の親から外してから返す
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view ?: FrameLayout(ctx)
                },
                modifier =
                Modifier.requiredSize(aw.dp, ah.dp).graphicsLayer {
                    scaleX = k
                    scaleY = k
                },
            )
        }
    }
}
