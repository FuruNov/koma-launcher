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

/** ウィジェットの選択画面。アプリごとに並べる。 */
@Composable
fun WidgetPicker(
    controller: WidgetHostController,
    onPick: (Activity, WidgetChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val activity = LocalContext.current.findActivity()
    val choices = remember { controller.choices() }
    Box(Modifier.fillMaxSize().background(Color(0xF2121216))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            BasicText(
                "ウィジェットを選択",
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(20.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(choices, key = { it.info.provider.flattenToString() }) { c ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { activity?.let { onPick(it, c) } }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        BasicText(c.label, style = TextStyle(color = Color.White, fontSize = 16.sp))
                        BasicText(c.appLabel, style = TextStyle(color = Color(0xFF999999), fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
