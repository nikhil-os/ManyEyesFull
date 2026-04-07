package com.manyeyes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.manyeyes.ui.AppRoot
import com.manyeyes.permissions.PermissionHelper
import com.manyeyes.data.Prefs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize Timber logging so our debug/info/error logs appear in Logcat
        if (Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
        }
        val prefs = Prefs(this)
        val helper = PermissionHelper(this) {
            lifecycleScope.launch { prefs.setPermissionsGranted(true) }
        }
        // Ask once on first launch
        lifecycleScope.launch {
            prefs.permissionsGrantedFlow.collect { granted ->
                if (!granted) helper.ensureCameraMic()
            }
        }
        setContent {
            MaterialTheme { Surface { AppRoot() } }
        }
    }
}
