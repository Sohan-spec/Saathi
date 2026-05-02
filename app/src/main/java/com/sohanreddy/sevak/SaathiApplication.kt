package com.sohanreddy.sevak

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.sohanreddy.sevak.data.rag.EmbeddingManager
import com.sohanreddy.sevak.data.rag.VectorStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SaathiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        val appCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }

        // Initialize vector store (loads persisted chunks from disk)
        try {
            VectorStoreManager.init(this)
            Log.d("SaathiApp", "VectorStoreManager initialized")
        } catch (e: Exception) {
            Log.e("SaathiApp", "VectorStoreManager init failed: ${e.message}", e)
        }

        // Initialize EmbeddingManager on background thread (takes 1-2 seconds)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EmbeddingManager.init(this@SaathiApplication)
                Log.d("SaathiApp", "EmbeddingManager initialized")
            } catch (e: Exception) {
                Log.e("SaathiApp", "EmbeddingManager init failed: ${e.message}", e)
            }
        }
    }
}
