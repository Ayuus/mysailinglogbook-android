package com.ayuus.mysailinglogbook

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat

/**
 * Bound to by MainActivity for as long as it lives, for one reason: a service is the only thing Android tells when
 * the user swipes the app away from Recents (onTaskRemoved()) -- the Activity's own onDestroy() often never runs then,
 * because the process is killed right after (found in practice: the "Voltooid 18:32" notification of a finished run
 * stayed behind after closing the app). SyncNotificationService.onTaskRemoved() only fires while that service runs,
 * which after a finished run it no longer does.
 *
 * On closing, the notifications of the run (completion, "tap to reopen") are removed -- unless a run is still going
 * on by itself (an upload that finishes after the app is closed, see SyncNotificationService), which keeps its own.
 * The boat mode has its own service and notification and is not touched.
 */
class TaskCloseService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // A run going on by itself keeps its notifications, and so does one that closing just interrupted: its
        // "Onderbroken door sluiten" notification (SyncNotificationService.onTaskRemoved()) is meant to stay.
        if (!SyncState.inProgress && !SyncState.cancelled) {
            val notifications = NotificationManagerCompat.from(this)
            notifications.cancel(SyncNotificationService.NOTIFICATION_ID)
            notifications.cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
        }
        stopSelf()
    }
}
