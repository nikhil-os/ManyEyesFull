package com.manyeyes.signaling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.manyeyes.MainActivity
import com.manyeyes.streaming.StreamForegroundService
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber

class SignalingForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

    val token = intent?.getStringExtra("token") ?: run { Timber.e("[Signaling] missing token extra"); return START_STICKY }
    val deviceId = intent.getStringExtra("deviceId") ?: run { Timber.e("[Signaling] missing deviceId extra"); return START_STICKY }
    val baseWs = intent.getStringExtra("baseWs") ?: run { Timber.e("[Signaling] missing baseWs extra"); return START_STICKY }
    Timber.i("[Signaling] Starting with deviceId=$deviceId url=$baseWs")

            val client = WsClient(baseWs, token, deviceId)
        client.connect(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("Signaling connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("[Signaling] RX: $text")
                try {
                    val j = JSONObject(text)
                    val msgType = j.optString("type")
                    val toId = j.optString("toDeviceId", "")
                    val fromId = j.optString("fromDeviceId", "")
                    // Only act on messages targeted to this device
                    if (toId.isNotEmpty() && toId != deviceId) {
                        Timber.d("[Signaling] Ignoring $msgType not targeted to this device (to=$toId, me=$deviceId)")
                        return
                    }
                    when (msgType) {
                        "REQUEST_STREAM" -> {
                            Timber.i("[Signaling] REQUEST_STREAM from=$fromId to=$toId -> starting StreamForegroundService")
                            // Show a heads-up notification so user can confirm the incoming share
                            showIncomingRequestNotification(fromId)
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("role", "streamer")
                            svc.putExtra("remoteDeviceId", fromId)
                            svc.putExtra("token", token)
                            svc.putExtra("deviceId", deviceId)
                            svc.putExtra("baseWs", baseWs)
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed to start StreamForegroundService")
                            }
                        }
                        "ANSWER" -> {
                            Timber.d("[Signaling] ANSWER received from=$fromId")
                            // TODO: forward ANSWER to StreamForegroundService via local broadcast/binder
                        }
                        "ICE" -> {
                            Timber.d("[Signaling] ICE received from=$fromId")
                            // TODO: forward ICE candidate to active WebRTC peer
                        }
                        else -> Timber.d("[Signaling] Unknown type: $msgType")
                    }
                } catch (t: Throwable) {
                    Timber.e(t, "[Signaling] onMessage parse error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Signaling failed")
            }
        })

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ManyEyes Ready")
            .setContentText("Listening for monitor requests")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel(): String {
        val id = "manyeyes_signal_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(id, "Signaling", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Foreground service for signaling"
                ch.enableVibration(false)
                ch.enableLights(false)
                ch.lightColor = Color.BLUE
                nm.createNotificationChannel(ch)
            }
        }
        return id
    }

    private fun showIncomingRequestNotification(fromDeviceId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val text = "Camera/Mic requested by $fromDeviceId"
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Monitor Request")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    companion object { private const val NOTIF_ID = 2001 }
}
