package com.manyeyes.signaling

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber

class WsClient(private val baseWsUrl: String, private val token: String, private val deviceId: String) {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect(listener: WebSocketListener) {
        val url = "$baseWsUrl/ws?token=$token&deviceId=$deviceId"
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, listener)
    }

    fun send(text: String) { socket?.send(text) }
    fun send(bytes: ByteString) { socket?.send(bytes) }
    fun close() { socket?.close(1000, "bye"); socket = null }
}
