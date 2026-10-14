package com.ayuus.mysailinglogbook

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (API 37) blocks an app that targets it from every device on the local network until the user has allowed
 * ACCESS_LOCAL_NETWORK (group "Nearby devices"). The W2K-2 is such a device, so the download and the boat mode ask for it
 * first. A blocked connection does not fail with an error: it times out, which looks exactly like "no W2K-2 here".
 * Older Android versions have no such permission and let every app with INTERNET through.
 */
object LocalNetworkPermission {

    /** The literal, so this builds the same whatever the compile SDK's Manifest.permission offers. */
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val FIRST_API_WITH_PERMISSION = 37

    /** Whether this Android version has the permission at all. */
    fun isNeeded(): Boolean = Build.VERSION.SDK_INT >= FIRST_API_WITH_PERMISSION

    /** True where nothing needs to be asked (older Android) or the user has allowed it. */
    fun isGranted(context: Context): Boolean =
        !isNeeded() || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}
