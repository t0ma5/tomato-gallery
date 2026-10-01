package tomato.gallery.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simplemobiletools.commons.helpers.REFRESH_PATH
import tomato.gallery.extensions.addPathToDB
import tomato.gallery.extensions.config

class RefreshMediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra(REFRESH_PATH) ?: return
        if (path.isEmpty() || context.config.isPathInProtectedFolder(path)) {
            return
        }
        context.addPathToDB(path)
    }
}
