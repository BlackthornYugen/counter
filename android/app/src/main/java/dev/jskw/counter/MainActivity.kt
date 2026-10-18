package dev.jskw.counter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.jskw.counter.data.CounterRepositoryProvider
import dev.jskw.counter.ui.CounterApp
import dev.jskw.counter.ui.CounterViewModel
import dev.jskw.counter.ui.theme.CounterTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CounterViewModel by viewModels {
        CounterViewModel.Factory(CounterRepositoryProvider.getRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)
        setContent {
            CounterTheme {
                CounterApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val dataString = intent?.dataString
        if (!dataString.isNullOrBlank()) {
            viewModel.importCounterFromLink(dataString)
        }
    }
}
