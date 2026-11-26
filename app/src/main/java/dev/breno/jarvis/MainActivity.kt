package dev.breno.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.breno.jarvis.ui.JarvisScreen
import dev.breno.jarvis.ui.SettingsScreen
import dev.breno.jarvis.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onOrbTapped() else viewModel.onPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                if (state.showSettings) {
                    SettingsScreen(
                        initial = state.settings,
                        discovery = state.discovery,
                        onDiscover = viewModel::discoverPc,
                        onSave = viewModel::saveSettings,
                        onCancel = viewModel::closeSettings,
                    )
                } else {
                    JarvisScreen(
                        state = state,
                        onOrbTap = ::onOrbTap,
                        onConversationModeChange = viewModel::setConversationMode,
                        onPcStatusTap = viewModel::refreshPcTools,
                        onOpenSettings = viewModel::openSettings,
                    )
                }
            }
        }
    }

    private fun onOrbTap() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onOrbTapped() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
