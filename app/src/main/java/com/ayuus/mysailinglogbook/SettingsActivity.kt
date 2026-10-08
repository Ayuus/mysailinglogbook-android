package com.ayuus.mysailinglogbook

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.chaquo.python.Python
import java.io.File

/**
 * Plain form for the settings SettingsStore holds -- W2K-2 login, boat identity, and the WordPress
 * publish settings. Only W2K-2 user/password are required to Save -- the publish fields can stay empty
 * until the owner is ready to publish; that's checked separately (isPublishConfigured) when the
 * ☁️ icon is tapped or a download's own auto-publish runs (see MainActivity.uploadIfConfigured()).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // See MainActivity.onCreate()'s own matching comment.
        enableEdgeToEdge()
        val store = SettingsStore(this)
        val padding = (16 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun field(
            label: String,
            initialValue: String,
            isPassword: Boolean = false,
            container: LinearLayout = layout,
            hint: String? = null,
        ): EditText {
            container.addView(
                TextView(this).apply {
                    text = label
                    setPadding(0, padding, 0, 0)
                }
            )
            val editText = EditText(this).apply {
                setText(initialValue)
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                if (hint != null) this.hint = hint
            }
            if (isPassword) {
                // Material's own standard reveal-password eye icon (asked for explicitly, also
                // done for iOS's own password fields, via a "Show password" switch there --
                // there's no equivalent built-in toggle on a plain toga.PasswordInput) -- wrapping
                // in a bare TextInputLayout (no box/outline style requested) gets this for free,
                // no custom click handling or icon needed.
                val inputLayout = TextInputLayout(this).apply {
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    // Material's default box/underline styling around the field -- found in
                    // practice, asked for explicitly to fix: it made password fields look
                    // visibly different (an outline/underline) from every other plain EditText
                    // on this screen, for no reason other than needing this wrapper at all to
                    // get the built-in reveal icon. BOX_BACKGROUND_NONE keeps the icon but drops
                    // the box/underline, matching the plain fields again.
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
                }
                inputLayout.addView(editText)
                container.addView(inputLayout)
            } else {
                container.addView(editText)
            }
            return editText
        }

        fun sectionHeader(text: String) {
            layout.addView(
                TextView(this).apply {
                    this.text = text
                    setPadding(0, padding * 3, 0, 0)
                    textSize = 19f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
        }

        fun checkbox(label: String, initialValue: Boolean): CheckBox {
            val box = CheckBox(this).apply {
                text = label
                isChecked = initialValue
            }
            // Found in practice, asked for explicitly, and confirmed with pixel measurements:
            // CompoundButton positions its check-glyph using the view's raw height, ignoring
            // padding -- so a top *padding* (as used for every other field's spacing) shifts the
            // label text down without moving the glyph, breaking their shared vertical center. A
            // top *margin* doesn't have that bug, since it's handled by the parent layout instead
            // of CompoundButton's own draw logic.
            layout.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = padding },
            )
            return box
        }

        sectionHeader(getString(R.string.section_w2k2_boat))
        val userField = field(getString(R.string.label_w2k2_user), store.w2k2User)
        val passwordField = field(getString(R.string.label_w2k2_password), store.w2k2Password, isPassword = true)
        val boatNameField = field(getString(R.string.label_boat_name), store.boatName)
        val mmsiField = field(getString(R.string.label_mmsi), store.mmsi)
        val callSignField = field(getString(R.string.label_call_sign), store.callSign)
        val autoSyncOnLaunchBox = checkbox(
            getString(R.string.checkbox_auto_sync_on_launch),
            store.autoSyncOnLaunch,
        )

        sectionHeader(getString(R.string.section_trips))
        val minStopMinutesField = field(
            getString(R.string.label_min_stop_minutes),
            store.minStopMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

        // Plain static header -- used to read "Publish to {host}" derived from what's actually
        // saved to disk, but that read as contradictory/stale the moment you picked a publish
        // method in the radio group below without having saved yet: the radio said "WordPress"
        // while this header still said "Publish" (disabled, implying "not configured") right
        // above it (found in practice, asked for explicitly to simplify instead of making the
        // header itself reactive; same fix applied to the iOS app's own settings screen).
        sectionHeader(getString(R.string.section_publish))
        val autoPublishAfterBuildBox = checkbox(
            getString(R.string.checkbox_auto_publish_after_build),
            store.autoPublishAfterBuild,
        )

        // WordPress or not publishing at all -- the WordPress fields only expand when it is
        // picked (none, for "don't publish").
        val publishMethodGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val wordpressRadio = RadioButton(this).apply { text = getString(R.string.radio_publish_wordpress) }
        val noPublishRadio = RadioButton(this).apply { text = getString(R.string.radio_publish_none) }
        publishMethodGroup.addView(noPublishRadio)
        publishMethodGroup.addView(wordpressRadio)
        layout.addView(
            publishMethodGroup,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = padding },
        )

        val wordpressFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(wordpressFields)

        // Needs just a WordPress Application Password
        // (Users > Profile > Application Passwords on the account's own profile page, not the
        // account's real login password) for an account in the logboek_editor role.
        val restUploadUrlField = field(
            getString(R.string.label_rest_upload_url),
            store.restUploadUrl,
            container = wordpressFields,
            hint = getString(R.string.hint_rest_upload_url),
        )
        val restUploadUserField = field(getString(R.string.label_rest_upload_user), store.restUploadUser, container = wordpressFields)
        val restUploadPasswordField = field(
            getString(R.string.label_rest_upload_password), store.restUploadPassword, isPassword = true, container = wordpressFields,
        )

        // Boat mode: rounds while the W2K-2 is reachable, a final round in the harbour or on
        // leaving the boat (see BootModeController / nmea2log/bootmode.py). The values only feed
        // BootModeConfig; the decisions themselves are made in Python.
        sectionHeader(getString(R.string.section_boat_mode))
        val bootIntervalOptions = SharedDefaults.BOOT_INTERVAL_CHOICES
        layout.addView(
            TextView(this).apply {
                text = getString(R.string.label_boat_interval)
                setPadding(0, padding, 0, 0)
            }
        )
        val bootIntervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.boat_interval_30, R.string.boat_interval_60, R.string.boat_interval_120, R.string.boat_interval_180)
                    .map { getString(it) },
            )
            setSelection(bootIntervalOptions.indexOf(store.bootRoundIntervalMinutes).let { if (it < 0) 1 else it })
        }
        layout.addView(bootIntervalSpinner)
        val bootPublishEveryRoundBox = checkbox(getString(R.string.checkbox_boat_publish_every_round), store.bootPublishEveryRound)

        // The WordPress fields are shown only when it is picked -- GONE, not just visually hidden,
        // so the collapsed block doesn't leave a blank gap. What is picked here also decides what
        // Opslaan actually saves (see its click listener below): with "don't publish" the
        // fields are cleared, not just left untouched.
        // "Elke ronde publiceren" and "Automatisch publiceren na samenstellen" are disabled the
        // same way (asked for explicitly, found in practice: left enabled with "Niet publiceren"
        // picked, they read as real, live settings despite doing nothing at all in that state) --
        // the harbour/left-the-boat final-round checkboxes below stay enabled either way, since
        // that round still builds a fresh local logbook regardless of whether anywhere is
        // configured to publish it.
        fun updatePublishMethodVisibility() {
            wordpressFields.visibility = if (wordpressRadio.isChecked) View.VISIBLE else View.GONE
            bootPublishEveryRoundBox.isEnabled = !noPublishRadio.isChecked
            autoPublishAfterBuildBox.isEnabled = !noPublishRadio.isChecked
        }
        publishMethodGroup.setOnCheckedChangeListener { _, _ -> updatePublishMethodVisibility() }
        // Preselects WordPress when publishing is on and every field is filled in (isPublishConfigured);
        // "don't publish" otherwise -- also the correct default on a brand new install.
        if (store.isPublishConfigured) wordpressRadio.isChecked = true else noPublishRadio.isChecked = true
        updatePublishMethodVisibility()

        val bootFinalHarbourBox = checkbox(getString(R.string.checkbox_boat_final_harbour), store.bootFinalOnHarbour)
        val bootStationaryField = field(
            getString(R.string.label_boat_harbour_stationary_minutes), store.bootHarbourStationaryMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootEngineOffField = field(
            getString(R.string.label_boat_harbour_engine_off_minutes), store.bootHarbourEngineOffMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootFinalLeftBox = checkbox(getString(R.string.checkbox_boat_final_left), store.bootFinalOnLeftBoat)
        val bootLeftMinutesField = field(
            getString(R.string.label_boat_left_minutes), store.bootLeftBoatMinutes.toString(),
        ).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val bootStopAfterFinalBox = checkbox(getString(R.string.checkbox_boat_stop_after_final), store.bootStopAfterFinal)
        val bootAutoStartBox = checkbox(getString(R.string.checkbox_boat_auto_start), store.bootAutoStart)

        // Licht/Donker/Apparaat -- asked for explicitly (Android always forced dark until now,
        // see LogbookApplication's own comment; iOS's launch screen never followed the phone's
        // theme at all). Applied immediately below (not just on next launch) via
        // AppCompatDelegate.setDefaultNightMode(), which recreates every active AppCompatActivity
        // on its own -- this screen is about to finish() anyway, but MainActivity underneath
        // picks up the change without needing a manual recreate() call here.
        sectionHeader(getString(R.string.section_appearance))
        val themeGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val themeLightRadio = RadioButton(this).apply { text = getString(R.string.radio_theme_light) }
        val themeDarkRadio = RadioButton(this).apply { text = getString(R.string.radio_theme_dark) }
        val themeSystemRadio = RadioButton(this).apply { text = getString(R.string.radio_theme_system) }
        themeGroup.addView(themeLightRadio)
        themeGroup.addView(themeDarkRadio)
        themeGroup.addView(themeSystemRadio)
        layout.addView(
            themeGroup,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = padding },
        )
        when (store.themeMode) {
            "light" -> themeLightRadio.isChecked = true
            "system" -> themeSystemRadio.isChecked = true
            else -> themeDarkRadio.isChecked = true
        }

        // How the logbook page lays out its trips (cards / table); the page's own two buttons are for browsers, here the
        // choice is made in Settings and handed to the page (MainActivity.applyLogbookPrefs).
        layout.addView(
            TextView(this).apply { text = getString(R.string.label_logbook_layout) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = padding },
        )
        val viewGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val viewAutoRadio = RadioButton(this).apply { text = getString(R.string.radio_layout_auto) }
        val viewCardsRadio = RadioButton(this).apply { text = getString(R.string.radio_layout_cards) }
        val viewTableRadio = RadioButton(this).apply { text = getString(R.string.radio_layout_table) }
        viewGroup.addView(viewAutoRadio)
        viewGroup.addView(viewCardsRadio)
        viewGroup.addView(viewTableRadio)
        layout.addView(
            viewGroup,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = padding },
        )
        when (store.logbookView) {
            "cards" -> viewCardsRadio.isChecked = true
            "table" -> viewTableRadio.isChecked = true
            else -> viewAutoRadio.isChecked = true
        }

        // Cache-legen: two separate buttons rather than one "clear everything" -- the two caches
        // are cleared for different reasons (a decode/trip-build bug vs. a wrong/stale place
        // name or weather value) and clearing the wrong one is real, avoidable extra network/CPU
        // cost (a cleared "data" cache re-decodes and re-classifies every .ebl file from
        // scratch; a cleared "plaatsnamen" cache re-does every geocoding/weather/marine lookup),
        // so keeping them separate lets whichever one is actually the problem be cleared without
        // paying for the other (asked for explicitly).
        sectionHeader(getString(R.string.section_cache))

        // deleteRecursively() rather than delete() -- sample_cache.pkl is a *directory* (one
        // small file per decoded .ebl file, see sample_cache.py's own module docstring for why),
        // not a plain file despite the name; delete() alone silently does nothing to a non-empty
        // directory. Harmless to call on a file that doesn't exist (or doesn't exist at all yet,
        // e.g. before the very first download) -- deleteRecursively() returns false either way and
        // there's nothing further to do.
        // Same row shape as checkbox()'s own label+control pairing -- asked for explicitly,
        // found in practice: a standalone MaterialButton per cache with its own long label
        // ("Cache: Data") read as an odd, oversized action compared to every checkbox/field row
        // around it. Now the descriptive text lives in a plain TextView on the left (like a
        // checkbox's own label), and the button itself is a small, compact "Legen" on the right.
        fun buttonRow(label: String, buttonText: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = padding }
            }
            row.addView(
                TextView(this).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                // Outlined, not the default filled style -- a secondary/occasional action,
                // outlined reads as lower-emphasis than the filled Opslaan button below without
                // needing a whole separate color. Fixed 90dp width -- asked for explicitly, found
                // in practice: sized to just its own "Legen" text alone, the button read as too
                // small/fiddly a tap target next to a checkbox's own much larger control on
                // every other row.
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = buttonText
                    // MaterialButton's own default style forces all-caps regardless of the
                    // theme's android:textAllCaps=false (see themes.xml's own comment on why
                    // that's set app-wide) -- a style-level attribute wins over a theme-level one
                    // of the same name, found in practice: these still rendered as "CACHE: DATA"
                    // despite that theme override, until set explicitly here too.
                    isAllCaps = false
                    // A bit more rounded than Material's own ~4dp default -- asked for
                    // explicitly, to look nicer -- same radius as the Opslaan button below.
                    cornerRadius = (16 * resources.displayMetrics.density).toInt()
                    // At least 90dp, wider when the text needs it ("Verwijderen").
                    minWidth = (90 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    setOnClickListener { onClick() }
                },
            )
            layout.addView(row)
        }

        fun clearCacheRow(label: String, confirmMessage: String, files: () -> List<File>) =
            buttonRow(label, getString(R.string.button_clear)) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setMessage(confirmMessage)
                    .setPositiveButton(getString(R.string.button_clear)) { _, _ ->
                        files().forEach { it.deleteRecursively() }
                        Toast.makeText(this@SettingsActivity, getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.button_cancel), null)
                    .show()
            }

        clearCacheRow(
            getString(R.string.button_cache_data),
            getString(R.string.dialog_clear_data_cache_message),
        ) {
            listOf(File(filesDir, SharedConstants.SAMPLE_CACHE_FILE_NAME), File(filesDir, ".trip_cache.pkl"))
        }
        clearCacheRow(
            getString(R.string.button_cache_places),
            getString(R.string.dialog_clear_places_cache_message),
        ) {
            listOf(
                File(filesDir, ".geocode_cache.json"),
                File(filesDir, ".weather_cache.json"),
                File(filesDir, ".marine_cache.json"),
            )
        }

        // The raw .ebl files themselves (the caches above never touch them): for freeing the phone's
        // storage. Counted and deleted by nmea2log.ebl_storage, the same code the iOS app uses. Not
        // while a run is busy with them.
        sectionHeader(getString(R.string.section_ebl_files))
        buttonRow(getString(R.string.button_ebl_files), getString(R.string.button_delete)) {
            if (SyncState.inProgress || SyncState.bootBusy) {
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_ebl_delete_busy), Toast.LENGTH_SHORT).show()
                return@buttonRow
            }
            PythonStarter.ensureStarted(this)
            val storage = Python.getInstance().getModule("nmea2log.ebl_storage")
            val folder = EblStorage.downloadDir(this).absolutePath
            val found = storage.callAttr("describe", folder).asList()
            val count = found[0].toInt()
            if (count == 0) {
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_ebl_files_none), Toast.LENGTH_SHORT).show()
                return@buttonRow
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setMessage(getString(R.string.dialog_delete_ebl_message, count, found[1].toString()))
                .setPositiveButton(getString(R.string.button_delete)) { _, _ ->
                    val deleted = storage.callAttr("delete_all", folder).asList()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.toast_ebl_files_deleted, deleted[0].toInt(), deleted[1].toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .setNegativeButton(getString(R.string.button_cancel), null)
                .show()
        }

        // Outlined, secondary-emphasis style (see clearCacheRow()'s own comment on this same
        // style choice) -- discards whatever's been changed on screen and leaves without saving,
        // same as iOS's own Cancel button next to Save. Asked for explicitly: this screen had no
        // explicit way to back out other than the system back gesture/button.
        val cancelButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.button_cancel)
            isAllCaps = false
            cornerRadius = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(padding, padding, padding / 2, padding)
            }
            setOnClickListener { finish() }
        }

        // Default filled MaterialButton style (unlike the outlined cache/Cancel buttons above) --
        // the one clearly primary action on this screen. Shares a row with Cancel (equal weight,
        // same as iOS's own Cancel/Save pair) rather than being alone and full width now that
        // there's a second button next to it.
        val saveButton = MaterialButton(this).apply {
            text = getString(R.string.button_save)
            isAllCaps = false // see clearCacheRow()'s own comment on this
            cornerRadius = (16 * resources.displayMetrics.density).toInt() // see clearCacheRow()'s own comment on this
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(padding / 2, padding, padding, padding)
            }
            setOnClickListener {
                // No longer required to be filled in before saving (asked for explicitly, found
                // in practice: this predates importButton's SD/USB-based import, which reaches
                // the exact same decode/build/publish pipeline without the W2K-2 involved at all
                // -- someone who only ever imports from a card has no reason to have W2K-2
                // credentials at all, and blocking Save entirely until they typed something into
                // both fields made every other setting on this screen unreachable too, not just
                // the download feature). runDownload() itself still shows a clear message the moment
                // the owner actually presses the download button with incomplete settings -- see
                // its own isW2k2ConfigComplete check -- which is the only point this was ever
                // actually actionable information for them.
                store.w2k2User = userField.text.toString().trim()
                store.w2k2Password = passwordField.text.toString()
                store.boatName = boatNameField.text.toString().trim()
                store.mmsi = mmsiField.text.toString().trim()
                store.callSign = callSignField.text.toString().trim()
                store.autoSyncOnLaunch = autoSyncOnLaunchBox.isChecked
                store.minStopMinutes = minStopMinutesField.text.toString().toDoubleOrNull()
                    ?: SettingsStore.DEFAULT_MIN_STOP_MINUTES.toDouble()
                store.autoPublishAfterBuild = autoPublishAfterBuildBox.isChecked
                store.bootRoundIntervalMinutes = bootIntervalOptions[bootIntervalSpinner.selectedItemPosition]
                store.bootPublishEveryRound = bootPublishEveryRoundBox.isChecked
                store.bootFinalOnHarbour = bootFinalHarbourBox.isChecked
                store.bootHarbourStationaryMinutes = bootStationaryField.text.toString().toIntOrNull()?.coerceAtLeast(SharedDefaults.MINIMUM_MINUTES) ?: SharedDefaults.BOOT_HARBOUR_STATIONARY_MINUTES
                store.bootHarbourEngineOffMinutes = bootEngineOffField.text.toString().toIntOrNull()?.coerceAtLeast(SharedDefaults.MINIMUM_MINUTES) ?: SharedDefaults.BOOT_HARBOUR_ENGINE_OFF_MINUTES
                store.bootFinalOnLeftBoat = bootFinalLeftBox.isChecked
                store.bootLeftBoatMinutes = bootLeftMinutesField.text.toString().toIntOrNull()?.coerceAtLeast(SharedDefaults.MINIMUM_MINUTES) ?: SharedDefaults.BOOT_LEFT_BOAT_MINUTES
                store.bootStopAfterFinal = bootStopAfterFinalBox.isChecked
                store.bootAutoStart = bootAutoStartBox.isChecked
                store.themeMode = when {
                    themeLightRadio.isChecked -> "light"
                    themeSystemRadio.isChecked -> "system"
                    else -> "dark"
                }
                store.logbookView = when {
                    viewCardsRadio.isChecked -> "cards"
                    viewTableRadio.isChecked -> "table"
                    else -> "auto"
                }
                AppCompatDelegate.setDefaultNightMode(
                    when (store.themeMode) {
                        "light" -> AppCompatDelegate.MODE_NIGHT_NO
                        "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        else -> AppCompatDelegate.MODE_NIGHT_YES
                    }
                )
                // The details are always saved as typed, and the radio only says whether publishing is on --
                // picking "don't publish" must not wipe them, or switching back finds the fields empty.
                // Stored exactly as typed, not expanded -- see RestUploader.kt's own
                // nmea2log.upload.normalize_rest_upload_url() call for where that happens instead (only at
                // actual upload time). Expanding it here would mean this field shows something different from
                // what was typed the next time Settings opens -- confusing on its own, and found in practice
                // on iOS (which had the same save-time expansion until this was moved): the field, once
                // holding a full URL, got treated as a real saved website by autofill/suggestions.
                store.restUploadUrl = restUploadUrlField.text.toString().trim()
                store.restUploadUser = restUploadUserField.text.toString().trim()
                store.restUploadPassword = restUploadPasswordField.text.toString()
                store.publishEnabled = wordpressRadio.isChecked
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Opslaan lives outside the ScrollView, not at the bottom of the scrolling field list --
        // with this many fields (W2K-2, boat identity, the publish section) the button used
        // to only be reachable by scrolling all the way down, and a quick "fill in the fields,
        // then just tap back" felt like it saved but silently didn't (found in practice, asked for
        // explicitly to fix: settings appeared not to be remembered at all). Now it's always
        // visible regardless of scroll position, so there's no way to miss it.
        val scrollArea = ScrollView(this).apply {
            addView(layout)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Plain 1dp line, inset by `padding` on both sides -- separates the scrolling field list
        // above from Cancel/Save below, same reasoning as the iOS app's own equivalent divider
        // (asked for explicitly), just inset rather than edge-to-edge to match Material's own
        // conventional dialog/list-footer divider styling. Low-alpha black (?attr/colorOnSurface
        // would need a ColorStateList reference for the alpha; a literal ARGB int is simpler here
        // and theme-agnostic either way, light or dark).
        val buttonRowDivider = View(this).apply {
            setBackgroundColor(0x1F000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt(),
            ).apply { leftMargin = padding; rightMargin = padding }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelButton)
            addView(saveButton)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollArea)
            addView(buttonRowDivider)
            addView(buttonRow)
        }
        // Same edge-to-edge insets fix as MainActivity (found in practice during spike 2), plus
        // the on-screen keyboard's own inset (asked for explicitly, found in practice on a real
        // tablet: with edge-to-edge on, the OS no longer resizes/pans this screen for the
        // keyboard on its own -- Opslaan, living below the ScrollView specifically so it's
        // always reachable regardless of scroll position (see its own comment above), ended up
        // hidden behind the keyboard instead, unreachable no matter how you scrolled). Bottom
        // padding is whichever inset is taller -- the keyboard's own, while it's showing
        // (usually taller than the nav bar it covers), or the plain system bars otherwise.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
    }
}
