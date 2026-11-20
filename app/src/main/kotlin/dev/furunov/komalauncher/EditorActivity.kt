package dev.furunov.komalauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.furunov.komalauncher.feature.editor.EditorScreen
import dev.furunov.komalauncher.feature.editor.EditorViewModel

/** コマ割りの編集画面の入れ物。中身は :feature:editor が作る。 */
class EditorActivity : ComponentActivity() {
    private val container by lazy { (application as KomaApplication).container }

    private val vm: EditorViewModel by viewModels {
        viewModelFactory { initializer { EditorViewModel(container.layoutStore) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // ホームは画面全体（システムバーの下も含む）に描くので、同じ大きさで線の位置と絵の範囲を決める
        val bounds = windowManager.currentWindowMetrics.bounds
        // 設定の色は編集の間に変わらないので、開いたときの値を使う
        val gutterColor = container.settingsStore.settings.value.appearance.gutterColor
        setContent {
            EditorScreen(
                vm = vm,
                screenWidth = bounds.width().toFloat(),
                screenHeight = bounds.height().toFloat(),
                defaultGutterColor = gutterColor,
                onClose = ::finish,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        container.flushStores()
        // 閉じるときだけ、使っていない画像を消す。画像の選択画面へ移る間（onStop が来る）に消すと、元に戻せなくなる
        if (isFinishing) vm.close()
    }
}
