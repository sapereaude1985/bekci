package com.bekci.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notif.channels(this)
        if (Prefs.code(this) == null) Prefs.p(this).edit().putString("code", Pairing.newCode()).apply()
        setContent { BekciTheme { HomeScreen() } }
    }
}
