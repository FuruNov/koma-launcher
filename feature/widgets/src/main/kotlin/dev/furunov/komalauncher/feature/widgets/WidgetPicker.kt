package dev.furunov.komalauncher.feature.widgets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.furunov.komalauncher.model.apps.AppSearch
import dev.furunov.komalauncher.model.layout.BuiltinWidget
import dev.furunov.komalauncher.model.layout.WidgetFonts
import dev.furunov.komalauncher.model.widgets.WidgetCatalog
import dev.furunov.komalauncher.ui.art.clockStrings
import dev.furunov.komalauncher.ui.art.widgetFontFamily
import kotlinx.coroutines.launch
import kotlin.math.min

private val CardWidth = 156.dp
private val PreviewHeight = 112.dp

/**
 * ウィジェットの選択画面。アプリごとの見出しの下に、見本・名前・大きさ（マス数）を横に並べる。
 * 文字だけの一覧では、どのウィジェットか見分けられないと利用者から指摘があった（2026-11-22）。
 * 上の検索欄で、ウィジェットの名前かアプリの名前で絞る。
 */
@Composable
fun WidgetPicker(
    controller: WidgetHostController,
    onPick: (Activity, WidgetChoice) -> Unit,
    onDismiss: () -> Unit,
    title: String = "ウィジェットを選択",
    /** ランチャーに内蔵のウィジェット（時計・テキスト）を選んだとき。null なら内蔵のものを並べない（入れ替えのときなど）。 */
    onPickBuiltin: ((BuiltinWidget) -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val activity = context.findActivity()
    val loaded by produceState<List<WidgetChoice>?>(null) { value = controller.loadChoices() }
    val choices = loaded.orEmpty()
    val byKey = remember(choices) { choices.associateBy { it.entry.key } }
    var query by remember { mutableStateOf("") }
    val groups = remember(choices, query) { WidgetCatalog.group(choices.map { it.entry }, query) }
    Box(Modifier.fillMaxSize().background(Color(0xF7121216)).clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                BasicText(
                    "閉じる",
                    style = TextStyle(color = Color(0xFFFFD54F), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                )
            }
            Box(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF26262C), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                if (query.isEmpty()) BasicText("ウィジェットを検索", style = TextStyle(color = Color(0xFF8A8A92), fontSize = 16.sp))
                BasicTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color(0xFFFFD54F)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 見出しはアプリドロワーなどと同じく、このアプリの名前（Koma Launcher）にする
            val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
            val builtins = if (onPickBuiltin == null) emptyList() else BUILTINS.filter { (_, name) -> query.isBlank() || listOf(name, appName, "コマランチャー").any { AppSearch.match(query, it) != null } }
            if (loaded == null) {
                BasicText("読み込み中", style = TextStyle(color = Color(0xFF9A9AA2), fontSize = 14.sp), modifier = Modifier.padding(20.dp))
            } else if (groups.isEmpty() && builtins.isEmpty()) {
                BasicText("一致するウィジェットはありません", style = TextStyle(color = Color(0xFF9A9AA2), fontSize = 14.sp), modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // ランチャーに内蔵のウィジェットを先頭に並べる
                if (builtins.isNotEmpty()) {
                    item(key = "builtin") {
                        Column(Modifier.padding(top = 14.dp)) {
                            BasicText(appName, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 20.dp))
                            Row(Modifier.padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                builtins.forEach { (kind, name) -> BuiltinCard(kind, name) { onPickBuiltin?.invoke(kind) } }
                            }
                        }
                    }
                }
                items(groups, key = { it.packageName }) { g ->
                    Column(Modifier.padding(top = 14.dp)) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncDrawable(Modifier.size(28.dp), g.packageName) { controller.appIcon(g.packageName) }
                            BasicText(
                                g.appLabel,
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            items(g.widgets, key = { it.key }) { e ->
                                val c = byKey.getValue(e.key)
                                WidgetCard(c, controller) { activity?.let { onPick(it, c) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(
    c: WidgetChoice,
    controller: WidgetHostController,
    onClick: () -> Unit,
) {
    val (cw, ch) = WidgetCatalog.cells(c.widthDp, c.heightDp, c.info.targetCellWidth, c.info.targetCellHeight)
    Column(Modifier.width(CardWidth).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(bottom = 6.dp)) {
        Box(
            Modifier.width(CardWidth).size(CardWidth, PreviewHeight).background(Color(0xFF2A2A31), RoundedCornerShape(14.dp)).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) { WidgetPreview(c, controller) }
        BasicText(
            c.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
        )
        BasicText("$cw × $ch", style = TextStyle(color = Color(0xFF9A9AA2), fontSize = 12.sp), modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * 見本。アプリが見本のレイアウト（previewLayout）を持てばそれを描き、無ければ見本の画像、それも無ければアイコン。
 * レイアウトはウィジェットの大きさで作り、枠に収まるよう縮める。展開と画像の読み込みはメインスレッドの外で行う
 * （メインスレッドで行うと、選択画面を開くときとスクロールが重かった。2026-11-22 に利用者が指摘）。
 */
@Composable
private fun WidgetPreview(
    c: WidgetChoice,
    controller: WidgetHostController,
) {
    val density = LocalDensity.current
    val boxW = with(density) { (CardWidth - 16.dp).roundToPx() }
    val boxH = with(density) { (PreviewHeight - 16.dp).roundToPx() }
    val layout = c.info.previewLayout
    if (layout == 0) {
        AsyncDrawable(Modifier.fillMaxSize(), c.entry.key) { controller.previewImage(c) }
        return
    }
    val scope = rememberCoroutineScope()
    AndroidView(
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            val fallback = {
                scope.launch {
                    val d = controller.previewImage(c)
                    frame.addView(ImageView(ctx).apply { setImageDrawable(d) }, FrameLayout.LayoutParams(boxW, boxH))
                }
            }
            val w = (c.widthDp * density.density).toInt().coerceAtLeast(1)
            val h = (c.heightDp * density.density).toInt().coerceAtLeast(1)
            // ビューを裏のスレッドで作り（AsyncLayoutInflater と同じ方法）、画面に加えるのはメインスレッドで行う
            controller.previewExecutor.execute {
                val made = runCatching { RemoteViews(c.info.provider.packageName, layout).apply(ctx, frame) }.getOrNull()
                frame.post {
                    if (made == null) {
                        fallback()
                    } else {
                        val v = made
                        // 申告の最小の大きさが中身より小さいアプリがある（時計のアプリの Digital clock は、時刻と日付が重なった）。
                        // 大きさを制限せずに一度測り、中身が必要とする大きさで作ってから縮める
                        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        val vw = maxOf(w, v.measuredWidth)
                        val vh = maxOf(h, v.measuredHeight)
                        val k = min(1f, min(boxW / vw.toFloat(), boxH / vh.toFloat()))
                        v.pivotX = 0f
                        v.pivotY = 0f
                        v.scaleX = k
                        v.scaleY = k
                        v.translationX = (boxW - vw * k) / 2f
                        v.translationY = (boxH - vh * k) / 2f
                        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        frame.addView(v, FrameLayout.LayoutParams(vw, vh))
                    }
                }
            }
            frame
        },
        modifier = Modifier.size(CardWidth - 16.dp, PreviewHeight - 16.dp),
    )
}

/** [load] で読んだ画像。読み終わるまでは何も描かない。[key] が同じなら読み直さない。 */
@Composable
private fun AsyncDrawable(
    modifier: Modifier,
    key: Any,
    load: suspend () -> Drawable?,
) {
    val d by produceState<Drawable?>(null, key) { value = load() }
    d?.let { drawable ->
        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, update = { it.setImageDrawable(drawable) }, modifier = modifier)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val BUILTINS = listOf(BuiltinWidget.CLOCK to "時計", BuiltinWidget.TEXT to "テキスト")

/** 内蔵のウィジェットの見本。ホームと同じ書体で描く。 */
@Composable
private fun BuiltinCard(
    kind: BuiltinWidget,
    name: String,
    onClick: () -> Unit,
) {
    Column(Modifier.width(CardWidth).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(bottom = 6.dp)) {
        Box(Modifier.size(CardWidth, PreviewHeight).background(Color(0xFF2A2A31), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            if (kind == BuiltinWidget.CLOCK) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(clockStrings(java.time.LocalDateTime.now()).second, style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                    BasicText(clockStrings(java.time.LocalDateTime.now()).first, style = TextStyle(color = Color.White, fontSize = 44.sp, fontFamily = widgetFontFamily(WidgetFonts.DELA)))
                }
            } else {
                BasicText("KOMA", style = TextStyle(color = Color.White, fontSize = 40.sp, fontFamily = widgetFontFamily(WidgetFonts.BODONI), fontWeight = FontWeight.ExtraBold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            }
        }
        BasicText(name, style = TextStyle(color = Color.White, fontSize = 13.sp), modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
        BasicText(if (kind == BuiltinWidget.CLOCK) "見た目を変えられます" else "文字と見た目を変えられます", style = TextStyle(color = Color(0xFF9A9AA2), fontSize = 12.sp), modifier = Modifier.padding(horizontal = 4.dp))
    }
}
