package com.tvhome.launcher

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask once for access to Play Next + app rows. Home reloads by itself when the prompt closes.
        if (!BuildConfig.IS_PHONE && checkSelfPermission(EPG_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(EPG_PERMISSION), 1)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val repo = remember { Repository(applicationContext) }
                val prefs = remember { Prefs(applicationContext) }
                HomeScreen(repo, prefs)
            }
        }
    }
}
