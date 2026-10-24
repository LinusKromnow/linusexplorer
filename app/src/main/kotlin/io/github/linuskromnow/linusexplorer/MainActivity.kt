package io.github.linuskromnow.linusexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserScreen
import io.github.linuskromnow.linusexplorer.ui.theme.LinusExplorerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LinusExplorerTheme {
                BrowserScreen()
            }
        }
    }
}
