package com.manyeyes.signaling

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import timber.log.Timber
import org.json.JSONObject
import org.json.JSONArray

class NotificationCaptureService : NotificationListenerService() {

    companion object {
        var instance: NotificationCaptureService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Timber.i("[NotificationCapture] Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Timber.i("[NotificationCapture] Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val toDevice = SignalingForegroundService.notificationViewerId ?: return
        
        try {
            val json = formatNotification(sbn) ?: return
            val msg = JSONObject().apply {
                put("type", "NOTIFICATION_NEW")
                put("toDeviceId", toDevice)
                put("notification", json)
            }.toString()
            SignalingForegroundService.instance?.sendWsMessage(msg)
        } catch (e: Exception) {
            Timber.e(e, "[NotificationCapture] Error processing new notification")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not specifically requested to handle removals, but could be added later
    }

    fun getAllActiveNotificationsAsJson(): JSONArray {
        val arr = JSONArray()
        try {
            val sbns = activeNotifications ?: return arr
            // Sort by post time descending
            val sorted = sbns.sortedByDescending { it.postTime }
            for (sbn in sorted) {
                val json = formatNotification(sbn)
                if (json != null) arr.put(json)
            }
        } catch (e: Exception) {
            Timber.e(e, "[NotificationCapture] Error getting active notifications")
        }
        return arr
    }

    private fun formatNotification(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        
        // Skip completely empty notifications (usually system progress bars or silent services)
        if (title.isBlank() && text.isBlank()) return null
        
        return JSONObject().apply {
            put("id", sbn.id)
            put("packageName", sbn.packageName)
            put("postTime", sbn.postTime)
            put("title", title)
            put("text", text)
            put("subText", subText)
        }
    }
}
