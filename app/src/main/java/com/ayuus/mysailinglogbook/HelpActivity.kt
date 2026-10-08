package com.ayuus.mysailinglogbook

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import java.io.File

/**
 * The help: one page shared with the iOS app (nmea2log's assets/help/help.html, English, Dutch, French and German), shown in a web view
 * without JavaScript. The page is the copy last downloaded from GitHub, or the one that came with the app (see
 * nmea2log/help_page.py); its screenshots are this app's own, in assets/help/img/. Links to the web open in the browser.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        title = getString(R.string.help_activity_title)

        val webView = WebView(this).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // A link inside the page (#connect) stays; one to the web goes to the browser.
                    if (request.url.scheme != "http" && request.url.scheme != "https") return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        Log.w("HelpActivity", "no app to open ${request.url}", e)
                    }
                    return true
                }
            }
        }
        // The padding sits on a container, not on the web view: padding on a web view does not keep its content clear of the
        // system bars once it scrolls.
        val container = FrameLayout(this).apply { addView(webView) }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(container)

        val language = resources.configuration.locales[0].language
        // Off the main thread: Python may still have to be started.
        Thread {
            try {
                PythonStarter.ensureStarted(this)
                val html = Python.getInstance().getModule("nmea2log.help_page")
                    .callAttr("html_for_app", helpCacheDir(this).absolutePath, "android", language).toString()
                runOnUiThread {
                    webView.loadDataWithBaseURL("file:///android_asset/help/", html, "text/html", "utf-8", null)
                }
            } catch (e: Exception) {
                Log.w("HelpActivity", "could not load the help", e)
            }
        }.start()
    }

    companion object {
        /** Where the copy of the help page that was downloaded from GitHub is kept. */
        fun helpCacheDir(context: android.content.Context): File = File(context.filesDir, "help")

        /** Fetches a newer help page from GitHub when there is one (at most once a day, see help_page.refresh); quiet
         * when there is no internet. Call from a background thread. */
        fun refreshFromGitHub(context: android.content.Context) {
            try {
                PythonStarter.ensureStarted(context)
                Python.getInstance().getModule("nmea2log.help_page")
                    .callAttr("refresh", helpCacheDir(context).absolutePath)
            } catch (e: Exception) {
                Log.w("HelpActivity", "could not refresh the help", e)
            }
        }
    }
}
