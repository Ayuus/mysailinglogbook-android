package com.ayuus.mysailinglogbook

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.ConsoleMessage
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.security.Security

/**
 * The full download flow: hotspot detection, download from the W2K-2, decode+build the logbook,
 * show it in-app, then (only if the owner filled in the "Publish to ayuus.com" settings) publish
 * it via REST or SFTP -- see RestUploader/SftpUploader. Runs automatically once per app
 * launch (see onCreate()'s own savedInstanceState check) and via the manual download button.
 * Boat mode (see BootModeService) covers periodic background work with no app open at all.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logList: RecyclerView
    private lateinit var logAdapter: LogAdapter
    private lateinit var webView: WebView
    private lateinit var downloadButton: Button
    private lateinit var buildButton: Button
    private lateinit var importButton: Button
    private lateinit var publishButton: Button
    private lateinit var bootButton: Button
    private lateinit var settingsStore: SettingsStore

    // The pulsing animator currently running on a button, one entry per button that has one --
    // see setBusyAppearance(). A plain map, not a per-button field, since only the small, fixed
    // set of buttons that can ever be a run's own cancel button (download/build/publish) use this.
    private val busyAnimators = mutableMapOf<Button, ObjectAnimator>()
    private lateinit var progressLabel: TextView
    private lateinit var progressBar: ProgressBar

    // Held for the whole length of a manual download/build/publish's own background Thread (see
    // acquireManualRunWakeLock()) -- boat mode already holds one of its own (BootModeService's
    // WakeLockedExecutor); a manual run had none at all, only SyncNotificationService's foreground
    // service, which keeps the process from being killed but does not by itself stop Android from
    // starving a background Thread of CPU once the screen goes off (asked for explicitly, found in
    // practice: a real decode on a slow device crawled from ~4 seconds/file with the screen on to
    // 3-13 *minutes* between files with it off, the log's own timestamps proving it wasn't stuck,
    // just starved).
    private var manualRunWakeLock: PowerManager.WakeLock? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // The system's own folder picker (Storage Access Framework): surfaces internal storage, any
    // mounted SD card and any mounted USB drive alike, whichever the OS itself exposes -- no
    // separate "browse USB" path needed. A plain StartActivityForResult, not the narrower
    // OpenDocumentTree contract (asked for explicitly): importButton's own onClick needs to hand
    // in a StorageVolume's own createOpenDocumentTreeIntent() when exactly one is attached, to
    // jump straight into it instead of the picker's usual "This device" starting point, which
    // OpenDocumentTree's fixed launch(Uri?) input has no way to express. No result/a cancelled
    // picker means the user backed out.
    private val importFolderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val treeUri = result.data?.data
            if (result.resultCode == RESULT_OK && treeUri != null) importFromRemovableMedia(treeUri)
        }

    // Held only so Android calls TaskCloseService.onTaskRemoved() when the app is swiped away (the process is
    // often killed without onDestroy() running): it removes the notifications of a finished run.
    private val taskCloseConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    private var taskCloseBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskCloseBound = bindService(Intent(this, TaskCloseService::class.java), taskCloseConnection, BIND_AUTO_CREATE)
        // Replaces the theme's own (now-removed) android:statusBarColor -- deprecated as of
        // Android 15's enforced edge-to-edge, flagged directly by Play Console's pre-launch
        // report. Draws transparent system bars across every supported API level; the existing
        // WindowInsetsCompat listener below (unchanged) is what actually keeps content clear of
        // them, same as before.
        enableEdgeToEdge()

        // Without this, sshj (used once Milestone B adds SFTP publishing) can't do Ed25519 key
        // operations on Android -- see spike 4 in docs/android-app-plan.md for the full story.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        // Found in practice: the app sometimes closed immediately on launch with no visible error
        // at all -- SettingsStore's EncryptedSharedPreferences relies on the Android Keystore,
        // which can transiently fail (e.g. right after boot, or in certain lock states). Without a
        // real crash log yet to pin down the exact failure, this at least turns a silent crash
        // (nothing ever got past this point before) into a visible, retryable message instead.
        val store = try {
            SettingsStore(this)
        } catch (e: Exception) {
            setContentView(
                TextView(this).apply {
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    text = getString(R.string.error_settings_load_failed, e.toString())
                    setPadding(padding, padding, padding, padding)
                }
            )
            return
        }
        settingsStore = store
        ensureNotificationPermission()

        // Belt-and-suspenders on top of onDestroy()'s/SyncNotificationService.onTaskRemoved()'s
        // own cleanup -- found in practice, a real gap: this device's launcher "Alles sluiten"
        // (close all recent apps) kills the process directly, which skips every in-process
        // lifecycle callback entirely (no onDestroy(), no onTaskRemoved() -- neither can run once
        // the process is already gone), so a leftover "W2K-2 niet gevonden"/completion
        // notification from before survived indefinitely across that specific close path. There's
        // no way to intercept a hard process kill from inside the app, so this is the next best
        // guarantee: whatever's stale gets cleared the moment the app is next opened, rather than
        // sitting there forever. Only when nothing is in progress -- a genuinely still-running
        // download's own notification must survive a fresh Activity instance being created on top
        // of it (e.g. a process restart while a download is still alive), same guard as the other two.
        if (!SyncState.inProgress) {
            NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
            NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
        }

        val padding = (16 * resources.displayMetrics.density).toInt()

        // Icon buttons (asked for explicitly): download + publish top-left, settings top-right --
        // every one a real Material vector icon (see each one's own comment below, and
        // iconButton()'s own doc comment for the emoji-button option they all moved away from).
        // Standard Material "download" glyph (ic_download_24), not the ↺ emoji it replaced --
        // asked for explicitly, alongside renaming "Synchroniseren" to "Downloaden" throughout:
        // the button downloads new .ebl files from the W2K-2, "sync" was never quite the right
        // word for that one-directional flow. Tapping it while a download (or offline build) is
        // already running cancels it instead of starting a new one -- see cancelSyncStayInApp()'s
        // own doc comment for why.
        downloadButton = iconButton(getString(R.string.tooltip_sync), iconRes = R.drawable.ic_download_24) {
            if (SyncState.inProgress) cancelSyncStayInApp() else runDownload()
        }
        // A second way to get .ebl files onto the device besides downloadButton's own W2K-2 download
        // (asked for explicitly): picks a folder from an SD card or USB drive via the system's own
        // document picker, copies whatever .ebl files it finds anywhere in there (any nesting --
        // SD/USB layouts don't have to match Actisense's own folder structure) into the app's own
        // Actisense folder, then builds/publishes exactly like a normal download would. Placed
        // right next to downloadButton (asked for explicitly): this is a download too in the end, just
        // from SD/USB instead of the W2K-2.
        importButton = iconButton(getString(R.string.tooltip_import), iconRes = R.drawable.ic_folder_download_24) {
            if (SyncState.inProgress) return@iconButton
            if (bootModeBusy()) return@iconButton
            // Switches away from a currently-shown logbook right away, on the tap itself (asked
            // for explicitly) -- not only once a folder is actually picked (see
            // importFromRemovableMedia()'s own matching reset): every other toolbar action that
            // can produce a log line already does this at the moment it's tapped, so this one
            // logging silently behind an still-visible logbook (the "no media"/"no .ebl files"
            // outcomes especially -- neither of those ever reaches importFromRemovableMedia() at
            // all) was the odd one out.
            showingLocalLogbook = false
            setLogExpanded(true)
            // Checked before ever opening the system picker (asked for explicitly): with nothing
            // removable attached, that picker only ever offers internal folders, which can never
            // hold anything an import needs -- a log line here is more honest about why than
            // making the owner navigate a picker just to find that out for themselves.
            val volumes = removableStorageVolumes()
            if (volumes.isEmpty()) {
                handleLogLine("[info] " + getString(R.string.log_import_no_media))
                return@iconButton
            }
            // With exactly one SD card or USB drive attached, jump the picker straight into its
            // own root (asked for explicitly: "kun je die dan meteen openen?") instead of its
            // usual "This device" starting point -- the owner still has to tap the system's own
            // one-time "Allow access" confirmation (Android itself never skips that, no way around
            // it), but no longer has to navigate there by hand first. Two or more attached at once
            // falls back to the plain picker instead of guessing which one is meant.
            val intent = if (volumes.size == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                volumes.single().createOpenDocumentTreeIntent()
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            }
            importFolderLauncher.launch(intent)
        }
        // Dedicated, always-enabled build button (ic_refresh_24 -- asked for explicitly, replacing
        // the earlier chip/processor glyph; a list-icon option was tried first but sat too close
        // to viewLocalButton's own document icon right next to it) -- decodes and builds the
        // logbook from whatever .ebl files are already on the device, no W2K-2 or publish
        // settings needed. Split out from ☁️ (asked for explicitly, after first trying ☁️ itself
        // switching modes): the download button only ever fetches, so a separate, always-present
        // way to (re)build from what's already local reads clearer than one button quietly
        // changing what it does depending on Instellingen.
        buildButton = iconButton(getString(R.string.tooltip_build_local), iconRes = R.drawable.ic_refresh_24) {
            // While its own run is in progress this is the (only enabled) cancel button, see
            // updatePublishButtonEnabled().
            if (SyncState.inProgress) {
                cancelSyncStayInApp()
            } else if (logbookShownAsRunResult) {
                // Asked for explicitly: with a run's own result showing over a small strip of the log,
                // this tap only brings the log back (a run takes minutes, and tapping here to read the
                // log must not also start one); the next tap, with the log already showing, assembles.
                showingLocalLogbook = false
                setLogExpanded(true)
            } else {
                runOfflineBuild()
            }
        }
        // Material's own "upload" icon (ic_upload_24), not the ☁️ emoji it replaced -- asked for
        // explicitly, found in practice: a plain cloud alone didn't read as obviously "publish"
        // as a real, recognized icon does. Back to plain disabled-until-configured (see
        // updatePublishButtonEnabled()) now that buildButton above covers the "no publish
        // settings yet, but still want to build" case on its own.
        publishButton = iconButton(getString(R.string.tooltip_publish), iconRes = R.drawable.ic_upload_24) {
            if (SyncState.inProgress) cancelSyncStayInApp() else runPublish()
        }
        // Loads whatever logbook.html is already on the phone into the WebView, without
        // downloading or publishing anything -- asked for explicitly, for when the owner just wants to check
        // the already-built logbook (e.g. after switching "Automatisch publiceren na bouwen" off
        // in Instellingen) without that also sending it to ayuus.com. Material's "article" icon
        // (ic_article_24), not the 📖 emoji it replaced -- asked for explicitly, found in
        // practice: an open book read as too old-fashioned.
        val viewLocalButton = iconButton(getString(R.string.tooltip_view_local), iconRes = R.drawable.ic_article_24) {
            viewLocalLogbook()
        }
        // Boat mode on/off (see BootModeController): rounds while the W2K-2 is reachable, a final
        // round in the harbour. Runs on a simulation for now (FakeBootModeExecutor), in BootModeService.
        bootButton = iconButton(getString(R.string.tooltip_boat_mode), iconRes = R.drawable.ic_sailboat_24) {
            toggleBootMode()
        }
        // Material's own "settings" icon (ic_settings_24), not the ⚙ emoji it replaced -- asked
        // for explicitly, so every toolbar icon is a real Material vector, uniformly.
        val settingsButton = iconButton(getString(R.string.tooltip_settings), iconRes = R.drawable.ic_settings_24) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        // No standalone toolbar close button (removed -- asked for explicitly, found in
        // practice: unclear what tapping it actually did, since it both cancelled a running
        // download and left the app entirely in one tap). The back button/swiping away from
        // Recents cover "actually leave" on their own (see onDestroy()/onTaskRemoved(), both of
        // which already stop a running download and post the same "tap to reopen" notification
        // closeAppAndCancelSync() does -- that function itself stays, still used by the
        // offline/close dialog's own "App sluiten" button, see showOfflineOrCloseDialog()), and
        // the download button now separately covers "cancel without leaving" (see
        // cancelSyncStayInApp()).

        // Shows the exact same "[info]"/"[ok]"/"[skip]"/"[warning]" lines the desktop CLI prints
        // (see log.py's set_log_sink(), wired up in android_entry.py) -- the only progress/status
        // surface left in the app itself (asked for explicitly: a separate one-line statusView
        // banner used to sit above this, but it kept ending up saying much the same thing as
        // whatever the log already showed right below it).
        // One row per line (see LogAdapter/LogBuffer): only the rows in sight are drawn, so the whole
        // log stays scrollable however long a run gets.
        logAdapter = LogAdapter(11f, (1 * resources.displayMetrics.density).toInt(), LOG_ERROR_COLOR, LOG_WARNING_COLOR)
        logList = (layoutInflater.inflate(R.layout.log_list, null) as RecyclerView).apply {
            setPadding(0, padding / 2, 0, padding / 2)
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            itemAnimator = null
        }
        logAdapter.sync()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true // the logbook's own trip map (Leaflet) needs this
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("LogbookWebView", "${msg.messageLevel()} ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
        }

        // Bottom progress bar (asked for explicitly): a visual bar reads faster at a glance than
        // scanning the log's own text for the current "x/y" count, and shows the phase
        // (downloading vs. decoding) as its own label rather than folding it into a longer
        // sentence -- see updateProgressBar(), fed from the exact same report()/handleProgress()
        // signals the notification already uses. Hidden (not just empty) whenever nothing is
        // running, rather than sitting there at 0/0.
        progressLabel = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            visibility = View.GONE
        }

        // Download + publish + view-local icons top-left, settings top-right (asked for
        // explicitly) -- a weight-1 empty spacer pushes settingsButton to the far right without
        // needing a second, nested layout.
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Off by default a horizontal LinearLayout aligns children on their text baseline --
            // was needed while these buttons still mixed plain-icon (no text at all) and
            // plain-emoji-text ones, which drifted a few pixels apart on their own baselines
            // (found in practice); left in place now that every button is a plain-icon one, since
            // centering vertically is at least as correct and one less thing to revisit if a
            // future button goes back to a plain-emoji label.
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            addView(downloadButton)
            addView(importButton)
            addView(buildButton)
            addView(publishButton)
            addView(viewLocalButton)
            addView(bootButton)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(settingsButton)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(buttonRow)
            addView(logList)
            addView(webView)
            addView(progressLabel)
            addView(progressBar)
        }
        setLogExpanded(true) // nothing in the WebView yet, so the log might as well use the space
        // Edge-to-edge drawing means the top of the layout would otherwise sit under the status
        // bar (found in practice during spike 2) -- pad by the system bars' own inset instead of
        // a fixed guess.
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(layout)
        indexExistingEblFilesForPcOnce()
        cleanUpOrphanedImportStagingDirs()
        updatePublishButtonEnabled()
        updateBootButton()
        updateSyncButtonAvailability()

        // The logbook page's own popups (Details/Opmerkingen/Overzicht -- all plain HTML
        // <dialog> elements inside the WebView) are invisible to the system back button by
        // default -- without this, pressing back while one was open closed the whole app instead
        // of just the popup (found in practice, asked for explicitly to fix): a single-Activity
        // app with no fragment back stack falls straight through to finishing the Activity
        // otherwise. Checks the page itself via JS (not some Kotlin-side "is a dialog open" flag
        // that would need to be kept in sync with every popup this page ever adds), so this stays
        // correct regardless of which dialog -- or none -- happens to be open.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // window.__handleBackPress (defined in the logbook's own page script) closes
                // whichever dialog is open, or -- if none is, but a trip was just opened from the
                // Overzicht map -- reopens Overzicht instead, and reports back whether it handled
                // anything. Falls back to the plain "any dialog open" check for an older cached
                // logbook.html that predates that function (e.g. reopened without a fresh sync).
                webView.evaluateJavascript(
                    "typeof window.__handleBackPress === 'function' ? window.__handleBackPress() " +
                        ": (function(d){ if (d) d.close(); return !!d; })(document.querySelector('dialog[open]'))"
                ) { handled ->
                    Log.d("LogbookBack", "handled=$handled")
                    if (handled == "true") {
                        // Already handled entirely in JS above.
                    } else {
                        // Falls through to whatever back would otherwise have done (finishing the
                        // Activity, same as before this callback existed) -- disabling this
                        // callback first, rather than calling finish() directly here, so that
                        // "otherwise" stays correct even if a later change ever adds another
                        // callback of its own instead of relying on the plain system default.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        // Auto-start on a genuinely fresh launch, not on every onCreate() -- asked for explicitly:
        // opening the app should try to reach the W2K-2 right away instead of waiting for a manual
        // tap, with the not-found/failure case still handled the same way a manual attempt's
        // failure is (see showRetryOrCloseDialog()). savedInstanceState == null is what actually
        // distinguishes the two cases: non-null for a screen rotation (this exact session/Activity
        // being restored, must not silently kick off a second sync on top of -- or right after --
        // whatever the first one already did), null for a real fresh start.
        //
        // A previous version instead gated this on a custom "already auto-started once this
        // process" flag (SyncState.autoStartedThisProcess), which assumed a "restart" always means
        // a new OS process -- found in practice not reliably true: closing and reopening the app
        // (e.g. to un-stick a frozen sync, see onDestroy()'s own notes on OS-level freezes) can
        // leave the same process alive underneath a brand new Activity, which left that flag stuck
        // "already done" and silently disabled auto-start until the user noticed and tapped 🔄
        // themselves.
        if (savedInstanceState == null && BootModeStateStore(this).isActive) {
            // The boat mode is running in its service (the app was closed and is opened again, e.g. from
            // its notification): show what it is doing, and leave the rounds to it -- no download of our
            // own. Logged explicitly, every fresh open, not left to be inferred from the state machine's
            // own status lines (which only fire on its own phase changes, not on the app simply reopening) --
            // asked for explicitly, so it is never in doubt whether the mode is still on.
            showBootModeLog()
            handleLogLine("[info] " + getString(R.string.boat_log_enabled))
            sendBootAction(BootModeService.ACTION_RESUME)
        } else if (savedInstanceState == null && !SyncState.inProgress && shouldAutoStartBootMode()) {
            // "Start automatically" (Instellingen): opened at the boat, hotspot on -- the mode finds the
            // W2K-2 and does the first round itself, so no sync of our own on top of it either.
            showBootModeLog()
            startBootMode()
        } else if (savedInstanceState == null) {
            // Asked for explicitly: opt-out via Instellingen ("Automatisch downloaden bij
            // starten") for whoever doesn't want opening the app to try reaching the W2K-2 on its
            // own -- a manual tap on the download button still works exactly the same either way.
            if (settingsStore.autoSyncOnLaunch) {
                autoStartSyncWithSettingsRetry()
            } else {
                // Shows whatever's already on the phone right away (asked for explicitly) --
                // exactly what tapping 📖 itself does, not a separate code path of its own. Without
                // this, the log's own placeholder text would sit there doing nothing until the
                // owner tapped something (📖, or the download button to download anyway) --
                // onResume(), called right
                // after this either way, only loads the file into the WebView underneath; it
                // doesn't also switch to 📖's fully-covering layout the way this does.
                viewLocalLogbook()
            }
        } else if (SyncState.inProgress) {
            // The savedInstanceState != null branch above skips autoStartSyncWithSettingsRetry()
            // entirely -- including its own guard -- so a brand new Activity instance recreated
            // WITH saved state (e.g. this device's "Freecess" background-process freezer thawing
            // it back out after unlocking the screen, see the isW2k2ConfigComplete doc comment
            // above for the same underlying OS behavior) needs the same live-state restore.
            restoreLiveSyncUi()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (taskCloseBound) {
            unbindService(taskCloseConnection)
            taskCloseBound = false
        }
        // isChangingConfigurations is true for a rotation (this Activity instance is about to be
        // recreated immediately) -- only a genuine close (finish(), or the task being swiped away
        // from Recents) should stop an in-progress sync.
        if (!isChangingConfigurations) {
            SyncState.cancelled = true
            // Stopped here directly, not left to the background Thread's own finally block (see
            // runDownload()) -- that block only runs once the Python side notices isCancelled() and
            // unwinds, which can take a while if it's currently blocked inside a single blocking
            // HTTP call (login, folder listing, a whole file's download) with no cancellation
            // check until that call returns (found in practice: the notification stayed on screen
            // for a while after closing the app, asked for explicitly to fix). Stopping the
            // service immediately removes the notification right away regardless of how long the
            // sync itself takes to actually wind down in the background; the Thread's own
            // stopService() call later is a harmless no-op against an already-stopped service.
            SyncNotificationService.stop(this)
            if (!SyncState.inProgress) {
                // Nothing running -- SyncNotificationService.onTaskRemoved()'s own cleanup only
                // fires for a service that's actually running, but autoStartSyncWithSettingsRetry()'s
                // cheap pre-check posts "W2K-2 niet gevonden" straight via NotificationManagerCompat
                // without ever starting the service at all, so that notification had no way to be
                // cleared by swiping the app away (found in practice, asked for explicitly: it just
                // sat there indefinitely). onDestroy() fires regardless of whether the service was
                // ever started, so it covers that gap onTaskRemoved() structurally can't.
                NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
                NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
            }
        }
    }

    /** Restores full live download state (log, progress bar) from SyncState's own cached fields
     * (see there) -- called whenever this Activity instance becomes the active one while a
     * download is already known to be in progress, instead of showing a static, never-updating
     * placeholder (found in practice, a real bug -- see SyncState.active's own doc comment).
     * Harmless to call even when nothing has been cached yet (a download that's only just
     * started, before its very first progress update reached SyncState). */
    private fun restoreLiveSyncUi() {
        // Same "don't yank the scroll position" check refreshLogView() already applies to every
        // other incoming line (see isLogScrolledToBottom()'s own doc comment) -- found in
        // practice, a real bug: this call site had its own unconditional fullScroll() instead,
        // so reopening the app while reading back through an earlier part of a long-running
        // import's log threw that reading position away every single time, not just when a
        // fresh line happened to arrive.
        val wasAtBottom = isLogScrolledToBottom()
        logAdapter.sync()
        if (wasAtBottom) scrollLogToEnd()
        val phase = SyncState.lastProgressPhase
        if (phase != null && SyncState.lastProgressTotal > 0) {
            progressBar.visibility = View.VISIBLE
            progressLabel.visibility = View.VISIBLE
            progressBar.max = SyncState.lastProgressTotal
            progressBar.progress = SyncState.lastProgressCurrent
            progressLabel.text = getString(
                R.string.progress_label_format, phase, SyncState.lastProgressCurrent, SyncState.lastProgressTotal,
            )
        } else {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
        }
        updatePublishButtonEnabled()
    }

    /** Puts the log over the logbook, with what the boat mode has reported so far: the running log
     * text while this process has one, else the end of the log file (a restarted process starts empty). */
    private fun showBootModeLog() {
        showingLocalLogbook = false
        setLogExpanded(true)
        if (LogBuffer.isEmpty()) {
            val logFile = LogFile.file(this)
            if (logFile.exists()) {
                LogBuffer.replaceAll(logFile.readLines(Charsets.UTF_8).takeLast(BOOT_LOG_TAIL_LINES))
            }
        }
        refreshLogView()
    }

    /** Starts or stops the boat mode. It runs in BootModeService, so it goes on with the app in the
     * background; whether it is on is what the service persisted (BootModeStateStore). */
    private fun toggleBootMode() {
        // The mode reports through the log, so bring it back over the logbook -- like a sync or build
        // does when it starts (see runDownload()); otherwise its lines land in a log nobody can see.
        showingLocalLogbook = false
        setLogExpanded(true)
        if (BootModeStateStore(this).isActive) sendBootAction(BootModeService.ACTION_STOP) else startBootMode()
    }

    /** Whether opening the app should start the boat mode by itself: the setting is on, the W2K-2
     * credentials are filled in, this device's own hotspot (the W2K-2 joins it) is up right now, and the
     * user has not switched the mode off themselves since the hotspot last came up. With the hotspot off
     * that last condition is reset, so the next visit to the boat starts it again. */
    private fun shouldAutoStartBootMode(): Boolean {
        val store = BootModeStateStore(this)
        if (!HotspotDetector.isHotspotUp()) {
            store.userStopped = false
            return false
        }
        return settingsStore.bootAutoStart && settingsStore.isW2k2ConfigComplete && !store.userStopped
    }

    private fun startBootMode() {
        sendBootAction(BootModeService.ACTION_START)
        askBatteryOptimisationExemptionOnce()
    }

    private fun sendBootAction(action: String) {
        try {
            BootModeService.send(this, action)
        } catch (e: Exception) {
            handleLogLine("[error] ${e.message}")
        }
    }

    /** With the phone lying still in port, Android's Doze can hold back the mode's alarms and network
     * access; an app that is not battery-optimised is left alone. Asked once, when the mode is first
     * started: the dialog opens the system list where the app is set to "not optimised" (no special
     * permission needed for that, unlike asking for the exemption directly). */
    private fun askBatteryOptimisationExemptionOnce() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_battery_title)
            .setMessage(R.string.dialog_battery_message)
            .setPositiveButton(R.string.dialog_battery_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .setNegativeButton(R.string.dialog_battery_later, null)
            .show()
    }

    /** Filled sailboat while the boat mode runs, outline while it is off. */
    fun updateBootButton() {
        val active = BootModeStateStore(this).isActive
        bootButton.setCompoundDrawablesWithIntrinsicBounds(
            if (active) R.drawable.ic_sailboat_filled_24 else R.drawable.ic_sailboat_24, 0, 0, 0,
        )
        ViewCompat.setTooltipText(
            bootButton,
            getString(if (active) R.string.tooltip_boat_mode_on else R.string.tooltip_boat_mode),
        )
    }

    /** ☁️ only makes sense once WordPress or SFTP is actually filled in (see SettingsStore) --
     * asked for explicitly: tapping it with neither configured used to just log "vul eerst de
     * publiceer-instellingen in" (see runPublish()), so the button looked usable when it never
     * could do anything. buildButton (see the iconButton() call site above) covers "no publish
     * settings, but still want to build" on its own now, so this one goes back to plain
     * disabled-until-configured rather than also changing what it does. Both stay disabled while
     * a sync or offline build is already running, same as before this existed. Called from
     * onResume() too, since the only way settings change is a round trip through SettingsActivity
     * and back.
     *
     * The tooltip is left set (harmless, still reachable via mouse/stylus hover), but not relied
     * on -- confirmed on a real device, touch-only (see updateSyncButtonAvailability()'s own,
     * more detailed doc comment on why): a *disabled* view's onTouchEvent() returns before ever
     * reaching the long-press/tooltip-trigger logic, so the tooltip text never actually shows on
     * a touchscreen with no mouse. The log line right below is what's actually visible. Not
     * logged every single call (this runs from onCreate()/onResume()/after every sync or build,
     * same as updateSyncButtonAvailability()) -- only when it just *became* unconfigured, so
     * opening Instellingen once and looking at it doesn't spam the log on every later resume. */
    private fun updatePublishButtonEnabled() {
        // While a run is in progress, the button that started it stays enabled and pulses (tapping
        // it cancels, see cancelSyncStayInApp()) -- the same toggle for the download button, the
        // build button and the publish button alike -- while the other two stay disabled.
        val initiator = if (SyncState.inProgress) SyncState.runInitiator ?: RunInitiator.DOWNLOAD else null
        val running = initiator != null
        buildButton.isEnabled = !running || initiator == RunInitiator.BUILD
        importButton.isEnabled = !running || initiator == RunInitiator.IMPORT
        setBusyAppearance(importButton, initiator == RunInitiator.IMPORT)
        val configured = settingsStore.isRestUploadConfigComplete || settingsStore.isSftpConfigComplete
        val wasEnabled = publishButton.isEnabled
        publishButton.isEnabled = if (running) initiator == RunInitiator.PUBLISH else configured
        if (!running && !configured && wasEnabled) {
            handleLogLine("[info] " + getString(R.string.log_publish_not_configured))
        }
        setBusyAppearance(buildButton, initiator == RunInitiator.BUILD)
        setBusyAppearance(publishButton, initiator == RunInitiator.PUBLISH)
        setBusyAppearance(downloadButton, initiator == RunInitiator.DOWNLOAD)
        ViewCompat.setTooltipText(
            publishButton,
            getString(
                when {
                    initiator == RunInitiator.PUBLISH -> R.string.tooltip_cancel
                    configured -> R.string.tooltip_publish
                    else -> R.string.tooltip_publish_not_configured
                },
            ),
        )
        ViewCompat.setTooltipText(
            buildButton,
            getString(if (initiator == RunInitiator.BUILD) R.string.tooltip_cancel else R.string.tooltip_build_local),
        )
        if (running) {
            // The download button only ever cancels a download it started itself; while a
            // build/publish runs it is just disabled (updateSyncButtonAvailability() takes over
            // again once nothing is running -- its own hotspot/W2K-2 check decides isEnabled
            // then, not this function).
            downloadButton.isEnabled = initiator == RunInitiator.DOWNLOAD
            if (initiator == RunInitiator.DOWNLOAD) ViewCompat.setTooltipText(downloadButton, getString(R.string.tooltip_cancel))
        }
    }

    /** Pulses the button's own icon (fading in and out, no icon swap) while it is the cancel
     * button for a run in progress -- one consistent way to show "something long is running here,
     * tap to stop it" everywhere in the app (asked for explicitly: a separate stop-square icon
     * read as a different thing from a notification's own animated "busy" icon; this makes both
     * say the same thing the same way). Idempotent: starting an already-running pulse, or stopping
     * one that was never started, both no-op rather than restarting/erroring. */
    private fun setBusyAppearance(button: Button, busy: Boolean) {
        if (busy) {
            if (busyAnimators.containsKey(button)) return
            // 1500ms each way (asked for explicitly, found in practice: 500ms read as flickery,
            // 900ms still too fast; this is a calm "something is happening" pulse).
            val animator = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.35f).apply {
                duration = 1500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            busyAnimators[button] = animator
            animator.start()
        } else {
            busyAnimators.remove(button)?.cancel()
            button.alpha = 1f
        }
    }

    /** The download button always stays enabled, whether or not the W2K-2 has actually been seen
     * yet -- asked for explicitly, reversing this function's own earlier "disabled until a real
     * scan confirms it" behavior: that background scan (HotspotDetector's cheap local check, then
     * android_entry.discover_w2k2_only() over the network) ran again on every onCreate()/onResume(),
     * which in practice meant a fresh "Geen Actisense W2K-2 gevonden" log line every time the app
     * so much as came back to the foreground -- confusing repetition, not useful information.
     * runDownload() already does the exact same single-attempt, no-retry check (HotspotDetector, then
     * discover_w2k2() as syncFromW2k2()'s own first step) the instant the button is actually
     * pressed, and already reports a clean "not found" outcome on its own -- nothing here needs to
     * duplicate that ahead of time. Like updatePublishButtonEnabled(), never overrides downloadButton
     * while a download is already in progress (it deliberately stays enabled then, to double as
     * the cancel button). */
    private fun updateSyncButtonAvailability() {
        if (SyncState.inProgress) return
        downloadButton.isEnabled = true
        ViewCompat.setTooltipText(downloadButton, getString(R.string.tooltip_sync))
    }

    /** Found in practice, still not fully understood at the OS level: right after this Activity's
     * process is resumed (a cold start, or -- confirmed by logcat on this device -- Samsung's own
     * "Freecess" background-process freezer thawing it back out) isW2k2ConfigComplete has
     * sometimes read false here even though the real settings were still saved fine, and stayed
     * false for longer than the single 300ms re-check this used to do (found in practice: that
     * one-shot version still wasn't enough, see the same bug reported again after this was
     * already in place). EncryptedSharedPreferences relies on the Android Keystore, which could
     * plausibly still be settling for a bit after either kind of resume.
     *
     * Retries a few times, spaced out, before concluding settings are genuinely incomplete --
     * cheap either way: it either papers over that race, or costs a little under a second before
     * showing the same message runDownload() itself would show for a real empty-settings case. Only
     * used for the automatic startup attempt; a manual 🔄 tap goes straight to runDownload() and its
     * own immediate check, since by then the app has already been running long enough that this
     * race isn't a concern. */
    private fun autoStartSyncWithSettingsRetry(attemptsLeft: Int = 5) {
        // A sync from an earlier instance of this Activity can still be genuinely running in the
        // background right now -- SyncNotificationService's android:stopWithTask="false" means
        // closing the app (or it getting recreated for any other reason) doesn't stop it (found
        // in practice: a fresh instance's own settings re-check raced against this and showed
        // "vul eerst je instellingen in" over a download that was actually still progressing fine,
        // confusing but not actually broken). Nothing to auto-start in that case.
        //
        // Full live state is restored here too (found in practice, a second real bug on top of
        // the first): a brand new Activity instance's log/progress bar always start out blank/
        // hidden, and returning here without touching them left that placeholder state on screen
        // indefinitely, even though the download was genuinely progressing the whole time.
        if (SyncState.inProgress) {
            restoreLiveSyncUi()
            return
        }
        if (settingsStore.isW2k2ConfigComplete) {
            // Only actually starts a download when the W2K-2's own hotspot looks reachable right
            // now (a cheap, local, synchronous check -- see HotspotDetector, no network I/O) --
            // asked for explicitly: always trying (and usually failing, away from the boat) on
            // every single app launch used to mean a visible "Hotspot controleren..." cycle each
            // time, for no benefit when there was never any real chance of finding it. A real full
            // download (see runDownload()) still does its own, more thorough discover_w2k2() scan, which can
            // still come back "not found" (the hotspot's on, but the W2K-2 itself never actually
            // joined it) -- that outcome (and this cheap check's own, right below) is always a
            // quiet log line + notification rather than a popup, auto-started or manual alike.
            if (HotspotDetector.detectSubnetPrefix() != null) {
                // No longer minimized automatically after starting (tried this -- see git history
                // for both a fixed-delay and an event-based version) -- asked for explicitly: the
                // app should only minimize once its notification is fully visible, and since
                // Android has no callback for "now visually rendered" (only for the
                // startForeground() call itself, which found in practice can precede the real,
                // on-screen appearance by several seconds, especially right after a fresh
                // install), that can't be guaranteed -- so per the fallback instruction, it just
                // stays open instead of guessing at a delay again.
                runDownload()
            } else {
                // No existing-logbook fallback here (asked for explicitly, "logboek alleen tonen
                // als auto download uit staat") -- this whole function only ever runs when
                // settingsStore.autoSyncOnLaunch is on (see onCreate()'s own branching), so
                // showing a logbook automatically here is out of scope: that's what the
                // autoSyncOnLaunch==false branch's own viewLocalLogbook() call is for. With
                // auto-download on, the owner asked to see fresh data, not whatever's cached.
                // No system notification here (asked for explicitly, "meldingen alleen gebruiken
                // voor langdurige processen") -- this is a cheap, synchronous, no-network-I/O
                // check, over before it started; nothing actually ran long enough to be worth
                // learning about after walking away. The log line above is enough.
                handleLogLine("[info] " + getString(R.string.log_hotspot_precheck_skipped))
            }
        } else if (attemptsLeft > 0) {
            android.os.Handler(mainLooper).postDelayed(
                { autoStartSyncWithSettingsRetry(attemptsLeft - 1) }, 300L
            )
        } else {
            // Just calls runDownload() rather than duplicating its own isW2k2ConfigComplete check
            // and log_fill_w2k2_credentials line here too (asked for explicitly, "1x is toch
            // genoeg?") -- runDownload() already starts with the exact same check, and produces the
            // exact same message, for a manual tap on the download button. Retries above exist
            // purely for the settings-store-still-loading race (see this function's own doc
            // comment); once those are exhausted, settings are either genuinely complete (handled
            // by runDownload() itself) or genuinely incomplete (also handled by runDownload() itself) --
            // either way, runDownload() is the single source of truth for what to do about it. Note
            // this deliberately does NOT replace the branch above (settings complete, hotspot
            // reachable): that one's own cheap HotspotDetector precheck before ever calling
            // runDownload() is a real, separate optimisation (asked for explicitly, see its own
            // comment) to avoid a "Hotspot controleren..." cycle on every single launch away from
            // the boat -- only this settings-incomplete branch was pure duplication.
            runDownload()
        }
    }

    /** Call right before starting a manual run's own background Thread (runDownload()/
     * buildFromLocalFilesAndMaybePublish()); release with releaseManualRunWakeLock() in that
     * Thread's own finally block, same pairing as SyncState.inProgress. A generous fixed timeout
     * (see WAKE_LOCK_TIMEOUT_MS), not indefinite -- a safety net only, same reasoning as
     * BootModeService's own wake lock: the run's own finally block is what actually releases it
     * the moment it is done, this just guarantees the lock can never outlive a run that crashed
     * Android hard enough to skip that finally block too. */
    private fun acquireManualRunWakeLock() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        manualRunWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MySailingLogbook:ManualRun").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseManualRunWakeLock() {
        manualRunWakeLock?.let { if (it.isHeld) it.release() }
        manualRunWakeLock = null
    }

    /** True (with a line in the log, brought to the front first) while the boat-mode service is
     * running a round or a publish: the user's own run would work on the same files at the same
     * time, so it waits. Brings the log to the front itself rather than leaving that to the
     * caller's own setup (which this short-circuits past) -- found in practice: without it, this
     * line was written to the log while the logbook's WebView stayed on screen covering it, so
     * tapping the button while boat mode was busy looked like it silently did nothing at all. */
    private fun bootModeBusy(): Boolean {
        if (!SyncState.bootBusy) return false
        showingLocalLogbook = false
        setLogExpanded(true)
        handleLogLine("[info] " + getString(R.string.log_boat_busy))
        return true
    }

    private fun runDownload() {
        if (SyncState.inProgress) return
        if (bootModeBusy()) return
        if (!settingsStore.isW2k2ConfigComplete) {
            // Switches away from a currently-shown logbook first (found in practice, asked for
            // explicitly, "ik drukte op download terwijl html werd getoond" -- "2e keer niks"):
            // handleLogLine() below always updates the log view's own text regardless of whether
            // it's actually the visible layout right now (see refreshLogView()'s own doc comment
            // on why -- it has no idea, and doesn't check), so without this the line was added but
            // invisible behind the still-showing WebView, reading as if nothing had happened at
            // all. Every other path through this function already does this (see below); only
            // this early-return guard was missing it.
            showingLocalLogbook = false
            setLogExpanded(true)
            handleLogLine("[info] " + getString(R.string.log_fill_w2k2_credentials))
            return
        }

        SyncState.inProgress = true
        SyncState.runInitiator = RunInitiator.DOWNLOAD
        SyncState.cancelled = false
        // downloadButton deliberately stays enabled here (unlike buildButton/publishButton) --
        // tapping it again while a sync is running cancels it instead (see the button's own
        // onClick below and cancelSyncStayInApp()), asked for explicitly: the app's own
        // auto-start-on-launch (see autoStartSyncWithSettingsRetry()) has no way to be skipped
        // otherwise, so opening the app to use ☁️ Publiceren on its own was never actually
        // reachable -- these two buttons stayed disabled for as long as that auto-started sync
        // kept running.
        updatePublishButtonEnabled()
        val initialStatusText = getString(R.string.status_checking_hotspot)
        // Log deliberately NOT cleared here (asked for explicitly) -- it now accumulates across
        // every sync this process runs instead of starting over each time, so a run's own history
        // stays visible/scrollable-back-to after later runs. Only the progress state below still
        // resets per-run, since that's specifically about the run in progress right now.
        SyncState.lastStatusText = initialStatusText
        SyncState.lastNotificationText = initialStatusText
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        showingLocalLogbook = false
        setLogExpanded(true)
        // Shown immediately, before hotspot detection even starts -- not only once the first
        // "Downloaden: 1/X" progress update arrives (asked for explicitly: hotspot detection and
        // then listing every folder that still needs checking can itself take a real moment on a
        // big archive, during which nothing was visible outside the app at all before this).
        val startIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, initialStatusText)
        startSyncNotification(startIntent)
        handleLogLine("[info] $initialStatusText")

        // Captured before this run starts -- see the "actually produced a fresh file" fallback
        // check below, right after syncFromW2k2() returns.
        val htmlFile = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
        val htmlMtimeBeforeThisRun = if (htmlFile.exists()) htmlFile.lastModified() else -1L

        acquireManualRunWakeLock()
        Thread {
            val subnetPrefix = HotspotDetector.detectSubnetPrefix()
            if (subnetPrefix == null) {
                val message = getString(R.string.status_hotspot_off)
                SyncState.lastStatusText = message
                // This specific check is pure Kotlin (HotspotDetector, no Python/Chaquopy call
                // involved at all), unlike the "No W2K-2 found on <subnet>" case a few lines
                // further down in this same Thread -- that one already gets a log line for free,
                // from discover_w2k2()'s own log() calls in Python. This one didn't have an
                // equivalent until now, so it's added explicitly here to match.
                handleLogLine("[info] $message")
                withActiveActivity {
                    SyncNotificationService.stop(this)
                    SyncState.notificationForegrounded = false
                    SyncState.notificationStartFailed = false
                    // No popup, and no system notification either (asked for explicitly,
                    // "meldingen alleen gebruiken voor langdurige processen") -- "W2K-2 not
                    // reachable yet" is the expected, common outcome of not being at the boat, not
                    // something worth a modal interruption, and this cheap check bailed before any
                    // real download work even started -- nothing ran long enough to be worth
                    // learning about after walking away. The log line above is enough.
                    SyncState.inProgress = false
                    SyncState.runInitiator = null
                    updateSyncButtonAvailability()
                    updatePublishButtonEnabled()
                }
                releaseManualRunWakeLock()
                return@Thread
            }

            val listingStatusText = getString(R.string.status_listing_files, subnetPrefix)
            SyncState.lastStatusText = listingStatusText
            SyncState.lastNotificationText = listingStatusText
            handleLogLine("[info] $listingStatusText")
            withActiveActivity {
                val listingIntent = Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, listingStatusText)
                startSyncNotification(listingIntent)
            }
            // Hoisted above the try block so the finally below can still see the outcome --
            // needed to decide between a plain "download stopped" cleanup and posting the
            // "Voltooid" completion notification (see SyncNotificationService.postCompletionNotification()).
            var syncSucceeded = false
            var didPublish = false
            try {
                var result = syncFromW2k2(subnetPrefix)
                // Belt-and-suspenders on top of SyncController.onResult() (see its own doc
                // comment for the real fix -- capturing the outcome via a direct method call
                // during sync_from_w2k2()'s own execution, instead of reading callAttr()'s
                // returned PyObject's fields afterward, which is what was actually unreliable).
                // Kept as a second, independent check rather than removed once onResult() landed:
                // logbook.html's own mtime is a signal Kotlin already has regardless of anything
                // Python reports, so an inconclusive "ok: false, error: null" outcome (should no
                // longer happen at all post-onResult(), but cheap to guard anyway) still isn't
                // trusted blindly over a file that's demonstrably newer than before this run.
                if (!result.ok && result.error == null && !result.cancelled) {
                    val mtimeNow = if (htmlFile.exists()) htmlFile.lastModified() else -1L
                    if (mtimeNow > htmlMtimeBeforeThisRun) {
                        result = SyncResult(
                            ok = true,
                            cancelled = false,
                            error = null,
                            tripCount = null,
                            htmlPath = htmlFile.absolutePath,
                            downloadedCount = result.downloadedCount,
                        )
                    }
                }
                syncSucceeded = result.ok
                // Before showing the logbook, not after -- asked for explicitly, found in
                // practice: showing it first and then covering it back up with the log a moment
                // later, once a publish problem turned up, looked like a glitch (the logbook
                // flashing on screen and immediately disappearing again). Still on this same
                // background Thread, not re-dispatched: SftpUploader's calls are blocking network
                // I/O same as the download itself was. Gated on the setting (asked for explicitly)
                // -- off, this sync only ever builds the logbook locally; the owner checks it via
                // 📖 and publishes on their own terms via ☁️ (runPublish(), unaffected by this
                // setting).
                var publishFailed = false
                if (result.ok && result.htmlPath != null && settingsStore.autoPublishAfterBuild) {
                    didPublish = uploadIfConfigured(result.htmlPath)
                    publishFailed = publishFailedAfter(didPublish)
                }
                withActiveActivity { showSyncResult(result, publishFailed) }
                if (syncSucceeded) {
                    // A plain Kotlin-originated line (not one of log.py's own), reused through
                    // the exact same accumulator/log-view path as every other line -- asked for
                    // explicitly: there was previously no single, unambiguous "this whole sync,
                    // including any publish step, is now finished" marker in the log at all.
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    handleLogLine(getString(R.string.log_sync_done_at, timeText))
                }
            } catch (e: Exception) {
                val message = getString(R.string.error_unexpected_sync, e.toString())
                SyncState.lastStatusText = message
                handleLogLine("[error] $message")
            } finally {
                // Real, must-always-happen state -- not gated behind withActiveActivity (which
                // no-ops when nothing is currently active, e.g. the app is fully backgrounded
                // right as the download finishes): SyncState.inProgress staying stuck true forever
                // would block every later download attempt, and this instance is a valid Context for
                // stopService()/postCompletionNotification() regardless of whether it's the
                // currently active one.
                SyncNotificationService.stop(this)
                if (syncSucceeded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    SyncNotificationService.postCompletionNotification(
                        this,
                        getString(R.string.notif_sync_done_at, timeText),
                        if (didPublish) MainActivity.LIVE_SITE_URL else null,
                        R.drawable.ic_download_24,
                    )
                }
                SyncState.notificationForegrounded = false
                SyncState.notificationStartFailed = false
                SyncState.inProgress = false
                SyncState.runInitiator = null
                releaseManualRunWakeLock()
                // Purely cosmetic UI state, safe to skip when nothing is active right now -- the
                // next Activity to resume starts from a fresh, already-correct button/progress
                // state on its own (see onCreate()/restoreLiveSyncUi()).
                withActiveActivity {
                    updateSyncButtonAvailability()
                    updatePublishButtonEnabled()
                    hideProgressBar()
                }
            }
        }.start()
    }

    /** 📖 icon: shows whatever logbook.html is already on the phone, purely local -- no sync, no
     * publish, nothing sent anywhere. Doesn't touch SyncState at all, so it works even while a
     * sync is running (just shows the *previous* build until that one finishes and replaces it
     * via showSyncResult()'s own success branch). */
    private fun viewLocalLogbook() {
        if (showingLocalLogbook) {
            // Toggle back to the log (asked for explicitly) -- the logbook itself is already
            // loaded in the WebView from the tap that showed it, nothing to reload.
            showingLocalLogbook = false
            setLogExpanded(true)
            return
        }
        val htmlFile = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
        if (!htmlFile.exists()) {
            handleLogLine("[info] " + getString(R.string.log_no_logbook_to_view))
            return
        }
        showingLocalLogbook = true
        logbookShownAsRunResult = false
        // Fully hides the log rather than leaving setLogExpanded(false)'s own small collapsed
        // strip (still used as-is after a normal download/publish completes) -- asked for explicitly,
        // this view is meant to cover the whole screen, not share it with a log peek.
        logList.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
        webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        loadLogbookIntoWebView(htmlFile.absolutePath)
    }

    // Tracks whether 📖 is currently showing the fully-covering local view above, so a second tap
    // knows to toggle back to the log instead of just reloading the same file again. Reset to
    // false wherever a sync/offline-build starts (see runDownload()/runOfflineBuild()) -- those
    // already re-expand the log themselves via setLogExpanded(true), so this only needs to stay
    // in sync with that, not drive it.
    private var showingLocalLogbook = false

    // True while the logbook is showing as the result of a run, with the log still visible as a
    // strip below it (see showSyncResult()); false as soon as the log is expanded again or 📖 shows
    // the logbook over the whole screen. The Assemble button reads it, see buildButton.
    private var logbookShownAsRunResult = false

    /** Manual re-publish (the ☁️ icon): builds the logbook from whatever .ebl files are already
     * on the phone (same as runOfflineBuild(), see buildFromLocalFilesAndMaybePublish()'s own
     * doc comment) and then always uploads it, ignoring "Automatisch publiceren na bouwen" --
     * tapping ☁️ is itself the explicit request to publish. Building first here, not just
     * re-uploading whatever logbook.html already happened to be on disk, fixes a real gap found
     * in practice: downloading from the W2K-2 over its own hotspot (no internet there to publish
     * over anyway), then switching to a different network later specifically to publish -- if
     * that download's own decode/build step never finished (app closed, network switched first),
     * there was no fresh logbook.html for ☁️ to send, or an old one sat there unchanged. A
     * cache-hit rebuild when nothing's actually missing is fast, so this costs little even when
     * ☁️ alone (an already-fresh logbook.html) would have been enough. Shares SyncState.inProgress
     * with runDownload() so this can't run at the same time as a sync's own automatic upload at the
     * end of it. */
    private fun runPublish() {
        if (SyncState.inProgress) return
        if (bootModeBusy()) return
        // Both, not just SFTP -- found in practice, a real bug: an owner with only REST
        // configured (no SFTP at all, the whole point of preferring REST) tapped ☁️ and got told
        // to fill in "de publiceer-instellingen (SFTP)" even though publishing itself would have
        // worked fine via REST. Matches uploadIfConfigured()'s own check exactly.
        if (!settingsStore.isRestUploadConfigComplete && !settingsStore.isSftpConfigComplete) {
            // Same fix as runDownload()'s own matching guard (asked for explicitly, "check ook bij
            // andere knoppen of dit goed gaat in alle gevallen") -- without this, tapping publish
            // while a logbook was already showing added this line to the log invisibly, since
            // refreshLogView() updates the log view's own text regardless of whether it's
            // actually the visible layout right now.
            showingLocalLogbook = false
            setLogExpanded(true)
            handleLogLine("[info] " + getString(R.string.log_fill_publish_settings))
            return
        }
        buildFromLocalFilesAndMaybePublish(forcePublish = true)
    }

    private data class SyncResult(
        val ok: Boolean,
        val cancelled: Boolean,
        val error: String?,
        val tripCount: Int?,
        val htmlPath: String?,
        val downloadedCount: Int?,
    )

    /** See EblStorage.indexForPc(). */
    private fun indexEblFilesForPc(actisenseDir: File, modifiedSince: Long) =
        EblStorage.indexForPc(applicationContext, actisenseDir, modifiedSince)

    /** One-time catch-up for .ebl files downloaded before indexEblFilesForPc() existed (already
     * sitting in the folder, never reported to the media index). Off the main thread; remembered
     * in a plain preference so it runs once per install, not on every launch. Uses the folder's
     * path directly rather than eblDownloadDir(), which also logs and migrates. */
    private fun indexExistingEblFilesForPcOnce() {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EBL_INDEXED_FOR_PC, false)) return
        Thread {
            val base = getExternalFilesDir(null) ?: return@Thread
            val dir = File(base, SharedConstants.EBL_DIR_NAME)
            if (dir.exists()) indexEblFilesForPc(dir, 0L)
            prefs.edit().putBoolean(KEY_EBL_INDEXED_FOR_PC, true).apply()
        }.start()
    }

    /** Deletes any `ebl-import-*` staging directory left behind under cacheDir by a previous
     * importFromRemovableMedia() run that never reached its own finally block -- found in
     * practice to be a real gap, asked about explicitly ("wordt lokale scratch-map opgeruimd
     * onder alle omstandigheden?"): that finally block's own stagingDir.deleteRecursively() (see
     * importFromRemovableMedia()'s own comment) covers every exception this app's own code can
     * catch, but not the process being killed outright mid-import (force-stop, the OS's low-
     * memory killer, a crash) -- nothing runs then, so that one run's own staging directory (named
     * with that run's own start time, never reused) is simply orphaned, and a future run's own
     * cleanup only ever deletes its own directory, not an older one left by a previous run.
     *
     * SyncState.inProgress guard is required, not just cheap insurance -- onCreate() (this
     * function's only caller) runs on every Activity recreation, not only a genuinely fresh
     * process launch, and an import's own background Thread (unlike a download, not tied to any
     * Service) keeps running across a recreation the same process is still hosting; without this
     * check, a rotation or task-switch-and-return landing mid-import would sweep away the exact
     * staging directory that Thread is still actively reading from. Skipped for any run in
     * progress, not only an import specifically -- simpler, and free either way: nothing to clean
     * up yet if a download/build/publish is what's actually running, so the only cost of being
     * this conservative is trying again on the next launch, once nothing is running to protect.
     *
     * Otherwise run on every launch, off the main thread (a leftover directory from a large,
     * interrupted import could hold hundreds of files) -- cacheDir is exclusive to this app, so
     * deleting whatever matches this name pattern is safe once nothing could still be using it. */
    private fun cleanUpOrphanedImportStagingDirs() {
        if (SyncState.inProgress) return
        Thread {
            cacheDir.listFiles { file -> file.isDirectory && file.name.startsWith("ebl-import-") }
                ?.forEach { it.deleteRecursively() }
        }.start()
    }

    /** Where downloaded .ebl files live (see EblStorage.downloadDir()), logged once per run. */
    private fun eblDownloadDir(): File {
        val result = EblStorage.downloadDir(this)
        // Asked for explicitly, now that USB file transfer to this exact path is the owner's own
        // way to browse the .ebl archive from a PC -- one line per sync/offline-build run (both
        // callers only ever call this once each), not spammy. "/storage/emulated/0/" dropped
        // (asked for explicitly too) -- that prefix is never what's shown in Explorer/a file
        // picker on the PC side, just noise; starts at "Android/..." instead, which is. Falls
        // back to the full path on the rare internal-storage fallback (eblDownloadDir() above),
        // whose path never has an "/Android/" segment to trim from in the first place.
        val fullPath = result.absolutePath
        val androidIndex = fullPath.indexOf("/Android/")
        val shownPath = if (androidIndex >= 0) fullPath.substring(androidIndex + 1) else fullPath
        handleLogLine("[info] " + getString(R.string.log_ebl_files_location, shownPath))
        return result
    }

    /** Runs android_entry.sync_from_w2k2() via Chaquopy -- one Python call does discovery,
     * download, and the decode/build/write pipeline (see android_entry.py for why this isn't
     * split into several separate Chaquopy calls). Must be called off the main thread: Python
     * startup plus decoding real multi-MB .ebl files easily takes several seconds (found in
     * practice during spike 3 -- ANR otherwise). */
    private fun syncFromW2k2(subnetPrefix: String): SyncResult {
        PythonStarter.ensureStarted(this)
        val py = Python.getInstance()
        val androidEntry = py.getModule("nmea2log.android_entry")

        val downloadDir = eblDownloadDir()
        val syncStartedAt = System.currentTimeMillis()
        val outputHtmlPath = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
        val sampleCachePath = File(filesDir, SharedConstants.SAMPLE_CACHE_FILE_NAME)

        // Captured by the controller's onResult() below, not read back out of callAttr()'s own
        // return value afterward -- see SyncController.onResult()'s own doc comment for why.
        var capturedResult: SyncResult? = null

        // Called by Python between files (android_entry.py): report() lets the status text and
        // notification show real "current/total" progress instead of one static message for
        // however long a sync takes (a first-ever sync fetches the whole historical archive,
        // easily several GB, found in practice); isCancelled() lets a sync stop cleanly once
        // onDestroy() sets the cancelled flag, instead of continuing after the app is closed.
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {
                val text = getString(R.string.status_downloading, current, total, fileName)
                SyncState.lastStatusText = text
                // Matches what SyncNotificationService itself independently computes from the
                // current/total/fileName extras below -- cached here too so a later restore (see
                // onResume()) has the right text without needing to resend those three extras.
                // Not logged per file either (this fires once per file, easily thousands of times
                // for a first-ever sync) -- the progress bar (see updateProgressBar() below) and
                // this same text in the notification are enough; a log line per file would just
                // flood the log for no benefit.
                SyncState.lastNotificationText = text
                // Also visible from the notification shade while the app isn't on screen -- see
                // SyncNotificationService.onStartCommand(), which updates its existing
                // notification in place rather than posting a new one each time.
                val progressIntent = Intent(this@MainActivity, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_CURRENT, current)
                    .putExtra(SyncNotificationService.EXTRA_TOTAL, total)
                    .putExtra(SyncNotificationService.EXTRA_FILE_NAME, fileName)
                    // A real progress bar in the notification too, not just text (asked for
                    // explicitly, same reasoning as the decode/build-trips phases below -- the
                    // small icon itself can't animate, see onStartCommand()'s own doc comment).
                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_CURRENT, current)
                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_MAX, total)
                startSyncNotification(progressIntent)
                updateProgressBar(getString(R.string.phase_downloading), current, total)
            }

            override fun isCancelled(): Boolean = SyncState.cancelled

            override fun onLogLine(line: String) = handleLogLine(line)

            override fun onProgress(phase: String, current: Int, total: Int) = handleProgress(phase, current, total)

            // No longer stops the notification here (decode/build is pure CPU, no more network
            // I/O left once this fires) -- tried that, found in practice it backfired: decoding
            // this app's real archives routinely takes long enough to need its own progress
            // shown again anyway (see handleProgress()), so stopping here only meant a
            // *second* startForegroundService() eligibility check later in the same run, and on
            // Android 15+'s per-24h "dataSync" budget (see startSyncNotification()'s own doc
            // comment) that's a second chance to get refused instead of one. Leaving the service
            // running continuously from the start of the sync through decode costs at most a
            // handful of extra seconds of that budget on the (uncommon, on a real archive)
            // all-cache-hit case, for a real reduction in how often this budget gets hit at all.
            //
            // The only thing done here: make the files just fetched show up over USB, see
            // indexEblFilesForPc().
            override fun onDownloadComplete() {
                indexEblFilesForPc(downloadDir, syncStartedAt)
            }

            // Only the boat mode needs the boat state (see W2kBootExecutor).
            override fun onBoatState(boatStateJson: String?) {}

            override fun onResult(
                ok: Boolean,
                error: String?,
                cancelled: Boolean,
                tripCount: Int,
                htmlPath: String?,
                downloadedCount: Int,
            ) {
                capturedResult = SyncResult(
                    ok = ok,
                    cancelled = cancelled,
                    error = error,
                    tripCount = if (ok && tripCount >= 0) tripCount else null,
                    htmlPath = if (ok) htmlPath else null,
                    downloadedCount = if (ok && downloadedCount >= 0) downloadedCount else null,
                )
            }
        }

        androidEntry.callAttr(
            "sync_from_w2k2",
            settingsStore.w2k2User,
            settingsStore.w2k2Password,
            subnetPrefix,
            downloadDir.absolutePath,
            outputHtmlPath.absolutePath,
            sampleCachePath.absolutePath,
            settingsStore.boatName,
            settingsStore.mmsi,
            settingsStore.callSign,
            controller,
            settingsStore.minStopMinutes,
        )

        // onResult() is always called, unconditionally, right before sync_from_w2k2() returns
        // (see android_entry.py's own _report_result()) -- this being null would mean that call
        // never happened at all, which callAttr() above returning normally already rules out.
        return capturedResult ?: SyncResult(
            ok = false, cancelled = false, error = getString(R.string.error_no_result),
            tripCount = null, htmlPath = null, downloadedCount = null,
        )
    }

    /** Runs [block] against whichever Activity instance is currently active (see
     * SyncState.active), with that instance as the receiver, on the main thread -- lets code
     * reached from a background Thread/SyncController callback (report(), handleLogLine(), the
     * sync Thread's own finally block, ...) update whichever Activity is actually on screen right
     * now, not necessarily the specific instance that started the sync (see SyncState.active's
     * own doc comment for why that distinction matters). A no-op when nothing is currently active
     * (app fully backgrounded) -- there's nothing to update in that case; SyncState's own cached
     * fields (updated separately, alongside every call site below) still let the next Activity
     * that resumes restore the real state instead (see restoreLiveSyncUi()). */
    private fun withActiveActivity(block: MainActivity.() -> Unit) {
        val target = SyncState.active ?: return
        target.runOnUiThread { target.block() }
    }

    /** Shared between syncFromW2k2()'s and buildFromLocalFiles()'s own SyncController.onLogLine()
     * -- found in practice: the offline-build path (see runOfflineBuild()) had no log-line
     * handling of its own at all, so a real (not cache-hit) decode there left the screen stuck on
     * a single static "Logboek opbouwen..." message with nothing to show it wasn't just hung,
     * instead of the same "...decoded X/Y" progress a normal sync already shows via the block
     * below. */
    /** Whether the log is currently scrolled all the way to its own bottom -- checked *before*
     * appending a new line (see handleLogLine()), so a user who scrolled up to read an earlier
     * line doesn't get yanked back down to the bottom the moment the next line arrives. A few px
     * of slack rather than exact equality: scroll position/content height can be off by a
     * rounding pixel or two even while visually "at the bottom". */
    private fun isLogScrolledToBottom(): Boolean {
        val slackPx = (4 * resources.displayMetrics.density).toInt()
        return logList.computeVerticalScrollOffset() + logList.computeVerticalScrollExtent() >=
            logList.computeVerticalScrollRange() - slackPx
    }

    /** Shows the lines LogBuffer has gained since the last call, keeping the scroll position unless it was
     * at the bottom. Public: AppLog calls it for lines made outside this Activity (the boat-mode service). */
    fun refreshLogView() {
        // Coalesced: at most one update per LOG_REFRESH_INTERVAL_MS however many lines arrive (a decode
        // logs one every few seconds, an import one per file); rows are only appended, so an update costs
        // the same however long the log is.
        if (!logRefreshPending.compareAndSet(false, true)) return
        runOnUiThread {
            logList.postDelayed({
                logRefreshPending.set(false)
                val wasAtBottom = isLogScrolledToBottom()
                if (logAdapter.sync() && wasAtBottom) scrollLogToEnd()
            }, LOG_REFRESH_INTERVAL_MS)
        }
    }

    private val logRefreshPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Scrolls the log to its newest line, once the rows have been laid out. */
    private fun scrollLogToEnd() {
        logList.post { if (logAdapter.lastPosition >= 0) logList.scrollToPosition(logAdapter.lastPosition) }
    }

    private fun handleLogLine(rawLine: String) {
        val line = AppLog.stamp(rawLine)
        // Only a line that had no timestamp yet was made here; Python's own lines are already in
        // the file (log.py writes every line there itself).
        if (line != rawLine) AppLog.appendToFile(this, line)
        // The accumulator, not the log view itself -- it may belong to an orphaned
        // instance, or there may be no active instance at all right now (see withActiveActivity),
        // so the running log has to live somewhere that survives either.
        AppLog.append(line)
        withActiveActivity { refreshLogView() }
        // A "[warning]" line (a failed attempt being retried, e.g. connection lost) means
        // report()'s own "current/total" notification text is about to sit frozen and
        // stale for a while -- found in practice: half an hour out of range looked from
        // the notification alone like the app was just stuck on file 26/625, no
        // indication anything had actually gone wrong. A short, plain message here, not
        // the raw warning text itself (asked for explicitly) -- that's already visible
        // verbatim in the in-app log for anyone who wants the technical detail (which
        // host/file, the exact OS error, which retry attempt).
        // contains(), not startsWith(): log.py's log() always prepends a "YYYY-MM-DD
        // HH:MM:SS " timestamp before handing the line to this sink, so it never actually
        // starts with the "[warning]" tag itself (found in practice: this check never
        // matched at all, so the notification silently never updated during a real
        // connection-loss test).
        if (line.contains("[warning]")) {
            val text = getString(R.string.notif_connection_lost_retrying)
            SyncState.lastNotificationText = text
            val warningIntent = Intent(this, SyncNotificationService::class.java)
                .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
            startSyncNotification(warningIntent)
        }
    }

    /** A progress report from Python (nmea2log/progress.py): decoding x of y logfiles, or step x of the
     * four of building the trips. Found in practice: decoding logfiles not already in the sample cache is
     * CPU-bound and, on a phone's much weaker CPU than a desktop's, can silently run for many minutes --
     * with the screen off there was nothing at all to show this wasn't just hung. The notification is left
     * running continuously from the start of the sync (see onDownloadComplete() above), so this is just a
     * cheap content update most of the time, not a fresh eligibility-gated start. */
    private fun handleProgress(phase: String, current: Int, total: Int) {
        val text = SyncProgress.notificationText(this, phase, current, total) ?: return
        SyncState.lastNotificationText = text
        val progressIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
            .putExtra(SyncNotificationService.EXTRA_PROGRESS_CURRENT, current)
            .putExtra(SyncNotificationService.EXTRA_PROGRESS_MAX, total)
        startSyncNotification(progressIntent)
        updateProgressBar(SyncProgress.phaseLabel(this, phase) ?: return, current, total)
    }

    /** Bottom progress bar + "phase: x/y" label (see progressBar/progressLabel, asked for
     * explicitly) -- fed from report() (download) and handleProgress()
     * (decode, building the trips), the same two signals the notification already shows as text. Hidden rather
     * than shown at 0/0 for a total <= 0 (nothing meaningful to show yet, or the phase hasn't
     * started). */
    private fun updateProgressBar(phase: String, current: Int, total: Int) {
        SyncState.lastProgressPhase = if (total > 0) phase else null
        SyncState.lastProgressCurrent = current
        SyncState.lastProgressTotal = total
        withActiveActivity {
            if (total <= 0) {
                progressBar.visibility = View.GONE
                progressLabel.visibility = View.GONE
                return@withActiveActivity
            }
            progressBar.visibility = View.VISIBLE
            progressLabel.visibility = View.VISIBLE
            progressBar.max = total
            progressBar.progress = current
            progressLabel.text = getString(R.string.progress_label_format, phase, current, total)
        }
    }

    private fun hideProgressBar() {
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        withActiveActivity {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
        }
    }

    /** The log view (see logList/LogAdapter) starts out filling the space the WebView would
     * otherwise waste while there's nothing to show it -- once a logbook actually loads, the log
     * shrinks back down to a small scrollable strip and the WebView takes the space instead. */
    private fun setLogExpanded(expanded: Boolean) {
        if (expanded) {
            logbookShownAsRunResult = false
            logList.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f)
        } else {
            val collapsedHeight = (SharedConstants.LOG_STRIP_HEIGHT * resources.displayMetrics.density).toInt()
            logList.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, collapsedHeight)
            webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // To the newest line once the new size is applied -- found in practice: the log strip kept a run's
        // result under the logbook, but showed the *top* of the log (the start of the run) and had to be
        // scrolled through to reach the end, which on a long log was hopeless. The scroll the log view
        // does itself when a line arrives measures the old (full-height) layout, so it cannot do this.
        logList.doOnLayout { scrollLogToEnd() }
    }

    /** publishFailed: the build succeeded but a publish attempted right after it (still before
     * this is called -- see runDownload()/buildFromLocalFilesAndMaybePublish()'s own comments on the
     * ordering) failed. The "Klaar: ..." line is still logged either way (the build itself did
     * succeed), but the WebView switch is skipped so the log -- which by now already has the
     * publish failure's own [error] line in it -- stays in front instead of covering it back up
     * a moment after showing it. */
    private fun showSyncResult(result: SyncResult, publishFailed: Boolean = false) {
        // What the end of the run says and shows is decided in Python (nmea2log/run_outcome.py), shared
        // with the iOS app so the two cannot drift apart; this turns the answer into log lines and views.
        // An ok result without a logbook file counts as the failure it is.
        val outcome = describeOutcome(result, publishFailed)
        var lastText = ""
        for (line in outcome.lines) {
            val text = outcomeText(line)
            lastText = text
            // The status texts (ready / stopped / error) are what the notification and a restored screen
            // show; the calm "not found" and "no files" lines are only log lines.
            if (line.key?.startsWith("status_") == true || line.key == "error_generic_prefix") {
                SyncState.lastStatusText = text
            }
            handleLogLine("[${line.level}] $text")
        }
        when (outcome.show) {
            "logbook" -> {
                setLogExpanded(false)
                loadLogbookIntoWebView(result.htmlPath!!)
                // The logbook is showing now, so the log button's next tap has to go back to the full
                // log, not load the same file again and hide the log entirely (asked for explicitly:
                // after a successful run the log couldn't be read without tapping it twice). Same as the
                // iOS app's own _log_result().
                showingLocalLogbook = true
                logbookShownAsRunResult = true
            }
            "log" -> if (result.ok) {
                // A publish that was attempted failed: the log, which already has its [error] line,
                // stays in front instead of being covered by the logbook a moment after showing it.
                setLogExpanded(true)
                scrollLogToEnd()
            }
            "existing_logbook" -> {
                // Not at the boat: the logbook that is already there is made ready. No system
                // notification (asked for explicitly: only for long-running processes) -- discovery
                // itself takes about a second.
                val existing = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
                if (existing.exists()) loadLogbookIntoWebView(existing.absolutePath)
            }
            "error" -> showOfflineOrCloseDialog(lastText)
        }
    }

    /** The log lines (with their [level] tag) nmea2log.run_outcome.describe_import() gives for an import result. */
    private fun describeImport(importResultJson: String): List<String> {
        val answer = JSONObject(
            Python.getInstance().getModule("nmea2log.run_outcome").callAttr("describe_import_json", importResultJson).toString(),
        )
        val lines = answer.getJSONArray("lines")
        return (0 until lines.length()).map { index ->
            val line = lines.getJSONObject(index)
            val params = line.getJSONObject("params")
            val outcome = OutcomeLine(
                line.getString("level"),
                if (line.isNull("key")) null else line.getString("key"),
                params.keys().asSequence().map { name -> if (params.isNull(name)) null else params.get(name) }.toList(),
                if (line.isNull("text")) null else line.getString("text"),
            )
            val text = outcomeText(outcome)
            if (outcome.level.isEmpty()) text else "[${outcome.level}] $text"
        }
    }

    private class OutcomeLine(val level: String, val key: String?, val params: List<Any?>, val text: String?)
    private class RunOutcome(val lines: List<OutcomeLine>, val show: String)

    /** nmea2log.run_outcome.describe_result() for [result]: the log lines (keys into the shared texts, which
     * are the string resources of the same name) and what to show afterwards. */
    private fun describeOutcome(result: SyncResult, publishFailed: Boolean): RunOutcome {
        val resultJson = JSONObject()
            .put("ok", result.ok && result.htmlPath != null)
            .put("cancelled", result.cancelled)
            .put("error", result.error ?: JSONObject.NULL)
            .put("trip_count", result.tripCount ?: JSONObject.NULL)
            .put("downloaded_count", result.downloadedCount ?: JSONObject.NULL)
        val initiator = if (SyncState.runInitiator == RunInitiator.DOWNLOAD) "download" else "build"
        val answer = JSONObject(
            Python.getInstance().getModule("nmea2log.run_outcome")
                .callAttr("describe_result_json", resultJson.toString(), initiator, publishFailed).toString(),
        )
        val lines = answer.getJSONArray("lines")
        return RunOutcome(
            (0 until lines.length()).map { index ->
                val line = lines.getJSONObject(index)
                val params = line.getJSONObject("params")
                OutcomeLine(
                    line.getString("level"),
                    if (line.isNull("key")) null else line.getString("key"),
                    params.keys().asSequence().map { name -> if (params.isNull(name)) null else params.get(name) }.toList(),
                    if (line.isNull("text")) null else line.getString("text"),
                )
            },
            answer.getString("show"),
        )
    }

    private fun outcomeText(line: OutcomeLine): String {
        line.text?.let { return it }
        val id = resources.getIdentifier(line.key, "string", packageName)
        val args = line.params.map { it ?: getString(R.string.error_unknown) }.toTypedArray()
        return getString(id, *args)
    }

    /** nmea2log.run_outcome.publish_failed(): whether the publish after a build failed -- only an upload
     * that was attempted (a destination is set up) and did not succeed. */
    private fun publishFailedAfter(didPublish: Boolean): Boolean =
        Python.getInstance().getModule("nmea2log.run_outcome")
            .callAttr(
                "publish_failed", didPublish, settingsStore.isRestUploadConfigComplete, settingsStore.isSftpConfigComplete,
            ).toBoolean()

    /** Reads the freshly-written logbook and feeds its content to the WebView directly, instead
     * of webView.loadUrl("file://$htmlPath") -- found in practice, a real regression (this exact
     * loadUrl() call had worked fine earlier this same session): a file:// navigation into the
     * app's own private storage started failing with net::ERR_ACCESS_DENIED, on-device, with
     * nothing in this app's own code having changed about how or where the file is written.
     * loadDataWithBaseURL() never makes the WebView navigate to a file:// URL at all -- the HTML
     * is handed over as a plain string -- sidestepping whatever changed about that policy rather
     * than chasing it. baseUrl is still the file's own directory (as a file:// URL), so any
     * relative resource reference the page itself makes (none right now, the logbook is fully
     * self-contained, but this keeps that option open) would still resolve correctly. */
    private fun loadLogbookIntoWebView(htmlPath: String) {
        val html = try {
            File(htmlPath).readText()
        } catch (e: Exception) {
            handleLogLine("[error] " + getString(R.string.error_logbook_display_failed, e.toString()))
            return
        }
        webView.loadDataWithBaseURL("file://${File(htmlPath).parent}/", html, "text/html", "utf-8", null)
        lastLoadedHtmlMtime = File(htmlPath).lastModified()
    }

    // Tracks which version of logbook.html is currently showing (see onResume()'s own reload
    // guard) -- lastModified(), not a content hash: cheap, and this file is only ever written
    // whole by write_html_logbook(), never appended to, so its mtime alone is enough to tell
    // "already showing this" from "there's a newer build to load".
    private var lastLoadedHtmlMtime: Long = -1L

    /** Two-button dialog for "the app just tried to reach the W2K-2 (on launch or on retry) and
     * that didn't work" -- asked for explicitly, covers both the hotspot-not-detected case and any
     * other sync failure uniformly, instead of leaving the user looking at a status line with no
     * obvious next step. Not cancelable by tapping outside/back -- one of the two buttons is the
     * only way out.
     *
     * No "wait for connection" option (an earlier version had one, polling in the background) --
     * found in practice not actually useful, closing the app and trying again later reads better
     * than a long silent wait with an uncertain outcome. Offers building/showing the logbook from
     * whatever's already been downloaded instead, since that's genuinely useful precisely when the
     * W2K-2 can't be reached right now (asked for explicitly), and closing the app remains always
     * available as the simple way out.
     *
     * Deferred to onResume() instead of shown right away if the Activity isn't currently visible
     * (regression, found in practice: a real crash, android.view.WindowManager$BadTokenException
     * "token ... is not valid; is your activity running?") -- since the app now minimizes itself
     * shortly after an auto-started sync begins (see autoStartSyncWithSettingsRetry()), a sync
     * that then fails to even find the W2K-2 calls this from a background thread's callback well
     * after that minimize already happened, and AlertDialog.show() can't add a new window to an
     * Activity that isn't in the foreground. The log already has the failure line either way (by
     * the caller, before this is even called) so it's still reflected the moment the app is next
     * opened, dialog or not. */
    private fun showOfflineOrCloseDialog(message: String) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingOfflineOrCloseMessage = message
            return
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_build_logbook_button)) { _, _ -> runOfflineBuild() }
            .setNegativeButton(getString(R.string.dialog_close_app_button)) { _, _ -> closeAppAndCancelSync() }
            .show()
    }

    /** Shared core of closeAppAndCancelSync() and cancelSyncStayInApp() below -- sets the flag
     * runDownload()'s background Thread checks (both during download, between files, and during
     * decode, see run_pipeline()'s should_cancel) and actually stops SyncNotificationService,
     * rather than just leaving it: that service has android:stopWithTask="false" (see the
     * manifest), so it wouldn't otherwise notice a cancellation that doesn't also finish this
     * Activity (found in practice, for the app-close case this was originally written for). */
    private fun cancelSync() {
        SyncState.cancelled = true
        SyncNotificationService.stop(this)
        SyncState.notificationForegrounded = false
        SyncState.notificationStartFailed = false
    }

    /** "App sluiten" above -- cancelSync() plus actually leaving, unlike cancelSyncStayInApp()
     * below. Used to also leave behind a plain, dismissible "tap to reopen" notification (the
     * same one onTaskRemoved() below still posts for a swipe-away) -- dropped here specifically,
     * asked for explicitly: a real close via this button left a notification sitting in the
     * shade, which read as "still not actually closed" rather than the convenience it was meant
     * to be. Explicitly cancels both notification ids too, not just relying on cancelSync()'s own
     * stopService() -- that only tears down the foreground service's own notification (id 1);
     * nothing else here would otherwise clear an already-posted reopen notification (id 2) if one
     * happened to exist from an earlier close.
     *
     * finishAffinity() alone (the "normal" way to close every Activity in the task) still leaves
     * the process itself alive in the background -- ordinarily fine (that's how most Android apps
     * behave, including this one everywhere else), but found in practice: asked for explicitly
     * that this specific button, unlike just backgrounding the app, actually exits -- Process.
     * killProcess() is the standard way to guarantee that, rather than leave it to the OS's own
     * discretion about when (or whether) to reclaim a merely-backgrounded process. */
    private fun closeAppAndCancelSync() {
        cancelSync()
        NotificationManagerCompat.from(this).cancel(SyncNotificationService.NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(SyncNotificationService.REOPEN_NOTIFICATION_ID)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Tapping the download button again while the app's own auto-start download (see
     * autoStartSyncWithSettingsRetry()) is already running it -- asked for explicitly: that
     * auto-start has no way to be skipped, so opening the app to use ☁️ Publiceren on its own
     * (re-send an already-built logbook.html without a fresh download) was never actually
     * reachable, both buttons stay disabled for as long as the auto-started download keeps
     * running. Unlike closeAppAndCancelSync(), the app stays open and runDownload()'s own Thread
     * (once it notices the cancellation, same as any other cancelled download) re-enables both
     * buttons itself in its finally block -- nothing else to do here. */
    private fun cancelSyncStayInApp() {
        cancelSync()
        val cancelledText = getString(
            if (SyncState.runInitiator == RunInitiator.DOWNLOAD) R.string.status_sync_cancelled else R.string.status_build_cancelled,
        )
        SyncState.lastStatusText = cancelledText
        handleLogLine("[info] $cancelledText")
        hideProgressBar()
        // The background thread only notices SyncState.cancelled between discrete steps (before
        // each file, or once a season-wide trip-cache load finishes -- see should_cancel() in
        // pipeline.py) -- on a slow device that trip-cache load alone can take well over ten
        // seconds, so nothing else happens in the log for a while after "geannuleerd" (asked for
        // explicitly, found in practice: reported as a "vreemde logregel" because whatever else
        // happened to be logged in that gap, e.g. boot mode starting up, ended up sitting right
        // before the eventual "gestopt" line, making it look connected to that when it wasn't).
        // SyncState.cancelled is checked again here, not just inProgress, so this stays silent if
        // a fresh run started in the meantime (its own start resets cancelled to false).
        android.os.Handler(mainLooper).postDelayed(
            {
                if (SyncState.inProgress && SyncState.cancelled) {
                    handleLogLine("[info] " + getString(R.string.status_cancel_still_stopping))
                }
            },
            3000L,
        )
    }

    /** Builds and shows the logbook from whatever .ebl files are already sitting in
     * eblDownloadDir() -- no W2K-2 connection needed at all, for exactly the case that's
     * otherwise a dead end: the
     * device can't be reached right now, but there's still real (if possibly not fully current)
     * data already on the phone worth seeing (asked for explicitly). Publishes it too, same as a
     * normal sync's own auto-publish, if the SFTP settings are filled in. */
    private fun runOfflineBuild() {
        buildFromLocalFilesAndMaybePublish(forcePublish = false)
    }

    /** Shared by runOfflineBuild() (the offline/close dialog's "Logboek bouwen..." button) and
     * runPublish() (the ☁️ icon) -- both build the logbook from whatever .ebl files are already
     * on the phone, no W2K-2 connection needed. forcePublish=false keeps runOfflineBuild()'s own
     * existing behavior (gated on "Automatisch publiceren na bouwen"); runPublish() passes true
     * instead, since tapping ☁️ is itself the explicit request to publish, same as it already was
     * before this was shared. */
    private fun buildFromLocalFilesAndMaybePublish(forcePublish: Boolean) {
        if (SyncState.inProgress) return
        if (bootModeBusy()) return
        SyncState.inProgress = true
        SyncState.runInitiator = if (forcePublish) RunInitiator.PUBLISH else RunInitiator.BUILD
        // Reset here too, not only in runDownload(): a cancelled earlier run leaves it true, which
        // would cancel this new build the moment it starts.
        SyncState.cancelled = false
        // The button that started this build stays enabled as its cancel button -- a long local
        // decode (see run_pipeline()'s should_cancel) should be cancellable by tapping it again,
        // same as a normal sync with the sync button.
        updatePublishButtonEnabled()
        // Log deliberately NOT cleared here (asked for explicitly, see runDownload()'s own matching
        // comment) -- it accumulates across every run this process makes instead.
        SyncState.lastStatusText = getString(R.string.status_building_with_existing_data)
        handleLogLine("[info] ${SyncState.lastStatusText}")
        // Shown immediately, same as runDownload()'s own startIntent -- asked for explicitly, found
        // in practice: listing every .ebl file and loading the trip cache can itself take well
        // over ten seconds on a big archive before handleLogLine()'s first real progress line
        // ever arrives, during which nothing was visible outside the app at all before this (read
        // as "no notification at all", not just a slow one, since nobody kept watching that long).
        SyncState.lastNotificationText = SyncState.lastStatusText
        val startIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, SyncState.lastStatusText)
        startSyncNotification(startIntent)
        SyncState.lastProgressPhase = null
        SyncState.lastProgressCurrent = 0
        SyncState.lastProgressTotal = 0
        showingLocalLogbook = false
        setLogExpanded(true)

        acquireManualRunWakeLock()
        Thread {
            var syncSucceeded = false
            var didPublish = false
            try {
                val result = buildFromLocalFiles()
                syncSucceeded = result.ok
                // Before showing the logbook, not after -- see runDownload()'s own matching comment.
                // Same "Automatisch publiceren na bouwen" gate as runDownload()'s own matching call,
                // unless forcePublish overrides it (see this function's own doc comment).
                var publishFailed = false
                if (result.ok && result.htmlPath != null && (forcePublish || settingsStore.autoPublishAfterBuild)) {
                    didPublish = uploadIfConfigured(result.htmlPath)
                    publishFailed = publishFailedAfter(didPublish)
                }
                withActiveActivity { showSyncResult(result, publishFailed) }
                if (syncSucceeded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    handleLogLine(getString(R.string.log_sync_done_at, timeText))
                }
            } catch (e: Exception) {
                val message = getString(R.string.error_unexpected, e.toString())
                SyncState.lastStatusText = message
                handleLogLine("[error] $message")
            } finally {
                // Same "Voltooid"-completion treatment as runDownload() -- see its own finally for
                // the full reasoning. Only posted if a notification was ever actually shown for
                // this run (see startSyncNotification()'s own eligibility check) -- this path,
                // Only posted if a notification was ever actually shown for this run (see
                // startSyncNotification()'s own eligibility check).
                SyncNotificationService.stop(this)
                if (syncSucceeded && SyncState.notificationForegrounded) {
                    val timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    SyncNotificationService.postCompletionNotification(
                        this,
                        getString(R.string.notif_sync_done_at, timeText),
                        if (didPublish) MainActivity.LIVE_SITE_URL else null,
                        R.drawable.ic_refresh_24,
                    )
                }
                SyncState.notificationForegrounded = false
                SyncState.notificationStartFailed = false
                SyncState.inProgress = false  // must always happen, see runDownload()'s own finally
                SyncState.runInitiator = null
                releaseManualRunWakeLock()
                withActiveActivity {
                    updateSyncButtonAvailability()
                    updatePublishButtonEnabled()
                    hideProgressBar()
                }
            }
        }.start()
    }

    /** Every SD card or USB drive currently mounted -- checked before importButton ever opens the
     * system folder picker (see its own onClick above), both to skip the picker entirely with
     * nothing attached and to jump straight into the one that is. StorageManager's own volume
     * list, not just Environment.getExternalStorageDirectory() (that one only ever covers
     * internal/primary storage): every non-primary entry here is removable media the OS itself
     * knows about, SD card or USB drive alike, regardless of how the picker will label it. */
    private fun removableStorageVolumes(): List<StorageVolume> {
        val storageManager = getSystemService(STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        return storageManager.storageVolumes.filter { !it.isPrimary }
    }

    /** Recursively collects every .ebl file under [dir], at any depth -- SD/USB media doesn't
     * have to mirror Actisense's own folder structure (asked for explicitly), so this doesn't
     * assume any particular layout, just walks everything the picked tree contains.
     *
     * [isDisconnected] is checked before each dir.listFiles() call, not a wall-clock timeout
     * around it (tried first, found wanting, removed -- asked for explicitly, "timers zijn een
     * slecht idee, effect hangt af van cpu-snelheid"; the real problem it was papering over is
     * that a blocked SAF read over a genuinely gone connection can't be force-cancelled from here
     * at all, timer or not -- found in practice, the same session: the read only ever actually
     * failed once Android itself noticed the drive was gone and tore the connection down, not
     * because of anything a timeout did). importFromRemovableMedia() registers a real,
     * event-driven listener for exactly that OS-level "the drive is gone now" signal (see its own
     * comment) instead of guessing at how long is too long to wait -- this just stops the scan
     * from *starting* another subtree once that signal has actually arrived, same reasoning as
     * stageForImport()'s own check. */
    private fun findEblFiles(dir: DocumentFile, isDisconnected: () -> Boolean): List<DocumentFile> {
        if (isDisconnected()) return emptyList()
        val result = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            if (isDisconnected()) break
            if (child.isDirectory) {
                result += findEblFiles(child, isDisconnected)
            } else if (child.isFile && child.name?.endsWith(".ebl", ignoreCase = true) == true) {
                result += child
            }
        }
        return result
    }

    /** Same two patterns nmea2log's own logfile_layout.py/import_ebl.py check the archive
     * against (kept in sync by hand -- this is Kotlin, that's Python, neither can import the
     * other's regex). Used only to decide, while staging, whether a picked file's own immediate
     * parent folder name is worth preserving (see stageForImport() below); import_ebl.py re-
     * checks the same thing itself once the files are on local disk, so a wrong guess here just
     * means that one file is treated as a loose, non-EBLnnnnnn file instead -- never a crash. */
    private val eblFolderName = Regex("^EBL\\d{6}$")
    private val eblFileName = Regex("^\\d{6}_\\d{3}\\.ebl$")

    /** Copies every .ebl file found under the picked SAF tree into a local scratch directory
     * import_ebl.py can actually open (a content:// Uri isn't a path plain Python can read) --
     * preserving each source file's own immediate parent folder name when it looks like one of
     * the W2K-2's own "EBLnnnnnn" folders (see import_ebl.py's own module doc comment for why
     * that identity matters), so that module can tell a real W2K-2 session apart from an
     * arbitrary one. A same-named loose (non-EBLnnnnnn) file colliding with an earlier one in
     * this same batch gets a "-1"/"-2" suffix here, purely to survive the copy itself -- distinct
     * loose files landing under slightly different names in the archive is harmless; silently
     * losing one of them to a same-name overwrite before import_ebl.py even sees it would not be.
     * Reports [onProgress] (current, total, this file's own name) after each file so the caller
     * can drive the same progress bar/status-text/notification runDownload()'s own per-file report()
     * already does (asked for explicitly, "uniformiteit is belangrijk": copying a real USB
     * drive's worth of files one SAF round-trip at a time is itself slow enough to need its own
     * visible progress, the same way download already shows one file at a time passing by).
     * Returns the staged files' own paths, ready to hand to import_staged_ebl_files_json(), and
     * how many of [sourceFiles] could not actually be read (see the loop's own comment) --
     * silently importing fewer files than were found, with no sign anything was skipped, is
     * worse than a slower import: found in practice, a real, serious bug -- a USB drive whose
     * own SAF provider started failing queries partway through a 594-file scan (Android's
     * DocumentFile swallows that failure internally and returns a null name/false isFile instead
     * of raising it, logging only its own "W DocumentFile: Failed query" line nothing in this
     * app ever saw) silently dropped 552 of those 594 files from the import with a perfectly
     * clean-looking "42 .ebl-bestand(en) geïmporteerd" success line -- and, compounding that,
     * those files' own EBLnnnnnn folder name happened to already exist as a normal download
     * elsewhere without a year layer (a *separate* bug, now fixed in import_ebl.py's own
     * _find_existing_ebl_folder()), so the 42 that did make it in were quietly duplicated on disk
     * instead of being recognized as already present.
     *
     * Gives up on the rest of [sourceFiles] the moment [isDisconnected] says so -- checked before
     * each file, not a wall-clock timeout around each read (see findEblFiles()'s own doc comment
     * for why that was tried first and removed: this is the same real, event-driven "the drive is
     * gone" signal, not a guess). A per-file try/catch still covers an ordinary read failure that
     * *doesn't* come with that signal (one flaky file among otherwise-good ones, still rare but
     * not unheard of) -- that alone never aborts anything, just counts as unreadable and moves on;
     * only [isDisconnected] itself stops the loop early.
     *
     * Returns the staged files' own paths, how many of [sourceFiles] could not actually be read,
     * and whether [isDisconnected] is why the loop stopped short of the full list. */
    private fun stageForImport(
        sourceFiles: List<DocumentFile>,
        stagingDir: File,
        isDisconnected: () -> Boolean,
        onProgress: (Int, Int, String) -> Unit,
    ): Triple<List<File>, Int, Boolean> {
        val staged = mutableListOf<File>()
        var unreadable = 0
        for ((index, sourceFile) in sourceFiles.withIndex()) {
            if (isDisconnected()) {
                return Triple(staged, unreadable, true)
            }
            // A null name here is the actual symptom of the SAF query failure findEblFiles()'s
            // own doc comment describes, not a real property of the file -- every DocumentFile it
            // returns passed this same check once already, during the scan; this can still be the
            // first time it fails for a given file, since DocumentFile re-queries the provider on
            // every access rather than caching what listFiles() first saw.
            val name = sourceFile.name
            if (name == null) {
                unreadable++
                onProgress(index + 1, sourceFiles.size, "?")
                continue
            }
            val parentName = sourceFile.parentFile?.name.orEmpty()
            val destDir = if (eblFolderName.matches(parentName) && eblFileName.matches(name)) {
                File(stagingDir, parentName)
            } else {
                stagingDir
            }
            destDir.mkdirs()
            var destFile = File(destDir, name)
            var suffix = 1
            while (destFile.exists()) {
                val dot = name.lastIndexOf('.')
                val base = if (dot >= 0) name.substring(0, dot) else name
                val ext = if (dot >= 0) name.substring(dot) else ""
                destFile = File(destDir, "$base-$suffix$ext")
                suffix++
            }
            // A per-file try/catch, not the whole loop's own (see importFromRemovableMedia()'s
            // own FileNotFoundException/SecurityException handling, still there for a connection
            // lost before staging ever starts) -- one flaky file out of hundreds shouldn't abort
            // an otherwise-good import; unreadable's own count and the [warning] line it drives
            // surfaces the problem instead of hiding it, without throwing away what did work.
            //
            // Up to FILE_READ_MAX_RETRIES retries (three attempts total) before giving up on this
            // one file, the same convention w2k2_download.py's own _DOWNLOAD_MAX_RETRIES (and
            // geocode.py's/open_meteo.py's own lookup retries) already use for a single transient
            // failure -- asked for explicitly, "graag in totaal 3x proberen, net als bij url's".
            // A short pause between attempts, not on the first one -- same shape as that Python
            // retry loop's own "if attempt: sleep(...)" -- gives a momentary provider hiccup a
            // real chance to clear before trying again, instead of hammering it back to back.
            // Doesn't help a *hung* read that never returns at all (no attempt ever finishes, so
            // there's nothing here to retry) -- that's isDisconnected()'s own job, checked at the
            // top of this loop, not this.
            var copied = false
            for (attempt in 0..FILE_READ_MAX_RETRIES) {
                if (attempt > 0) Thread.sleep(FILE_READ_RETRY_DELAY_MS)
                copied = try {
                    contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                } catch (e: java.io.IOException) {
                    false
                } catch (e: IllegalArgumentException) {
                    // Found in practice, live: pulling the SD card mid-copy doesn't always surface
                    // as an IOException here -- the framework's own scoped-storage permission check
                    // on openInputStream() (SAF verifying this document is still a legitimate child
                    // of the granted tree, done via the provider's own isChildDocument()) throws
                    // this instead when the root itself is already gone ("Failed to determine if
                    // ... is child of ...: FileNotFoundException: No root for <volume>"), wrapping
                    // what is really the exact same "the drive is gone" condition IOException would
                    // have meant, in a type that check wasn't catching. Treated identically: still
                    // retried above (harmless if the root really is gone -- isDisconnected() at the
                    // top of the outer loop is what actually stops things, same as any other
                    // unreadable file), and until it does, this one file is just unreadable rather
                    // than an uncaught crash the outer catch(Exception) reported as "Onverwachte
                    // fout" -- misleading, since nothing was actually wrong with the app.
                    false
                }
                if (copied) break
            }
            if (!copied || destFile.length() == 0L) {
                destFile.delete()
                unreadable++
                onProgress(index + 1, sourceFiles.size, name)
                continue
            }
            staged += destFile
            onProgress(index + 1, sourceFiles.size, name)
        }
        return Triple(staged, unreadable, false)
    }

    /** importButton's own handler (see importFolderLauncher): stages every .ebl file found under
     * the picked tree (see stageForImport() above), then hands the staged paths to nmea2log's
     * shared import_ebl.import_staged_ebl_files() (via android_entry's JSON-wrapped
     * import_staged_ebl_files_json() -- see its own doc comment for why a JSON string, not the
     * raw dict, crosses the Chaquopy boundary here) -- that module owns the actual placement
     * (by year, preserving the source's own EBLnnnnnn structure), duplicate-skip and reformatted-
     * SD-card-collision handling, exactly the same way for both this app and the iOS one. Once
     * imported, hands off to the exact same buildFromLocalFilesAndMaybePublish() pipeline
     * runDownload()/runOfflineBuild() already use, so the freshly imported files get decoded,
     * assembled and (per the usual "Automatisch publiceren na bouwen" setting) published like any
     * other .ebl files already on the device would be. */
    private fun importFromRemovableMedia(treeUri: Uri) {
        if (SyncState.inProgress) return
        if (bootModeBusy()) return
        showingLocalLogbook = false
        setLogExpanded(true)
        // Same "a run is in progress" bookkeeping runDownload()/buildFromLocalFilesAndMaybePublish()
        // use (asked for explicitly, found in practice: importButton.isEnabled = false on its own
        // left downloadButton/buildButton/publishButton fully tappable during an import, unlike every
        // other long-running action here) -- updatePublishButtonEnabled() below now disables
        // those and pulses importButton itself the same way, purely from this state. Also
        // required for the real system notification just below: without lastStatusText/
        // lastNotificationText set and a real notification actually up, onResume()'s own restore
        // logic (see its own doc comment on SyncState.lastNotificationText) found inProgress=true
        // with nothing to restore from and fell back to "Downloaden loopt al...", which read as a
        // stale/wrong download notification rather than the import actually running.
        SyncState.inProgress = true
        SyncState.runInitiator = RunInitiator.IMPORT
        SyncState.lastStatusText = getString(R.string.status_importing)
        SyncState.lastNotificationText = SyncState.lastStatusText
        handleLogLine("[info] ${SyncState.lastStatusText}")
        val startIntent = Intent(this, SyncNotificationService::class.java)
            .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, SyncState.lastStatusText)
        startSyncNotification(startIntent)
        updatePublishButtonEnabled()
        acquireManualRunWakeLock()
        // A real, event-driven "the removable drive is gone" signal, not a wall-clock timeout
        // around each SAF read (asked for explicitly, "timers zijn een slecht idee" -- see
        // findEblFiles()'s own doc comment for the full reasoning and what was tried first).
        // ACTION_MEDIA_* covers an SD card being ejected/removed the normal way; USB_DEVICE_DETACHED
        // covers a USB drive being physically unplugged -- registered together since either kind
        // of media can be picked here, and there's no way to know ahead of time which this is.
        val mediaDisconnected = AtomicBoolean(false)
        val mediaDisconnectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mediaDisconnected.set(true)
            }
        }
        ContextCompat.registerReceiver(
            this,
            mediaDisconnectReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addDataScheme("file")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            mediaDisconnectReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Thread {
            // Found in practice, live: a fresh process whose very first Python-touching action is
            // an import (no download/build/publish already run first, each of which happens to
            // call this on their own) reached the Python call below with Python never actually
            // started, surfacing as "RuntimeException: Cannot use GenericPlatform on Android" --
            // not an import-specific bug, just the one path here that was missing the same call
            // every other Python entry point (syncFromW2k2(), buildFromLocalFiles(), etc.) already
            // makes for itself, unconditionally, before touching Python.
            PythonStarter.ensureStarted(this)
            var importedCount = 0
            val stagingDir = File(cacheDir, "ebl-import-${System.currentTimeMillis()}")
            try {
                val sourceRoot = DocumentFile.fromTreeUri(this, treeUri)
                // Its own distinct status/notification text, not left on the generic
                // "Importing .ebl files..." kickoff line above (asked for explicitly, "volstrekt
                // onduidelijk wat hij aan het doen is") -- findEblFiles() below is a plain
                // recursive directory walk with no per-file callback of its own (the total isn't
                // even known yet at this point), so without this the owner would otherwise see
                // nothing change at all -- not the log, not the notification, not even the
                // progress bar -- for however long that walk takes on a large card. Same one-time
                // phase-transition shape runDownload() already uses for its own "Bestandenlijst
                // ophalen..." line between hotspot-check and the live per-file download updates.
                val searchingText = getString(R.string.status_searching_files)
                SyncState.lastStatusText = searchingText
                SyncState.lastNotificationText = searchingText
                handleLogLine("[info] $searchingText")
                startSyncNotification(
                    Intent(this, SyncNotificationService::class.java)
                        .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, searchingText),
                )
                val sourceFiles = if (sourceRoot != null) findEblFiles(sourceRoot) { mediaDisconnected.get() } else emptyList()
                if (sourceFiles.isEmpty()) {
                    handleLogLine("[info] " + getString(R.string.log_import_no_files))
                } else {
                    // One-time phase-transition line (asked for explicitly, "logregel blijft
                    // staan op zoek, terwijl balk loopt") -- without this, the log stayed on
                    // searchingText above for the whole copying phase below (which, like a
                    // download's own per-file report(), deliberately has no line of its own per
                    // file -- see its own comment), reading as stuck even while the bar/
                    // notification kept moving. Same gap turned out to exist in runDownload() too
                    // (see syncFromW2k2()'s own matching fix, "%1$d file(s) need downloading" --
                    // added there for the same reason, at the same point in that flow).
                    handleLogLine("[info] " + getString(R.string.log_import_copying_started, sourceFiles.size))
                    stagingDir.mkdirs()
                    val (staged, unreadableCount, abortedEarly) = stageForImport(
                        sourceFiles, stagingDir, { mediaDisconnected.get() },
                    ) { current, total, fileName ->
                        // "Copying", not "Importing" (asked for explicitly) -- this phase is only
                        // the SAF-to-local-scratch-file copy (see stageForImport()'s own doc
                        // comment); the actual import -- deciding per file whether it's new,
                        // already present, or a same-name-different-content collision, and placing
                        // it in the archive -- is the separate phase below, which keeps its own
                        // "Importing" label.
                        updateProgressBar(getString(R.string.phase_copying), current, total)
                        // Same per-file notification/status-text update as a download's own
                        // SyncController.report() (asked for explicitly, "uniformiteit is
                        // belangrijk") -- the file name visibly cycling in the notification
                        // shade is what "elk bestand voorbijkomen" was actually describing there,
                        // not a per-file log line (report() deliberately skips one of those too,
                        // for the same reason: easily hundreds of files, see its own comment).
                        val text = getString(R.string.status_copying_progress, current, total, fileName)
                        SyncState.lastStatusText = text
                        SyncState.lastNotificationText = text
                        // Not wrapped in withActiveActivity (unlike updateProgressBar() above,
                        // which already does its own internally): Context.startService()/
                        // startForegroundService() are safe to call off the main thread, same as
                        // SyncController.report()'s own matching call for a download.
                        startSyncNotification(
                            Intent(this, SyncNotificationService::class.java)
                                .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
                                // A real progress bar in the notification too, not just text
                                // (asked for explicitly, "kan logbalk ook in melding, zoals bij
                                // download?") -- same EXTRA_PROGRESS_CURRENT/EXTRA_PROGRESS_MAX
                                // pair SyncController.report() already sends for a download; left
                                // out before now, which is why this notification's text updated
                                // per file but never grew an actual bar the way the in-app one
                                // (updateProgressBar() above) already did. Deliberately not also
                                // EXTRA_CURRENT/EXTRA_TOTAL/EXTRA_FILE_NAME -- those three together
                                // make SyncNotificationService.onStartCommand() build its own
                                // status_downloading-worded text instead of using EXTRA_STATUS_TEXT
                                // above, which would show the wrong ("Downloading: ...") wording
                                // here.
                                .putExtra(SyncNotificationService.EXTRA_PROGRESS_CURRENT, current)
                                .putExtra(SyncNotificationService.EXTRA_PROGRESS_MAX, total),
                        )
                    }
                    if (abortedEarly) {
                        // See stageForImport()'s own doc comment: five failures in a row (the
                        // actual signature of the whole connection being gone, not just one bad
                        // file) stops the scan there instead of racing through every remaining
                        // file -- each failing near-instantly -- to a misleadingly "complete"
                        // progress bar (asked for explicitly, found in practice: that's exactly
                        // what pulling the drive mid-import looked like before this). Just this
                        // one [error] line, not also the plain [warning] below (asked for
                        // explicitly, found in practice: showing both for the same underlying
                        // "the drive is gone" event read as two different problems instead of
                        // one) -- unreadableCount here is a subset of what this line already
                        // explains (staged.size vs. sourceFiles.size), not separate information.
                        handleLogLine(
                            "[error] " + getString(R.string.log_import_aborted, staged.size, sourceFiles.size),
                        )
                    } else if (unreadableCount > 0) {
                        // Scattered, non-consecutive failures that never reached the abort
                        // threshold -- a real, standalone problem worth its own line here, unlike
                        // the aborted case above where it would just repeat what that [error]
                        // line already says.
                        handleLogLine("[warning] " + getString(R.string.log_import_unreadable, unreadableCount))
                    }
                    val importStart = System.currentTimeMillis()
                    val actisenseDir = EblStorage.downloadDir(this)
                    // Same one-time phase-transition line as log_import_copying_started above,
                    // between copying and this next phase (asked for explicitly, same fix).
                    handleLogLine("[info] " + getString(R.string.log_import_importing_started, staged.size))
                    // One line per newly-imported file, reported live as each one actually lands --
                    // the same "Python calls back into Kotlin per file, during its own real work"
                    // shape SyncController.report() already uses for a download (see
                    // ImportProgressCallback's own doc comment: root-caused, not the earlier fix
                    // here, which tried to fake this from the Kotlin side after
                    // import_staged_ebl_files_json() had already finished all the real work, with
                    // nothing left to pace a replay loop over its own, already-decided result).
                    //
                    // "skipped_duplicate" deliberately does NOT get its own live line here (asked
                    // for explicitly, "gelijk maken" -- this used to log both) -- matches
                    // w2k2_download.py's own "[skip] ... already complete locally" being logged at
                    // level="debug" rather than the default "info" (see download_file()'s own
                    // comment there: "a normal day-to-day sync with a couple thousand files already
                    // local produced that many lines on screen... for zero new information,
                    // drowning out the handful of lines that actually mattered"). Exactly the same
                    // reasoning applies here -- a reformatted or previously-imported SD card can
                    // just as easily be mostly duplicates. Still counted (see the "skipped" summary
                    // line below, and result.getInt("skipped_duplicate")) -- only the one line per
                    // duplicate file is gone, not the information that it happened.
                    val progressCallback = object : ImportProgressCallback {
                        override fun report(current: Int, total: Int, name: String, outcome: String) {
                            if (outcome == "imported") {
                                handleLogLine("[info] " + getString(R.string.log_import_copied, name))
                            }
                            updateProgressBar(getString(R.string.phase_importing), current, total)
                            // Same notification update the copying phase above already does (asked
                            // for explicitly, "volstrekt onduidelijk wat hij aan het doen is...
                            // zelfde als download") -- this phase used to update only the in-app
                            // bottom bar, leaving the notification frozen on whatever text/progress
                            // copying last left it at for the entire importing phase, same bug
                            // findEblFiles()'s own scanning phase still has (no per-file signal to
                            // report during a plain directory walk, unlike this one).
                            val text = getString(R.string.status_importing_progress, current, total, name)
                            SyncState.lastStatusText = text
                            SyncState.lastNotificationText = text
                            startSyncNotification(
                                // this@MainActivity, not this -- this is an anonymous
                                // ImportProgressCallback object, whose own bare `this` is itself,
                                // not the enclosing Activity (found while writing this: it would
                                // otherwise fail to compile, Intent() has no overload taking an
                                // ImportProgressCallback).
                                Intent(this@MainActivity, SyncNotificationService::class.java)
                                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, text)
                                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_CURRENT, current)
                                    .putExtra(SyncNotificationService.EXTRA_PROGRESS_MAX, total),
                            )
                        }
                    }
                    val resultJson = Python.getInstance().getModule("nmea2log.android_entry").callAttr(
                        "import_staged_ebl_files_json",
                        staged.map { it.absolutePath }.toTypedArray(),
                        actisenseDir.absolutePath,
                        progressCallback,
                    ).toString()
                    val result = JSONObject(resultJson)
                    importedCount = result.getInt("imported")
                    // What the end of the import says (renamed and failed files as warnings, then one summary
                    // line) is decided in Python, shared with the iOS app: nmea2log/run_outcome.py.
                    for (line in describeImport(resultJson)) handleLogLine(line)
                    if (importedCount > 0) {
                        EblStorage.indexForPc(applicationContext, actisenseDir, importStart)
                    }
                }
            } catch (e: FileNotFoundException) {
                // The SD card/USB drive was pulled mid-import (asked for explicitly: this used to
                // fall through to the generic "Unexpected error: java.io.FileNotFoundException:
                // ..." below, which read as an app bug rather than the mundane, expected "the
                // cable/card came loose" it actually was every time it was seen in practice).
                handleLogLine("[error] " + getString(R.string.log_import_media_disconnected))
            } catch (e: SecurityException) {
                // Same underlying cause as above -- removing the media (or just its own OS-level
                // permission grant expiring) revokes this app's access to the picked tree, which
                // surfaces as a SecurityException on the next SAF call rather than a
                // FileNotFoundException.
                handleLogLine("[error] " + getString(R.string.log_import_media_disconnected))
            } catch (e: IllegalArgumentException) {
                // Same underlying cause again, seen live during findEblFiles()'s own scan (before
                // staging -- see stageForImport()'s per-file catch for the same failure caught
                // there instead, once staging is under way): the framework's scoped-storage
                // ancestry check (provider's own isChildDocument()) throws this, not
                // FileNotFoundException/SecurityException directly, when the root is already gone.
                // A backstop for any SAF call site this class doesn't already wrap in its own
                // retry, not a substitute for one -- see stageForImport()'s own comment for why the
                // per-file copy loop still needs its own matching catch instead of relying on this.
                handleLogLine("[error] " + getString(R.string.log_import_media_disconnected))
            } catch (e: Exception) {
                handleLogLine("[error] " + getString(R.string.error_unexpected, e.toString()))
            } finally {
                try {
                    unregisterReceiver(mediaDisconnectReceiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered, or never actually registered (both registerReceiver()
                    // calls above throwing would have skipped straight past the try block that
                    // needed this) -- never actually reachable given the two calls right before
                    // Thread{}.start() above always run first, but harmless either way.
                }
                stagingDir.deleteRecursively()
                releaseManualRunWakeLock()
                // No completion notification of its own here (unlike download/build's own
                // finally blocks) -- deliberately: an import that found anything to import always
                // continues straight into buildFromLocalFilesAndMaybePublish() below, which starts
                // its own fresh notification and posts its own "Klaar" completion when *that*
                // finishes -- posting one here too would just be two notifications in a row for
                // what reads as one action from the owner's side. Still stopped/reset here so that
                // fresh notification actually starts fresh, instead of trying to update this
                // (about to be pointless) one.
                SyncNotificationService.stop(this)
                SyncState.notificationForegrounded = false
                SyncState.notificationStartFailed = false
                SyncState.inProgress = false
                SyncState.runInitiator = null
                withActiveActivity {
                    updatePublishButtonEnabled()
                    hideProgressBar()
                }
                if (importedCount > 0) {
                    withActiveActivity { buildFromLocalFilesAndMaybePublish(forcePublish = false) }
                }
            }
        }.start()
    }

    /** Chaquopy call to android_entry.build_from_local_files() -- decode/build/write only, no
     * discovery or download, over every .ebl file already present under eblDownloadDir(). */
    private fun buildFromLocalFiles(): SyncResult {
        PythonStarter.ensureStarted(this)
        val androidEntry = Python.getInstance().getModule("nmea2log.android_entry")

        val downloadDir = eblDownloadDir()
        val outputHtmlPath = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
        val sampleCachePath = File(filesDir, SharedConstants.SAMPLE_CACHE_FILE_NAME)
        // A plain array, not a Kotlin List -- found in practice: passing a List straight across
        // the Chaquopy boundary via callAttr() reached Python as something that raised
        // "TypeError: 'ArrayList' object is not iterable" the moment run_pipeline() tried to
        // iterate over it, apparently never actually exercised before today (this offline-build
        // path had no way to be reached without crashing the app first -- see
        // showOfflineOrCloseDialog()'s own fix). A String[] converts to a genuine Python
        // list/tuple instead.
        val eblPaths = downloadDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ebl", ignoreCase = true) }
            .map { it.absolutePath }
            .toList().toTypedArray()

        // Captured by the controller's onResult() below, not read back out of callAttr()'s own
        // return value afterward -- see SyncController.onResult()'s own doc comment for why.
        var capturedResult: SyncResult? = null

        // report() and onDownloadComplete() don't apply here (nothing is downloaded on this
        // path) -- only onLogLine() (see handleLogLine(), shared with syncFromW2k2()'s own
        // controller, found in practice: this call used to have none of this wiring at all, so a
        // real decode left the screen stuck on one static message) and isCancelled() (lets the ✕
        // button stop a long offline decode too, same as a normal sync) are meaningful.
        val controller = object : SyncController {
            override fun report(current: Int, total: Int, fileName: String) {}
            override fun isCancelled(): Boolean = SyncState.cancelled
            override fun onLogLine(line: String) = handleLogLine(line)

            override fun onProgress(phase: String, current: Int, total: Int) = handleProgress(phase, current, total)
            override fun onDownloadComplete() {}

            // Only the boat mode needs the boat state (see W2kBootExecutor).
            override fun onBoatState(boatStateJson: String?) {}

            override fun onResult(
                ok: Boolean,
                error: String?,
                cancelled: Boolean,
                tripCount: Int,
                htmlPath: String?,
                downloadedCount: Int,
            ) {
                capturedResult = SyncResult(
                    ok = ok,
                    cancelled = cancelled,
                    error = error,
                    tripCount = if (ok && tripCount >= 0) tripCount else null,
                    htmlPath = if (ok) htmlPath else null,
                    downloadedCount = null, // no download happened this run
                )
            }
        }

        androidEntry.callAttr(
            "build_from_local_files",
            eblPaths,
            outputHtmlPath.absolutePath,
            sampleCachePath.absolutePath,
            settingsStore.boatName,
            settingsStore.mmsi,
            settingsStore.callSign,
            controller,
            settingsStore.minStopMinutes,
        )

        return capturedResult ?: SyncResult(
            ok = false, cancelled = false, error = getString(R.string.error_no_result),
            tripCount = null, htmlPath = null, downloadedCount = null,
        )
    }

    /** Uploads the fresh logbook (always, if SFTP or REST publish settings are filled in) --
     * called after a successful build/sync, before that result is shown (see runDownload()/
     * buildFromLocalFilesAndMaybePublish()'s own comments on why that order, not the reverse),
     * still on the background Thread. Runs at most once per sync. The upload itself is
     * LogbookPublisher's; this just relays its own progress line to the notification too. */
    private fun uploadIfConfigured(htmlPath: String): Boolean =
        LogbookPublisher.publish(this, settingsStore, File(htmlPath), ::handleLogLine) {
            // Also pushed to the OS notification itself, not just the log -- asked for explicitly:
            // SyncState.uploading means closing the app mid-upload no longer interrupts it, so the
            // notification is the only place this phase is visible at all while the owner is not
            // looking at the app. Shorter than the log line, without "(naar WordPress)"/"(via SFTP)":
            // that detail belongs in the log.
            startSyncNotification(
                Intent(this, SyncNotificationService::class.java)
                    .putExtra(SyncNotificationService.EXTRA_STATUS_TEXT, getString(R.string.notif_uploading)),
            )
        }

    // Set by showOfflineOrCloseDialog() when it couldn't show right away because the Activity
    // wasn't visible -- shown as soon as onResume() sees it's non-null instead.
    private var pendingOfflineOrCloseMessage: String? = null

    // The notification-foregrounded/start-failed flags live on SyncState now (process-wide, not
    // per-instance) -- see SyncState.notificationForegrounded's own doc comment for why.

    /** Starts/updates the sync notification, tolerating Android refusing to start it -- a
     * "dataSync" foreground service is capped at 6 cumulative hours per 24h on Android 15+ (this
     * app targets 37); once that budget is exhausted the system throws instead of starting it,
     * until either 24h roll around or the owner brings the app to the foreground themselves. The
     * sync/upload work itself still proceeds either way (this call only ever drives the visible
     * notification, nothing functional depends on it) -- just without a notification, rather than
     * the whole sync crashing over a UI nicety it couldn't get. Called from SyncController.report()
     * on Python's own background thread as well as from the main thread, so the fallback status
     * update is wrapped in runOnUiThread rather than assuming either.
     *
     * Once the service is already running and foreground, later calls redeliver the intent via a
     * plain startService() instead of startForegroundService() -- found in practice: Android can
     * refuse a *new* startForegroundService() call the moment the app is no longer in an eligible
     * state (e.g. the screen just locked), even though the service is already legitimately
     * foreground and only needs its notification *text* updated, not a fresh foreground grant.
     * Before this, every decode-progress update (one every couple of seconds, see
     * handleProgress()) hit that refusal and appended its own copy of the failure message to
     * the on-screen status text of the day, flooding the screen with dozens of identical lines
     * within a minute.
     *
     * That fix alone wasn't enough, though (found in practice, again): if the *very first* call
     * of a run is itself refused -- now routine since the app minimizes itself shortly after
     * starting (see autoStartSyncWithSettingsRetry()), which is exactly the kind of state change
     * that can revoke foreground-start eligibility -- SyncState.notificationForegrounded never becomes
     * true, so *every* later call kept retrying startForegroundService() and hitting the same
     * refusal, reproducing the exact same flood one level up. SyncState.notificationStartFailed short-
     * circuits that: once refused, silently skip every further attempt (no repeated failure text
     * either) until onResume() restores it -- see onResume(). */
    private fun startSyncNotification(intent: Intent) {
        if (SyncState.notificationStartFailed) {
            return
        }
        try {
            if (SyncState.notificationForegrounded) {
                startService(intent)
            } else {
                SyncNotificationService.markStartRequested()
                ContextCompat.startForegroundService(this, intent)
                SyncState.notificationForegrounded = true
            }
        } catch (e: Exception) {
            SyncNotificationService.markStartFailed()
            SyncState.notificationStartFailed = true
            // A short, plain message, not the raw exception -- found in practice: dumping
            // "android.app.ForegroundServiceStartNotAllowedException: startForegroundService()
            // not allowed due to mAllowStartForeground false: service com.ayuus...." into the
            // log reads like a crash even though the sync itself is completely unaffected (see
            // this function's own doc comment above). The budget-exhaustion case (the routine
            // one, see that doc comment) gets its own specific wording; anything else still shows
            // the real exception, since that would be genuinely unexpected here.
            // SDK_INT check first: the exception class only exists from Android 12 (API 31), and this
            // app supports Android 7+ -- lint (NewApi) flags an unguarded reference.
            val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                getString(R.string.reason_notification_daily_limit)
            } else {
                e.toString()
            }
            handleLogLine("[info] " + getString(R.string.log_notification_could_not_be_shown, reason))
        }
    }

    /** A Button that's just a toolbar icon (tight padding, no background) -- either a real
     * Material vector icon (iconRes) shown as a compound "drawable" with no text, tinted to
     * match the button's own default text color so it follows the app's DayNight theme, or a
     * single emoji glyph (no call site uses this any more -- every toolbar button became a real
     * Material vector eventually, asked for explicitly each time, for uniformity: ic_upload_24
     * and ic_article_24 replaced ☁️/📖 for reading as unclear/too old-fashioned, ic_download_24
     * replaced ↺, ic_settings_24 replaced ⚙ last -- but kept as an option here, being the
     * cheapest way to add a button with no Material icon of its own, same approach as the
     * language-switcher flags in html_writer.py's HTML output).
     * tooltip shows on a long-press (standard Android behavior for View.setTooltipText(), asked
     * for explicitly, covers both styles the same way). */
    private fun iconButton(
        tooltip: String,
        emoji: String? = null,
        iconRes: Int? = null,
        emojiSize: Float = 26f,
        onClick: () -> Unit,
    ): Button {
        // Horizontal padding narrower than vertical (12dp vs 16dp, was 16dp both ways) -- asked
        // for explicitly, to match the iOS app's own tighter icon spacing: less whitespace
        // between adjacent toolbar icons. Still 24dp (icon) + 2*12dp = 48dp wide, the Material
        // minimum touch target size, so this doesn't shrink the actual tappable area below
        // Android's own accessibility guideline -- only the visual gap between icons shrinks.
        val horizontalPadding = (12 * resources.displayMetrics.density).toInt()
        val verticalPadding = (8 * resources.displayMetrics.density).toInt()
        // Borderless + no minimum size: a plain Button here still carries the default Material
        // button chrome (background box, shadow/elevation, a fairly large minimum touch target)
        // even with just an icon as its content, which reads as a boxed button rather than a
        // standalone icon (found in practice, asked for explicitly). A borderless circular ripple
        // background (the same one Android's own icon buttons use) plus dropping the minimum
        // width/height gets the plain-icon look without needing a drawable/vector asset for the
        // emoji case, and without the default Button chrome for the vector-icon case either.
        val backgroundValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
        return Button(this).apply {
            if (iconRes != null) {
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                // The button's own per-state text colors (not valueOf(currentTextColor), a single
                // fixed color): that made a disabled icon button look exactly like an enabled one
                // (found in practice: the ☁️ publish icon looked active while it was disabled,
                // whereas a text button dims on its own).
                compoundDrawableTintList = textColors
            } else {
                text = emoji
                // Bumped up from 20f (asked for explicitly, found in practice: next to the real
                // 24dp Material icons above, the plain-text ⚙ glyph read noticeably smaller even
                // at the same nominal size) -- brings it closer to their visual weight.
                textSize = emojiSize
            }
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            setBackgroundResource(backgroundValue.resourceId)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null // drops the default press elevation animation/shadow
            ViewCompat.setTooltipText(this, tooltip)
            setOnClickListener { onClick() }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }


    /** Tapping the notification a second time while the app is already showing hides it again
     * (asked for explicitly: an auto-started sync minimizes itself right away -- see
     * autoStartSyncWithSettingsRetry() -- and the notification is then the primary way to check
     * on it; tapping it should toggle the full UI open and closed rather than being a one-way
     * "show" button). Only reachable via the notification's own PendingIntent (see
     * SyncNotificationService's openAppIntent, which sets this action) -- a plain relaunch (the
     * launcher icon, the task switcher) never carries it, so those always just show the app,
     * never hide it (asked for explicitly too).
     *
     * lifecycle.currentState reflects whether this Activity is still the visible, resumed one at
     * the moment the intent arrives: still RESUMED when tapped again while already on top (the
     * OS delivers straight here without pausing it first), not yet RESUMED when tapped while it
     * had been minimized via moveTaskToBack (delivered here first, then the OS resumes it) -- so
     * checking this instead of a separately hand-tracked boolean can't drift out of sync with the
     * real lifecycle state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_TOGGLE_FROM_NOTIFICATION && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            moveTaskToBack(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // This instance is now the one live sync updates should reach -- set before
        // restoreLiveSyncUi() below, whose whole point is to bring THIS instance's own views up
        // to date (see SyncState.active's own doc comment for the bug this fixes).
        SyncState.active = this
        // Settings can only have changed via a round trip through SettingsActivity and back --
        // re-checks here so a publish method that was just filled in (or cleared) is reflected
        // immediately, without waiting for a sync to finish.
        updatePublishButtonEnabled()
        updateSyncButtonAvailability()
        updateBootButton()
        // Brings the log/the progress bar up to date with whatever a download -- still in
        // progress, or one that already finished while this Activity wasn't the active one --
        // has produced so far. Not gated on SyncState.inProgress alone: found in practice, a
        // real, reported "app hangs" bug -- a download that finishes while the app is
        // backgrounded runs its own finally block (showSyncResult()/hideProgressBar()) entirely
        // through withActiveActivity, which no-ops with nothing active to update; by the time
        // this Activity resumes, inProgress is already back to false, so the old "still in
        // progress"-only condition here skipped restoring anything at all, leaving whatever
        // mid-download progress bar/log text was on screen before backgrounding frozen there
        // indefinitely -- looking exactly like a hang, even though the download itself had
        // completed normally. lastStatusText is only ever null before the very first download
        // this install has ever run, in which case there's nothing to restore and onCreate()'s
        // own placeholder text is still correct as-is.
        if (SyncState.inProgress || SyncState.lastStatusText != null) {
            restoreLiveSyncUi()
        }
        // Same background-completion gap as above, but for the WebView specifically: loading the
        // freshly-built logbook only ever happens inside showSyncResult()'s own success branch,
        // which (like the rest of that finally block) silently no-ops via withActiveActivity if
        // this Activity wasn't the active one when a download finished. Without this, the status text
        // above would correctly say "Klaar: N reis(en)..." after reopening the app, while the map/
        // table underneath it kept showing whatever logbook (possibly none at all) was loaded
        // before backgrounding -- reopening the app to check on the wait wouldn't actually show
        // its result. Gated on the file's own mtime, not reloaded unconditionally: this runs on
        // every resume (including a plain app-switch with nothing new to show), and force-
        // reloading an unchanged page would discard an unsaved Remarks edit sitting open in the
        // WebView for no reason.
        if (!SyncState.inProgress) {
            val htmlFile = File(filesDir, SharedConstants.LOGBOOK_FILE_NAME)
            if (htmlFile.exists() && htmlFile.lastModified() != lastLoadedHtmlMtime) {
                loadLogbookIntoWebView(htmlFile.absolutePath)
            }
        }
        // Covers being brought back via the launcher icon (or the task switcher) while a sync is
        // still genuinely running but its notification isn't up right now -- e.g. the brief
        // download-to-decode transition gap, or an earlier startForegroundService() refusal while
        // the app was in the background (see startSyncNotification()) -- now that the app is
        // visibly in the foreground again, a fresh start is allowed to go through, restoring it
        // instead of leaving the user with no visible sync indicator at all outside the app.
        //
        // Checks the real, current notification shade (this app's own postable notifications --
        // no special permission needed, unlike reading *other* apps' notifications), not just
        // SyncState.notificationForegrounded -- found in practice, a real bug reported directly:
        // that flag only ever gets set back to false by this app's own code (the sync Thread's
        // finally block, cancelSync(), ...), so if the service or its notification instead
        // disappeared some other way (the OS reclaiming it, a crash inside the service itself),
        // the flag stayed stuck "still up" and this restore never fired at all.
        // NotificationManagerCompat has no activeNotifications accessor -- only the platform
        // NotificationManager does (API 23+, well below this app's minSdk 24).
        val notificationActuallyUp = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications.any { it.id == SyncNotificationService.NOTIFICATION_ID }
        if (SyncState.inProgress && !notificationActuallyUp) {
            // Give it a fresh chance even if an earlier attempt was refused -- see
            // SyncState.notificationStartFailed's own doc on startSyncNotification() -- now that the app
            // is genuinely foreground again, that earlier refusal no longer applies.
            SyncState.notificationForegrounded = false
            SyncState.notificationStartFailed = false
            // SyncState.lastNotificationText, not SyncState.lastStatusText -- found in practice,
            // a real bug: decode/build's own progress ("Reizen opbouwen: 3/4") only ever gets
            // written straight into the notification (see handleLogLine()) and was never
            // reflected in lastStatusText at all -- so falling back to that here restored the
            // wrong, stale text (e.g. "Logboek opbouwen met bestaande gegevens...", correct only
            // at the very start of an offline build) well into that later phase. lastNotificationText
            // is kept in lockstep with the notification's own real content at every call site that
            // sets it, so restoring from it can't drift the same way; the plain fallback only
            // matters for a sync so early nothing has set either field yet.
            val restoreIntent = Intent(this, SyncNotificationService::class.java).putExtra(
                SyncNotificationService.EXTRA_STATUS_TEXT,
                SyncState.lastNotificationText ?: SyncState.lastStatusText ?: getString(R.string.status_sync_already_running_fallback),
            )
            startSyncNotification(restoreIntent)
        }
        pendingOfflineOrCloseMessage?.let { message ->
            pendingOfflineOrCloseMessage = null
            showOfflineOrCloseDialog(message)
        }
    }

    override fun onPause() {
        super.onPause()
        // Only clear if this instance is still the one recorded as active -- a newer instance's
        // own onResume() (already having set itself) must never be undone by this older
        // instance's onPause() running after it, which order-of-events would otherwise allow
        // (Android pauses the old instance only partway through creating/resuming the new one).
        if (SyncState.active === this) {
            SyncState.active = null
        }
    }

    companion object {
        const val ACTION_TOGGLE_FROM_NOTIFICATION = "com.ayuus.mysailinglogbook.ACTION_TOGGLE_FROM_NOTIFICATION"

        // The public, WordPress-gated view of whatever was just published (see uploadIfConfigured()
        // and the "Bekijk live site" notification action) -- not derived from SettingsStore's own
        // sftpRemotePath, which is the *private* SFTP destination (outside the web root, see
        // little_endian-index.php's own doc comment), not a browsable URL at all.
        const val LIVE_SITE_URL = "https://ayuus.com/little_endian/"

        // How much of the log file to show when the boat mode is open in a process that has no log text yet.
        private const val BOOT_LOG_TAIL_LINES = 60

        // How soon after a log line the log view is re-rendered at the latest, see refreshLogView().
        private const val LOG_REFRESH_INTERVAL_MS = SharedConstants.LOG_REFRESH_INTERVAL_MS.toLong()

        private const val KEY_BATTERY_PROMPTED = "boot_battery_prompted_v1"
        private const val KEY_EBL_INDEXED_FOR_PC = "ebl_indexed_for_pc_v1"

        // Safety-net ceiling for acquireManualRunWakeLock() -- a full download-and-build of a big,
        // mostly-uncached archive can genuinely run for hours on a slow device (found in practice:
        // over two hours for under 1,400 of 2,326 files), same order of magnitude as boat mode's
        // own rounds, so this matches BootModeService's WAKE_LOCK_TIMEOUT_MS rather than a short
        // one meant for a single quick operation.
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        // stageForImport()'s own per-file retry count -- three attempts total, the same
        // convention w2k2_download.py's own _DOWNLOAD_MAX_RETRIES (and every other lookup retry
        // in that codebase) already uses; _RETRY_DELAY_MS mirrors that module's own
        // _DOWNLOAD_RETRY_DELAY_S the same way (a short, local-I/O-scaled pause, not a network
        // one -- there's no round trip to a remote server to wait out here, just a SAF provider
        // that might need a moment).
        private const val FILE_READ_MAX_RETRIES = 2
        private const val FILE_READ_RETRY_DELAY_MS = 500L

        // A mid-tone red (Material's "red 700") for LogAdapter's own "[error]" highlight --
        // readable against both a light and a dark system theme, since the log view itself has no
        // background color of its own, just whatever the theme gives it.
        private val LOG_ERROR_COLOR = Color.parseColor(SharedConstants.LOG_ERROR_COLOR)

        // Material's "yellow 600" for LogAdapter's own warning highlight -- "amber 700"
        // (#FFA000) read as orange in practice, not yellow.
        private val LOG_WARNING_COLOR = Color.parseColor(SharedConstants.LOG_WARNING_COLOR)
    }
}
