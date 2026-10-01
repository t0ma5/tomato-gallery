package tomato.gallery.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.extensions.updateDirectoryPath
import tomato.gallery.helpers.MediaFetcher

class BootScanJob : JobService() {
    companion object {
        const val JOB_ID = 2

        fun schedule(context: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, BootScanJob::class.java))
                .setOverrideDeadline(0)
                .build()
            try {
                context.getSystemService(JobScheduler::class.java)?.schedule(info)
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        ensureBackgroundThread {
            try {
                MediaFetcher(this).getFoldersToScan().forEach {
                    updateDirectoryPath(it)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
