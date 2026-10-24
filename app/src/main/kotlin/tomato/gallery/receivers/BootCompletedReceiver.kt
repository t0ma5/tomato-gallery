package tomato.gallery.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tomato.gallery.jobs.BootScanJob
import tomato.gallery.jobs.NewPhotoFetcher

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the boot broadcast, so ignore anything else other apps send
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        NewPhotoFetcher().scheduleJob(context)
        BootScanJob.schedule(context)
    }
}
