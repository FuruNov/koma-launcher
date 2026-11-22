package dev.furunov.komalauncher.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * オンとオフの 2 値を切り替える項目のスイッチ。見た目だけで、押したときの処理は行の側に付ける（行のどこを押しても切り替わるように）。
 * 「表示」「非表示」のような 2 つのチップで選ぶと、どちらが今の状態か分かりにくいと利用者から指摘があった（2026-11-22）。
 */
@Composable
fun ToggleSwitch(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(44.dp)
            .background(if (on) Color(0xFFFFD54F) else Color(0xFF44444C), RoundedCornerShape(12.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(18.dp).background(Color.White, CircleShape)) }
}
