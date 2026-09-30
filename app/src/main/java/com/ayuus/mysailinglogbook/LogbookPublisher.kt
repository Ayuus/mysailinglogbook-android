package com.ayuus.mysailinglogbook

import android.content.Context
import com.chaquo.python.Python
import java.io.File

/** Uploads the built logbook, for MainActivity's ☁️ and syncs and for the boat-mode service. */
object LogbookPublisher {
    /**
     * Uploads [htmlFile] to the configured destination: REST (WordPress) is preferred over SFTP whenever
     * both are configured, same choice cli.py makes -- it needs no SSH key/password on this device, just
     * an Application Password. Not "REST, falling back to SFTP if REST fails": a failed upload should
     * surface as a failed upload, not silently retry a transport the owner may not have meant to use.
     * The outcome goes to [log] as "[ok]"/"[error]"/"[skip]" lines; returns whether the upload worked.
     * [onStart] is told the (translated) line saying which way it is uploading, right before it starts.
     * Blocking network I/O: call it off the main thread.
     *
     * No longer gated on wifi-vs-mobile-data: the logbook is small enough that the data cost is
     * negligible, so it always runs regardless of connection type.
     */
    fun publish(context: Context, settings: SettingsStore, htmlFile: File, log: (String) -> Unit, onStart: (String) -> Unit = {}): Boolean {
        // Found while auditing every Python.getInstance() call site for the same gap
        // BootModeService.onStartCommand()'s own fix closed ("kan dit ook op andere plaatsen
        // optreden?"): every caller of this function so far happened to have already started
        // Python earlier in the same call chain (a build/download that ran first), but nothing
        // here actually guaranteed that -- this function has its own direct Python.getInstance()
        // call below (and RestUploader.uploadLogbook()'s own, unguarded the same way, runs
        // straight after it in the same call chain), so it needs its own guard like every other
        // entry point, not a borrowed one from whichever caller happened to run first.
        PythonStarter.ensureStarted(context)
        val useRest = settings.isRestUploadConfigComplete
        if (!useRest && !settings.isSftpConfigComplete) {
            log("[skip] " + context.getString(R.string.log_upload_not_configured))
            return false
        }
        val statusText = context.getString(if (useRest) R.string.status_uploading_wordpress else R.string.status_uploading_sftp)
        log("[info] $statusText")
        onStart(statusText)
        // See SyncState.uploading: a publish is not safely resumable mid-request, so onTaskRemoved()
        // needs to know one is running. Always reset in finally, including on the failure paths.
        SyncState.uploading = true
        try {
            if (useRest) {
                // Expanded here, not stored expanded -- see SettingsActivity.kt's own comment on
                // why the field holds exactly what was typed. This is the one place that value
                // is actually used, so it's also the one place normalize_rest_upload_url() needs
                // to run.
                val url = Python.getInstance().getModule("nmea2log.upload")
                    .callAttr("normalize_rest_upload_url", settings.restUploadUrl)
                    .toString()
                RestUploader.uploadLogbook(context, url, settings.restUploadUser, settings.restUploadPassword, htmlFile)
                log("[ok] " + context.getString(R.string.log_upload_ok_wordpress, url))
            } else {
                SftpUploader.uploadLogbookAtomic(context, settings, htmlFile)
                log(
                    "[ok] " + context.getString(
                        R.string.log_upload_ok_sftp, settings.sftpUser, settings.sftpHost, settings.sftpRemotePath,
                    ),
                )
            }
        } catch (e: RestUploadError) {
            log("[error] " + context.getString(R.string.log_upload_failed_wordpress, e.message))
            return false
        } catch (e: SftpUploadError) {
            log("[error] " + context.getString(R.string.log_upload_failed_sftp, e.message))
            return false
        } finally {
            SyncState.uploading = false
        }
        return true
    }
}
