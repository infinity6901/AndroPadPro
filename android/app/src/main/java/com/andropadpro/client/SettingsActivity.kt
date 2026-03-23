package com.andropadpro.client

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.andropadpro.client.network.DiscoveryClient
import com.andropadpro.client.theme.GamepadThemes
import com.andropadpro.client.theme.ThemeManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    // ── Connection ────────────────────────────────────────────────────────────
    private lateinit var connectionGroup:    RadioGroup
    private lateinit var radioWifiManual:    RadioButton
    private lateinit var radioWifiAuto:      RadioButton
    private lateinit var radioHotspot:       RadioButton
    private lateinit var radioBluetooth:     RadioButton
    private lateinit var ipInput:            EditText
    private lateinit var btnDiscover:        Button
    private lateinit var btDeviceSpinner:    Spinner
    private lateinit var autoDiscoverSwitch: Switch
    private lateinit var hotspotHint:        TextView
    private lateinit var bluetoothHint:      TextView

    // ── Appearance ────────────────────────────────────────────────────────────
    private lateinit var themeSpinner:    Spinner
    private lateinit var accentPreview:   android.view.View
    private lateinit var btnPickAccent:   Button
    private lateinit var btnPickBg:       Button
    private lateinit var btnClearBg:      Button

    // ── Opacity ───────────────────────────────────────────────────────────────
    private lateinit var opacityMasterBar:       SeekBar
    private lateinit var opacityMasterText:      TextView
    private lateinit var opacityJoysticksBar:    SeekBar
    private lateinit var opacityJoysticksText:   TextView
    private lateinit var opacityDpadBar:         SeekBar
    private lateinit var opacityDpadText:        TextView
    private lateinit var opacityFaceBar:         SeekBar
    private lateinit var opacityFaceText:        TextView
    private lateinit var opacityTriggersBar:     SeekBar
    private lateinit var opacityTriggersText:    TextView
    private lateinit var opacityShouldersBar:    SeekBar
    private lateinit var opacityShouldersText:   TextView
    private lateinit var opacityCenterBar:       SeekBar
    private lateinit var opacityCenterText:      TextView
    private lateinit var opacityBgBar:           SeekBar
    private lateinit var opacityBgText:          TextView

    // ── Gyroscope ─────────────────────────────────────────────────────────────
    private lateinit var gyroSwitch:          Switch
    private lateinit var gyroModeSpinner:     Spinner
    private lateinit var gyroSensitivityBar:  SeekBar
    private lateinit var gyroSensitivityText: TextView
    private lateinit var gyroDeadzoneBar:     SeekBar
    private lateinit var gyroDeadzoneText:    TextView
    private lateinit var btnCalibrate:        Button

    // ── Haptic ────────────────────────────────────────────────────────────────
    private lateinit var hapticSwitch:      Switch
    private lateinit var hapticStrengthBar: SeekBar
    private lateinit var hapticStrengthText: TextView

    // ── Profile / Layout ──────────────────────────────────────────────────────
    private lateinit var profileSpinner:     Spinner
    private lateinit var customLayoutSwitch: Switch
    private lateinit var btnEditLayout:      Button

    // ── PC Streaming ──────────────────────────────────────────────────────────
    private lateinit var audioStreamSwitch:  Switch
    private lateinit var audioVolumeBar:     SeekBar
    private lateinit var audioVolumeText:    TextView
    private lateinit var screenStreamSwitch: Switch

    // ── Touchpad ─────────────────────────────────────────────────────────────
    private lateinit var touchpadToggleSwitch:          Switch
    private lateinit var touchpadSensitivityBar:        SeekBar
    private lateinit var touchpadSensitivityText:       TextView
    private lateinit var touchpadScrollSensitivityBar:  SeekBar
    private lateinit var touchpadScrollSensitivityText: TextView
    private lateinit var touchpadScreenBgSwitch:        Switch

    // ── Mic ───────────────────────────────────────────────────────────────────
    private lateinit var micStreamSwitch: Switch
    private lateinit var micToggleSwitch: Switch

    // ── Save / Cancel ─────────────────────────────────────────────────────────
    private lateinit var btnSave:   Button
    private lateinit var btnCancel: Button

    // ── Data ──────────────────────────────────────────────────────────────────
    private val gyroModeValues = arrayOf("left_stick", "right_stick")
    private val gyroModeNames  = arrayOf("Left stick (steering)", "Right stick (camera)")
    private val profileNames   = arrayOf("Default", "Racing", "FPS", "Platformer", "Custom")
    private var selectedAccent = Color.parseColor("#107C10")
    private var pairedBtDevices: List<Pair<String, String>> = emptyList()

    // OpenDocument supports multiple MIME types in a single call, so the system
    // file picker shows both images and videos together.
    // GetContent only accepts one MIME type and was previously locked to images only.
    private val bgPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Persist read permission so the URI survives app restarts
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ThemeManager.saveBgUri(this, it.toString())
            // Give user feedback about what was picked
            val mime = contentResolver.getType(it) ?: "unknown"
            val kind = when {
                mime.startsWith("video/") -> "Video wallpaper set ✓"
                mime.startsWith("image/") -> "Image background set ✓"
                else                      -> "Background set ✓"
            }
            Toast.makeText(this, kind, Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        initViews()
        loadSettings()
        setupListeners()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    private fun initViews() {
        // Connection
        connectionGroup   = findViewById(R.id.connection_group)
        radioWifiManual    = findViewById(R.id.radio_wifi_manual)
        radioWifiAuto      = findViewById(R.id.radio_wifi_auto)
        radioHotspot       = findViewById(R.id.radio_hotspot)
        radioBluetooth     = findViewById(R.id.radio_bluetooth)
        ipInput            = findViewById(R.id.ip_input)
        btnDiscover        = findViewById(R.id.btn_discover)
        btDeviceSpinner    = findViewById(R.id.bt_device_spinner)
        autoDiscoverSwitch = findViewById(R.id.auto_discover_switch)
        hotspotHint        = findViewById(R.id.hotspot_hint)
        bluetoothHint      = findViewById(R.id.bluetooth_hint)

        // Appearance
        themeSpinner  = findViewById(R.id.theme_spinner)
        accentPreview = findViewById(R.id.accent_preview)
        btnPickAccent = findViewById(R.id.btn_pick_accent)
        btnPickBg     = findViewById(R.id.btn_pick_bg_image)
        btnClearBg    = findViewById(R.id.btn_clear_bg_image)

        // Opacity
        opacityMasterBar    = findViewById(R.id.opacity_master_bar)
        opacityMasterText   = findViewById(R.id.opacity_master_text)
        opacityJoysticksBar  = findViewById(R.id.opacity_joysticks_bar)
        opacityJoysticksText = findViewById(R.id.opacity_joysticks_text)
        opacityDpadBar      = findViewById(R.id.opacity_dpad_bar)
        opacityDpadText     = findViewById(R.id.opacity_dpad_text)
        opacityFaceBar      = findViewById(R.id.opacity_face_bar)
        opacityFaceText     = findViewById(R.id.opacity_face_text)
        opacityTriggersBar  = findViewById(R.id.opacity_triggers_bar)
        opacityTriggersText = findViewById(R.id.opacity_triggers_text)
        opacityShouldersBar  = findViewById(R.id.opacity_shoulders_bar)
        opacityShouldersText = findViewById(R.id.opacity_shoulders_text)
        opacityCenterBar    = findViewById(R.id.opacity_center_bar)
        opacityCenterText   = findViewById(R.id.opacity_center_text)
        opacityBgBar        = findViewById(R.id.opacity_bg_bar)
        opacityBgText       = findViewById(R.id.opacity_bg_text)

        // Gyro
        gyroSwitch          = findViewById(R.id.gyro_switch)
        gyroModeSpinner     = findViewById(R.id.gyro_mode_spinner)
        gyroSensitivityBar  = findViewById(R.id.gyro_sensitivity_bar)
        gyroSensitivityText = findViewById(R.id.gyro_sensitivity_text)
        gyroDeadzoneBar     = findViewById(R.id.gyro_deadzone_bar)
        gyroDeadzoneText    = findViewById(R.id.gyro_deadzone_text)
        btnCalibrate        = findViewById(R.id.btn_calibrate)

        // Haptic
        hapticSwitch       = findViewById(R.id.haptic_switch)
        hapticStrengthBar  = findViewById(R.id.haptic_strength_bar)
        hapticStrengthText = findViewById(R.id.haptic_strength_text)

        // Profile
        profileSpinner     = findViewById(R.id.profile_spinner)
        customLayoutSwitch = findViewById(R.id.custom_layout_switch)
        btnEditLayout      = findViewById(R.id.btn_edit_layout)

        // PC Streaming
        audioStreamSwitch  = findViewById(R.id.audio_stream_switch)
        audioVolumeBar     = findViewById(R.id.audio_volume_bar)
        audioVolumeText    = findViewById(R.id.audio_volume_text)
        screenStreamSwitch = findViewById(R.id.screen_stream_switch)

        // Touchpad
        touchpadToggleSwitch           = findViewById(R.id.touchpad_toggle_switch)
        touchpadSensitivityBar         = findViewById(R.id.touchpad_sensitivity_bar)
        touchpadSensitivityText        = findViewById(R.id.touchpad_sensitivity_text)
        touchpadScrollSensitivityBar   = findViewById(R.id.touchpad_scroll_sensitivity_bar)
        touchpadScrollSensitivityText  = findViewById(R.id.touchpad_scroll_sensitivity_text)
        touchpadScreenBgSwitch         = findViewById(R.id.touchpad_screen_bg_switch)

        // Mic
        micStreamSwitch = findViewById(R.id.mic_stream_switch)
        micToggleSwitch = findViewById(R.id.mic_toggle_switch)

        // Save / Cancel
        btnSave   = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)

        // Populate static spinners
        themeSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            GamepadThemes.ALL.map { it.displayName })
        gyroModeSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, gyroModeNames)
        profileSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, profileNames)

        populateBtDevices()
    }

    private fun populateBtDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            btDeviceSpinner.adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, listOf("Permission required"))
            return
        }
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        pairedBtDevices = adapter?.bondedDevices?.map { it.name to it.address } ?: emptyList()
        val names = if (pairedBtDevices.isEmpty()) listOf("No paired devices")
                    else pairedBtDevices.map { "${it.first} (${it.second})" }
        btDeviceSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, names)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadSettings() {
        // Connection
        when (prefs.getString("connection_mode", "wifi_manual")) {
            "wifi_auto"  -> radioWifiAuto.isChecked     = true
            "hotspot"    -> radioHotspot.isChecked      = true
            "bluetooth"  -> radioBluetooth.isChecked    = true
            else         -> radioWifiManual.isChecked   = true
        }
        ipInput.setText(prefs.getString("server_ip", "192.168.1.15"))
        autoDiscoverSwitch.isChecked = prefs.getBoolean("auto_discover", true)
        val savedAddr = prefs.getString("bt_device_address", "")
        val idx = pairedBtDevices.indexOfFirst { it.second == savedAddr }
        if (idx >= 0) btDeviceSpinner.setSelection(idx)

        // Theme
        val themeId = prefs.getString("theme_id", "xbox") ?: "xbox"
        themeSpinner.setSelection(GamepadThemes.ALL.indexOfFirst { it.id == themeId }.coerceAtLeast(0))
        selectedAccent = if (prefs.contains("accent_override"))
            prefs.getInt("accent_override", GamepadThemes.findById(themeId).accentColor)
        else
            GamepadThemes.findById(themeId).accentColor
        accentPreview.setBackgroundColor(selectedAccent)

        // Opacity
        fun loadOp(key: String, default: Float, bar: SeekBar, tv: TextView, label: String) {
            val v = prefs.getFloat(key, default)
            bar.progress = (v * 100).toInt()
            tv.text = "$label: ${(v * 100).toInt()}%"
        }
        loadOp("opacity_master",         1.0f, opacityMasterBar,   opacityMasterText,   "Master")
        loadOp("opacity_joysticks",      1.0f, opacityJoysticksBar, opacityJoysticksText,"Joysticks")
        loadOp("opacity_dpad",           1.0f, opacityDpadBar,     opacityDpadText,     "D-Pad")
        loadOp("opacity_face_buttons",   1.0f, opacityFaceBar,     opacityFaceText,     "Face Buttons")
        loadOp("opacity_triggers",       1.0f, opacityTriggersBar, opacityTriggersText, "Triggers")
        loadOp("opacity_shoulders",      1.0f, opacityShouldersBar, opacityShouldersText,"Shoulders")
        loadOp("opacity_center_buttons", 1.0f, opacityCenterBar,   opacityCenterText,   "Center Btns")
        loadOp("opacity_background",     0.85f, opacityBgBar,      opacityBgText,       "Background")

        // Gyro
        gyroSwitch.isChecked = prefs.getBoolean("gyro_enabled", false)
        gyroModeSpinner.setSelection(
            gyroModeValues.indexOf(prefs.getString("gyro_mode", "left_stick")).coerceAtLeast(0))
        val gs = prefs.getFloat("gyro_sensitivity", 1.0f)
        gyroSensitivityBar.progress = (gs * 10).toInt()
        gyroSensitivityText.text = "Sensitivity: ${String.format("%.1f", gs)}"
        val dz = prefs.getFloat("gyro_deadzone", 0.05f)
        gyroDeadzoneBar.progress = (dz * 100).toInt()
        gyroDeadzoneText.text = "Deadzone: ${String.format("%.2f", dz)}"

        // Haptic
        hapticSwitch.isChecked = prefs.getBoolean("haptic_enabled", true)
        val hs = prefs.getInt("haptic_strength", 128)
        hapticStrengthBar.progress = hs
        hapticStrengthText.text = "Strength: $hs"

        // Profile / Layout
        profileSpinner.setSelection(prefs.getInt("active_profile", 0))
        customLayoutSwitch.isChecked = prefs.getBoolean("custom_layout_enabled", false)

        // Touchpad
        touchpadToggleSwitch.isChecked  = prefs.getBoolean("touchpad_toggle_visible", true)
        val ts = prefs.getFloat("touchpad_sensitivity", 1.8f)
        touchpadSensitivityBar.progress = (ts * 10).toInt()
        touchpadSensitivityText.text    = "Mouse sensitivity: ${String.format("%.1f", ts)}"
        val ss = prefs.getFloat("touchpad_scroll_sensitivity", 0.4f)
        touchpadScrollSensitivityBar.progress = (ss * 10).toInt()
        touchpadScrollSensitivityText.text    = "Scroll sensitivity: ${String.format("%.1f", ss)}"
        touchpadScreenBgSwitch.isChecked = prefs.getBoolean("touchpad_screen_bg", false)

        // Mic
        micStreamSwitch.isChecked = prefs.getBoolean("mic_stream_enabled", false)
        micToggleSwitch.isChecked = prefs.getBoolean("mic_toggle_visible", false)

        // PC Streaming
        audioStreamSwitch.isChecked  = prefs.getBoolean("audio_stream_enabled", false)
        val av = prefs.getInt("audio_stream_volume", 80)
        audioVolumeBar.progress = av
        audioVolumeText.text = "Volume: $av%"
        screenStreamSwitch.isChecked = prefs.getBoolean("screen_stream_enabled", false)

        updateConnectionUiState()
        updateGyroUiState()
        updateAudioUiState()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupListeners() {
        connectionGroup.setOnCheckedChangeListener { _, _ -> updateConnectionUiState() }

        btnDiscover.setOnClickListener {
            btnDiscover.text = "…"
            Thread {
                val found = runCatching { DiscoveryClient().discover(3000) }.getOrElse { emptyList() }
                runOnUiThread {
                    if (found.isNotEmpty()) ipInput.setText(found.first().first)
                    btnDiscover.text = "AUTO"
                }
            }.start()
        }

        gyroSwitch.setOnCheckedChangeListener { _, _ -> updateGyroUiState() }

        // Opacity seekbars
        fun opSeek(bar: SeekBar, tv: TextView, label: String) =
            bar.setOnSeekBarChangeListener(seekListener { p ->
                tv.text = "$label: $p%"
            })
        opSeek(opacityMasterBar,   opacityMasterText,   "Master")
        opSeek(opacityJoysticksBar, opacityJoysticksText,"Joysticks")
        opSeek(opacityDpadBar,     opacityDpadText,     "D-Pad")
        opSeek(opacityFaceBar,     opacityFaceText,     "Face Buttons")
        opSeek(opacityTriggersBar, opacityTriggersText, "Triggers")
        opSeek(opacityShouldersBar, opacityShouldersText,"Shoulders")
        opSeek(opacityCenterBar,   opacityCenterText,   "Center Btns")
        opSeek(opacityBgBar,       opacityBgText,       "Background")

        gyroSensitivityBar.setOnSeekBarChangeListener(seekListener { p ->
            gyroSensitivityText.text = "Sensitivity: ${String.format("%.1f", p / 10f)}"
        })
        gyroDeadzoneBar.setOnSeekBarChangeListener(seekListener { p ->
            gyroDeadzoneText.text = "Deadzone: ${String.format("%.2f", p / 100f)}"
        })
        hapticStrengthBar.setOnSeekBarChangeListener(seekListener { p ->
            hapticStrengthText.text = "Strength: $p"
        })

        btnCalibrate.setOnClickListener {
            Toast.makeText(this, "Gyro will reset on next calibration in app", Toast.LENGTH_SHORT).show()
        }

        // Accent color cycle
        val accentColors = listOf(
            "#107C10","#0078D4","#D4537E","#EF9F27","#E24B4A","#1D9E75","#7F77DD"
        )
        btnPickAccent.setOnClickListener {
            val i = (accentColors.indexOfFirst { Color.parseColor(it) == selectedAccent } + 1) % accentColors.size
            selectedAccent = Color.parseColor(accentColors[i])
            accentPreview.setBackgroundColor(selectedAccent)
        }

        // OpenDocument.launch() takes an array — both image and video shown in picker
        btnPickBg.setOnClickListener {
            bgPickerLauncher.launch(arrayOf("image/*", "video/*"))
        }
        btnClearBg.setOnClickListener {
            ThemeManager.saveBgUri(this, null)
            Toast.makeText(this, "Background cleared", Toast.LENGTH_SHORT).show()
        }

        btnEditLayout.setOnClickListener {
            saveSettings()
            startActivity(Intent(this, LayoutEditorActivity::class.java))
        }

        // Touchpad
        touchpadSensitivityBar.setOnSeekBarChangeListener(seekListener { p ->
            touchpadSensitivityText.text = "Mouse sensitivity: ${String.format("%.1f", p / 10f)}"
        })
        touchpadScrollSensitivityBar.setOnSeekBarChangeListener(seekListener { p ->
            touchpadScrollSensitivityText.text = "Scroll sensitivity: ${String.format("%.1f", p / 10f)}"
        })

        // PC Streaming
        audioStreamSwitch.setOnCheckedChangeListener { _, _ -> updateAudioUiState() }
        audioVolumeBar.setOnSeekBarChangeListener(seekListener { p ->
            audioVolumeText.text = "Volume: $p%"
        })

        btnSave.setOnClickListener   { saveSettings(); finish() }
        btnCancel.setOnClickListener { finish() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveSettings() {
        val connMode = when {
            radioWifiAuto.isChecked  -> "wifi_auto"
            radioHotspot.isChecked   -> "hotspot"
            radioBluetooth.isChecked -> "bluetooth"
            else                     -> "wifi_manual"
        }
        val btAddr  = pairedBtDevices.getOrNull(btDeviceSpinner.selectedItemPosition)?.second ?: ""
        val themeId = GamepadThemes.ALL.getOrNull(themeSpinner.selectedItemPosition)?.id ?: "xbox"
        val gyroMode = gyroModeValues.getOrElse(gyroModeSpinner.selectedItemPosition) { "left_stick" }

        prefs.edit().apply {
            // Connection
            putString("connection_mode",   connMode)
            putString("server_ip",         ipInput.text.toString().trim())
            putString("bt_device_address", btAddr)
            putBoolean("auto_discover",    autoDiscoverSwitch.isChecked)
            // Theme
            putString("theme_id",          themeId)
            putInt("accent_override",      selectedAccent)
            // Opacity
            putFloat("opacity_master",         opacityMasterBar.progress   / 100f)
            putFloat("opacity_joysticks",      opacityJoysticksBar.progress / 100f)
            putFloat("opacity_dpad",           opacityDpadBar.progress     / 100f)
            putFloat("opacity_face_buttons",   opacityFaceBar.progress     / 100f)
            putFloat("opacity_triggers",       opacityTriggersBar.progress / 100f)
            putFloat("opacity_shoulders",      opacityShouldersBar.progress / 100f)
            putFloat("opacity_center_buttons", opacityCenterBar.progress   / 100f)
            putFloat("opacity_background",     opacityBgBar.progress       / 100f)
            // Gyro
            putBoolean("gyro_enabled",     gyroSwitch.isChecked)
            putString("gyro_mode",         gyroMode)
            putFloat("gyro_sensitivity",   gyroSensitivityBar.progress / 10f)
            putFloat("gyro_deadzone",      gyroDeadzoneBar.progress / 100f)
            // Haptic
            putBoolean("haptic_enabled",   hapticSwitch.isChecked)
            putInt("haptic_strength",      hapticStrengthBar.progress.coerceIn(1, 255))
            // Profile
            putInt("active_profile",       profileSpinner.selectedItemPosition)
            putBoolean("custom_layout_enabled", customLayoutSwitch.isChecked)
            // PC Streaming
            putBoolean("touchpad_toggle_visible",      touchpadToggleSwitch.isChecked)
            putFloat("touchpad_sensitivity",           touchpadSensitivityBar.progress / 10f)
            putFloat("touchpad_scroll_sensitivity",    touchpadScrollSensitivityBar.progress / 10f)
            putBoolean("touchpad_screen_bg",           touchpadScreenBgSwitch.isChecked)
            putBoolean("mic_stream_enabled",           micStreamSwitch.isChecked)
            putBoolean("mic_toggle_visible",           micToggleSwitch.isChecked)
            putBoolean("audio_stream_enabled",  audioStreamSwitch.isChecked)
            putInt("audio_stream_volume",        audioVolumeBar.progress)
            putBoolean("screen_stream_enabled",  screenStreamSwitch.isChecked)
            apply()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI state helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateConnectionUiState() {
        val isBt      = radioBluetooth.isChecked
        val isHotspot = radioHotspot.isChecked
        val isManual  = radioWifiManual.isChecked

        // IP input: enabled for manual only
        ipInput.isEnabled            = isManual
        btnDiscover.isEnabled        = !isBt && !isHotspot
        autoDiscoverSwitch.isEnabled = !isBt && !isHotspot
        btDeviceSpinner.isEnabled    = isBt

        // Show relevant hints
        hotspotHint.visibility   = if (isHotspot) android.view.View.VISIBLE else android.view.View.GONE
        bluetoothHint.visibility = if (isBt)      android.view.View.VISIBLE else android.view.View.GONE

        // Hotspot mode: phone is gateway 192.168.43.1 and auto-discovery works
        // on that subnet, so we just pre-fill the hotspot gateway as a hint.
        if (isHotspot && ipInput.text.toString().isBlank()) {
            ipInput.setText("192.168.43.1")
        }
    }

    private fun updateGyroUiState() {
        val on = gyroSwitch.isChecked
        gyroModeSpinner.isEnabled    = on
        gyroSensitivityBar.isEnabled = on
        gyroDeadzoneBar.isEnabled    = on
        btnCalibrate.isEnabled       = on
    }

    private fun updateAudioUiState() {
        val on = audioStreamSwitch.isChecked
        audioVolumeBar.isEnabled  = on
        audioVolumeText.isEnabled = on
    }

    private fun seekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChanged(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
