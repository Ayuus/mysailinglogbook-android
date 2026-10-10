package com.ayuus.mysailinglogbook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/**
 * The system's folder picker (Storage Access Framework) for the two buttons that pick a folder: Import (read the .ebl files
 * of a folder) and Settings > Copy (write them to one). Any folder will do: on the phone, an SD card, a USB drive, a cloud
 * folder. With exactly one SD card or USB drive attached the picker opens in it (asked for explicitly: "kun je die dan meteen
 * openen?"); with none it opens in the phone's Download folder (where an unzipped download ends up); with two or more it opens
 * plainly, instead of guessing which one is meant. Android 11 and newer do not let an app pick the top-level folders
 * themselves (Download, ...), only a folder inside them.
 */
object FolderPicker {
    fun intent(context: Context): Intent {
        val volumes = removableVolumes(context)
        if (volumes.size == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return volumes.single().createOpenDocumentTreeIntent()
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also { picker ->
            if (volumes.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                picker.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                )
            }
        }
    }

    /** Every SD card or USB drive currently mounted: StorageManager's own volume list, every non-primary entry is removable
     * media the OS itself knows about, SD card or USB drive alike. */
    private fun removableVolumes(context: Context) =
        (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)?.storageVolumes.orEmpty().filter { !it.isPrimary }
}
