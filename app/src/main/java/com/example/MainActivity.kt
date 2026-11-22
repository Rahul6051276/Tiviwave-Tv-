package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import com.example.routing.RoutingManager
import com.example.ui.HomeTvScreen
import com.example.ui.theme.TiviWaveTheme
import com.example.viewmodel.IptvViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureImmersiveTvWindow()
        configureProxyAwareImageLoader()

        setContent {
            TiviWaveTheme {
                val viewModel: IptvViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                Box(modifier = Modifier.fillMaxSize()) {
                    HomeTvScreen(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    private fun configureImmersiveTvWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun configureProxyAwareImageLoader() {
        val imageLoader = ImageLoader.Builder(applicationContext)
            .okHttpClient { RoutingManager.buildHttpClient(connectTimeoutSec = 10L, readTimeoutSec = 15L) }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)
    }
}
