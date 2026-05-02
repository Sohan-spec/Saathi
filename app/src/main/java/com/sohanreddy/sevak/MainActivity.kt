package com.sohanreddy.sevak

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.navigation.SaathiNavGraph
import com.sohanreddy.sevak.ui.theme.SaathiTheme
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!isOnline()) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_LONG).show()
        }

        val prefs = PrefsManager(this)

        setContent {
            SaathiTheme {
                val navController = rememberNavController()
                SaathiNavGraph(navController = navController, prefs = prefs)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}