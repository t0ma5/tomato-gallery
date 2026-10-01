package tomato.gallery.helpers

import android.os.SystemClock

object AppLock {
    private const val GRACE_MS = 5 * 60_000L

    @Volatile
    var unlockedUntil = 0L
        private set

    fun markUnlocked() {
        unlockedUntil = SystemClock.elapsedRealtime() + GRACE_MS
    }

    fun isUnlocked(): Boolean {
        return SystemClock.elapsedRealtime() < unlockedUntil
    }
}
