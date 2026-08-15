package com.vayunmathur.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.music.R
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.MusicViewModelFactory
import com.vayunmathur.music.platform.PlaybackManager

class MainActivity : ComponentActivity() {
    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModelFactory(application, MusicRepository.get(application), PlaybackManager.getInstance(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val (permissions, message) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO) to getString(R.string.grant_audio_permissions)
                else
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) to getString(R.string.grant_storage_permissions)
                PermissionsChecker(permissions, message) {
                    Navigation(musicViewModel)
                }
            }
        }
    }
}
