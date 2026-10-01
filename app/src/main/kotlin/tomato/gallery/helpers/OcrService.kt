package tomato.gallery.helpers

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.simplemobiletools.commons.helpers.ensureBackgroundThread

class OcrService : Service() {
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what != MSG_RECOGNIZE) {
            return@Handler false
        }
        val path = msg.data?.getString(KEY_PATH).orEmpty()
        val replyTo = msg.replyTo
        ensureBackgroundThread {
            val outcome = try {
                PhotoOcr.recognize(applicationContext, path)
            } catch (_: Exception) {
                PhotoOcrResult.Failed
            }
            val reply = Message.obtain(null, MSG_RESULT)
            reply.data = Bundle().apply {
                when (outcome) {
                    is PhotoOcrResult.Text -> {
                        putInt(KEY_KIND, KIND_TEXT)
                        putString(KEY_TEXT, outcome.text)
                    }
                    PhotoOcrResult.Empty -> putInt(KEY_KIND, KIND_EMPTY)
                    PhotoOcrResult.Failed -> putInt(KEY_KIND, KIND_FAILED)
                }
            }
            try {
                replyTo?.send(reply)
            } catch (_: RemoteException) {
            }
            Handler(Looper.getMainLooper()).post {
                stopSelf()
                Process.killProcess(Process.myPid())
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = incoming.binder

    companion object {
        private const val MSG_RECOGNIZE = 1
        private const val MSG_RESULT = 2
        private const val KEY_PATH = "path"
        private const val KEY_KIND = "kind"
        private const val KEY_TEXT = "text"
        private const val KIND_TEXT = 1
        private const val KIND_EMPTY = 2
        private const val KIND_FAILED = 3
        private const val TIMEOUT_MS = 60_000L

        fun recognize(context: Context, path: String, onResult: (PhotoOcrResult) -> Unit) {
            val app = context.applicationContext
            var delivered = false
            lateinit var connection: ServiceConnection
            val replyHandler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                if (delivered) {
                    return@Runnable
                }
                delivered = true
                onResult(PhotoOcrResult.Failed)
                runCatching { app.unbindService(connection) }
            }
            val finish: (PhotoOcrResult) -> Unit = { result ->
                if (!delivered) {
                    delivered = true
                    replyHandler.removeCallbacks(timeout)
                    onResult(result)
                    runCatching { app.unbindService(connection) }
                }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val outgoing = Message.obtain(null, MSG_RECOGNIZE)
                    outgoing.replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
                        if (msg.what == MSG_RESULT) {
                            val result = when (msg.data?.getInt(KEY_KIND)) {
                                KIND_TEXT -> PhotoOcrResult.Text(msg.data.getString(KEY_TEXT).orEmpty())
                                KIND_EMPTY -> PhotoOcrResult.Empty
                                else -> PhotoOcrResult.Failed
                            }
                            finish(result)
                        }
                        true
                    })
                    outgoing.data = Bundle().apply { putString(KEY_PATH, path) }
                    try {
                        Messenger(service).send(outgoing)
                    } catch (_: RemoteException) {
                        finish(PhotoOcrResult.Failed)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            replyHandler.postDelayed(timeout, TIMEOUT_MS)
            val bound = runCatching {
                app.bindService(Intent(app, OcrService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) {
                finish(PhotoOcrResult.Failed)
            }
        }
    }
}
