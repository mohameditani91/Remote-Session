package dev.remotesession

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Carries messages between the app and a relay. Callbacks must be delivered on the main thread. */
interface RemoteSessionTransport {
    var onText: ((String) -> Unit)?
    var onClose: ((Throwable?) -> Unit)?
    fun connect()
    fun send(text: String)
    fun send(data: ByteArray)
    fun close()

    /** Bytes handed to [send] that haven't gone out yet. Frames are dropped while this backs up. */
    val queuedBytes: Long get() = 0
}

/** The default transport: a WebSocket to the relay. Put authentication in the headers or the URL's query. */
class WebSocketTransport(private val url: String, private val headers: Map<String, String> = emptyMap()) : RemoteSessionTransport {

    override var onText: ((String) -> Unit)? = null
    override var onClose: ((Throwable?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var closed = false

    override val queuedBytes get() = socket?.queueSize() ?: 0

    override fun connect() {
        val request = Request.Builder().url(url).apply { headers.forEach(::header) }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (!closed) onText?.invoke(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { finish(null) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { finish(t) }
            }
        })
    }

    override fun send(text: String) {
        socket?.send(text)
    }

    override fun send(data: ByteArray) {
        socket?.send(data.toByteString())
    }

    override fun close() {
        closed = true
        socket?.close(1000, null)
        socket = null
    }

    private fun finish(error: Throwable?) {
        if (closed) return
        close()
        onClose?.invoke(error)
    }

    private companion object {
        val client: OkHttpClient by lazy { OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build() }
    }
}
