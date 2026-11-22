package dev.furunov.komalauncher.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 試作: 傾けている間の描画の重さを、効果ごとに測るための切り替え。debug ビルドの受け口（DEBUG_FX）だけが変える。
 * 名前: depth3d・shadow・rim・outline・textmask・texts・sheen・elevation
 */
object HomeDebug {
    var off by mutableStateOf(emptySet<String>())
}

/** 傾きで絵が動いている間 true（止まって 0.2 秒たったら false）。試作の深度の層を、動いている間だけ半分の解像度で描くのに使う。 */
internal object TiltMotion {
    var moving by mutableStateOf(false)
}
