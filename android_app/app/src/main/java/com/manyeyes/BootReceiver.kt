package com.manyeyes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.manyeyes.data.Prefs
import com.manyeyes.signaling.SignalingForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Receives boot completion broadcast and starts the SignalingForegroundService
 * so the device appears online even without opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.i("[BootReceiver] Received action: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            // Check if user has logged in before (has stored credentials)
            val prefs = Prefs(context)
            
            // Use runBlocking since BroadcastReceiver needs to complete quickly
            // but we need to read from DataStore
            val token = runBlocking { prefs.tokenFlow.first() }
            val deviceId = runBlocking { prefs.deviceIdFlow.first() }
            val baseUrl = runBlocking { prefs.baseUrlFlow.first() }
            
            if (token != null && deviceId != null && baseUrl != null) {
                Timber.i("[BootReceiver] User logged in, starting SignalingForegroundService")
                
                // Convert HTTP URL to WebSocket URL
                val baseWsRaw = baseUrl.replaceFirst("http", "ws")
                val baseWs = if (baseUrl.startsWith("https://")) {
                    baseWsRaw.replaceFirst("ws://", "wss://")
                } else {
                    baseWsRaw
                }
                
                val serviceIntent = Intent(context, SignalingForegroundService::class.java).apply {
                    putExtra("token", token)
                    putExtra("deviceId", deviceId)
                    putExtra("baseWs", baseWs)
                }
                
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Timber.i("[BootReceiver] SignalingForegroundService started successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[BootReceiver] Failed to start SignalingForegroundService")
                }
            } else {
                Timber.i("[BootReceiver] User not logged in, skipping service start")
            }
        }
    }
}
