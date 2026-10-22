# R8 renames and removes code it thinks nothing uses. Python (Chaquopy) calls the methods of the objects the app hands
# over by their NAME, which R8 cannot see: these three interfaces and every class that implements them keep their members.
# (SyncController: a download or an assemble; ImportProgressCallback: an import; BootProbeListener: the boat mode's quiet look.)
-keep interface com.ayuus.mysailinglogbook.SyncController { *; }
-keep interface com.ayuus.mysailinglogbook.ImportProgressCallback { *; }
-keep interface com.ayuus.mysailinglogbook.BootProbeListener { *; }
-keep class * implements com.ayuus.mysailinglogbook.SyncController { *; }
-keep class * implements com.ayuus.mysailinglogbook.ImportProgressCallback { *; }
-keep class * implements com.ayuus.mysailinglogbook.BootProbeListener { *; }

# Tink (under EncryptedSharedPreferences) mentions annotations that are only needed to compile it; R8 generated these.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
