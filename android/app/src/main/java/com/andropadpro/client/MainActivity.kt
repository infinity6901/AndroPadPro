package com.andropadpro.client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.andropadpro.client.model.ControllerState
import com.andropadpro.client.network.AudioStreamClient
import com.andropadpro.client.network.BluetoothClient
import com.andropadpro.client.network.MicStreamClient
import com.andropadpro.client.network.ScreenStreamClient
import com.andropadpro.client.network.Transport
import com.andropadpro.client.network.UdpClient
import com.andropadpro.client.theme.ThemeManager
import com.andropadpro.client.view.BackgroundMediaView
import com.andropadpro.client.view.DPadView
import com.andropadpro.client.view.FaceButtonsView
import com.andropadpro.client.view.JoystickView
import com.andropadpro.client.view.TriggerView
import com.andropadpro.client.view.TouchpadView

@Suppress("UnstableApiUsage")
class MainActivity : AppCompatActivity(), SensorEventListener {

    // ── Network ───────────────────────────────────────────────────────────────
    private var transport: Transport? = null
    private lateinit var controllerState: ControllerState
    private var isConnected  = false
    private var lastKnownIp  = ""
    private var lastConnMode = ""

    // ── Streaming clients ─────────────────────────────────────────────────────
    private var audioClient:  AudioStreamClient?  = null
    private var screenClient: ScreenStreamClient? = null
    private var micClient:    MicStreamClient?    = null

    // ── Touchpad ──────────────────────────────────────────────────────────────
    private var touchpadVisible  = false
    private lateinit var touchpadView:      TouchpadView
    private lateinit var btnTouchpadToggle: android.widget.Button
    private lateinit var btnMicToggle:      android.widget.Button
    private var micActive = false
    // Accumulated mouse delta sent each frame
    @Volatile private var mouseDxAccum  = 0
    @Volatile private var mouseDyAccum  = 0
    @Volatile private var mouseBtns     = 0
    @Volatile private var gestureAccum  = 0   // latest gesture code, cleared after send

    // ── Rumble state — tracks continuous motor values ─────────────────────────
    private var rumbleLeftMotor  = 0
    private var rumbleRightMotor = 0
    private val rumbleHandler    = Handler(Looper.getMainLooper())
    private val rumbleStopRunnable = Runnable {
        rumbleLeftMotor  = 0
        rumbleRightMotor = 0
    }

    // ── Threading ─────────────────────────────────────────────────────────────
    private lateinit var sendHandler: Handler
    private lateinit var sendThread:  HandlerThread

    // ── Sensors ───────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null   // TYPE_GAME_ROTATION_VECTOR

    // Gyro calibration offsets — set by CAL button, subtracted from raw values
    private var gyroOffsetX = 0f
    private var gyroOffsetY = 0f
    // Accumulator for CAL sampling
    private var gyroCalSumX = 0f
    private var gyroCalSumY = 0f

    // ── Haptic ────────────────────────────────────────────────────────────────
    private var vibrator:      Vibrator? = null
    private var hapticEnabled  = true
    private var hapticStrength = 128

    // ── Settings cache ────────────────────────────────────────────────────────
    private var gyroEnabled     = false
    private var gyroSensitivity = 1.0f
    private var gyroMode        = "left_stick"
    private var useCustomLayout = false
    private var sequence        = 0

    // ── Gyro calibration ─────────────────────────────────────────────────────
    private var isGyroCalibrating    = false
    private var gyroCalibrationCount = 0

    // ── Screen lock ───────────────────────────────────────────────────────────
    private var isScreenLocked  = false
    private var lastLockTapTime = 0L
    private var lockTapCount    = 0

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var backgroundView:      BackgroundMediaView
    private lateinit var statusText:          TextView
    private lateinit var pingText:            TextView
    private lateinit var connectionIndicator: View
    private lateinit var gyroIndicator:       TextView
    private lateinit var audioIndicator:      TextView
    private lateinit var screenIndicator:     TextView
    private lateinit var gamepadLayout:       View
    private lateinit var leftStick:           JoystickView
    private lateinit var rightStick:          JoystickView
    private lateinit var dpad:                DPadView
    private lateinit var faceButtons:         FaceButtonsView
    private lateinit var ltTrigger:           TriggerView
    private lateinit var rtTrigger:           TriggerView
    private lateinit var btnStart:            Button
    private lateinit var btnSelect:           Button
    private lateinit var btnLB:               Button
    private lateinit var btnRB:               Button
    private lateinit var btnLock:             Button
    private lateinit var gyroCalibrationBtn:  Button

    // ── Frame callback (vsync-locked gamepad send) ────────────────────────────
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            sendHandler.post { sendControllerState() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        useCustomLayout = prefs.getBoolean("custom_layout_enabled", false)

        setContentView(
            if (useCustomLayout) R.layout.activity_main_custom
            else                 R.layout.activity_main
        )

        sendThread = HandlerThread("UdpSender").also { it.start() }
        sendHandler = Handler(sendThread.looper)

        initVibrator()
        initViews()
        loadSettings()
        applyTheme()
        if (useCustomLayout) applyCustomLayoutPositions()
        resetHudToDisconnected()          // show red before network connects
        sendHandler.post { initNetwork() } // run on background thread — BT/hotspot block
        initSensors()
        setupListeners()
        setupTouchpad()

        // Request mic permission proactively (needed when user first enables mic)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 42)
        }
    }

    private fun setupTouchpad() {
        // Toggle button
        btnTouchpadToggle.setOnClickListener { toggleTouchpad() }

        // Load saved visibility preference
        val prefs = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        val showToggle = prefs.getBoolean("touchpad_toggle_visible", true)
        btnTouchpadToggle.visibility = if (showToggle) View.VISIBLE else View.GONE

        touchpadView.visibility = View.GONE

        touchpadView.onMove = { dx, dy ->
            mouseDxAccum += dx
            mouseDyAccum += dy
        }
        touchpadView.onLeftClick = {
            // Single-shot: set bit, send next frame clears it
            mouseBtns = mouseBtns or 0x01
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and 0x01.inv()
            }, 80)
            vibrate()
        }
        touchpadView.onRightClick = {
            mouseBtns = mouseBtns or 0x02
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and 0x02.inv()
            }, 80)
            vibrate()
        }
        touchpadView.onMiddleClick = {
            mouseBtns = mouseBtns or 0x04
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and 0x04.inv()
            }, 80)
            vibrate()
        }
        touchpadView.onDoubleClick = {
            // Two quick left clicks
            mouseBtns = mouseBtns or 0x01
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and 0x01.inv()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    mouseBtns = mouseBtns or 0x01
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        mouseBtns = mouseBtns and 0x01.inv()
                    }, 60)
                }, 60)
            }, 60)
            vibrate()
        }
        touchpadView.onScroll = { delta ->
            // Encode vertical scroll in mouseButtons bits 3-4
            if (delta > 0) mouseBtns = mouseBtns or 0x08   // scroll up
            else           mouseBtns = mouseBtns or 0x10   // scroll down
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and (0x08 or 0x10).inv()
            }, 50)
        }
        touchpadView.onHScroll = { delta ->
            // Encode horizontal scroll in mouseButtons bits 5-6
            if (delta > 0) mouseBtns = mouseBtns or 0x20   // scroll right
            else           mouseBtns = mouseBtns or 0x40   // scroll left
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mouseBtns = mouseBtns and (0x20 or 0x40).inv()
            }, 50)
        }
        touchpadView.onGesture = { code ->
            gestureAccum = code
            // Clear after 2 frames so it fires once, not continuously
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                gestureAccum = 0
            }, 80)
            vibrate()
        }
        touchpadView.onDragState = { held ->
            if (held) mouseBtns = mouseBtns or 0x01
            else      mouseBtns = mouseBtns and 0x01.inv()
            vibrateHeavy()
        }

        // Mic toggle button
        val prefs2 = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        val showMic = prefs2.getBoolean("mic_toggle_visible", false)
        btnMicToggle.visibility = if (showMic) View.VISIBLE else View.GONE
        btnMicToggle.setOnClickListener { toggleMic() }

        // Apply saved sensitivities
        val savedSensitivity = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
            .getFloat("touchpad_sensitivity", 1.8f)
        touchpadView.sensitivity = savedSensitivity
        val savedScrollSensitivity = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
            .getFloat("touchpad_scroll_sensitivity", 0.4f)
        touchpadView.scrollSensitivity = savedScrollSensitivity

        // Screen-stream-as-background: make the View itself transparent so
        // BackgroundMediaView (which is behind everything) shows through.
        applyTouchpadScreenBg(getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
            .getBoolean("touchpad_screen_bg", false))
    }

    private fun toggleMic() {
        // HUD button is a live mute/unmute toggle.
        // mic_stream_enabled (Settings) is the persistent on/off.
        // micActive here means "client is streaming" — toggling stops or restarts it.
        if (micClient?.isRunning == true) {
            // Currently streaming → mute (stop client)
            micClient?.stop()
            micClient = null
            micActive = false
            btnMicToggle.text = "🎙️"
        } else {
            // Currently muted → unmute (restart client)
            val ip = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
                .getString("server_ip", "192.168.1.100") ?: "192.168.1.100"
            micClient = MicStreamClient(ip).also { client ->
                client.onEchoWarning = {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "⚠️ Echo cancellation not available on this device. Use headphones to avoid echo.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                client.start()
            }
            micActive = true
            btnMicToggle.text = "🔇"
        }
        vibrate()
    }

    private fun applyTouchpadScreenBg(enabled: Boolean) {
        touchpadView.screenBackground = enabled
        if (enabled) {
            // Transparent window background so BackgroundMediaView shows through
            touchpadView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            touchpadView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // TouchpadView draws its own opaque bg in onDraw — no Window bg needed
        }
    }

    private fun toggleTouchpad() {
        touchpadVisible = !touchpadVisible
        // Touchpad replaces the gamepad entirely — hide one, show the other.
        // BackgroundMediaView (screen stream) stays VISIBLE in both modes so that
        // "screen stream as touchpad background" works transparently.
        gamepadLayout.visibility = if (touchpadVisible) View.INVISIBLE else View.VISIBLE
        touchpadView.visibility  = if (touchpadVisible) View.VISIBLE   else View.GONE
        btnTouchpadToggle.text   = if (touchpadVisible) "🎮" else "🖱️"
        if (!touchpadVisible) {
            touchpadView.releaseDragLock()
            mouseBtns    = 0
            gestureAccum = 0
        }
        vibrate()
    }

    override fun onResume() {
        super.onResume()
        val prefs     = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        val newCustom = prefs.getBoolean("custom_layout_enabled", false)
        if (newCustom != useCustomLayout) { recreate(); return }

        loadSettings()
        applyTheme()
        if (useCustomLayout) applyCustomLayoutPositions()

        val currentIp   = prefs.getString("server_ip",       "192.168.1.100") ?: "192.168.1.100"
        val currentMode = prefs.getString("connection_mode", "wifi_manual")   ?: "wifi_manual"
        gyroEnabled     = prefs.getBoolean("gyro_enabled", false)

        if (currentIp != lastKnownIp || currentMode != lastConnMode) {
            resetHudToDisconnected()
            sendHandler.post { initNetwork() }
        }

        registerSensors()
        gyroIndicator.visibility = if (gyroEnabled) View.VISIBLE else View.INVISIBLE

        // Re-start / stop streaming clients based on current settings
        // Re-apply touchpad sensitivities (may have changed in Settings)
        touchpadView.sensitivity       = prefs.getFloat("touchpad_sensitivity", 1.8f)
        touchpadView.scrollSensitivity = prefs.getFloat("touchpad_scroll_sensitivity", 0.4f)
        applyTouchpadScreenBg(prefs.getBoolean("touchpad_screen_bg", false))

        // Re-apply toggle button visibility from prefs
        val showToggle = prefs.getBoolean("touchpad_toggle_visible", true)
        btnTouchpadToggle.visibility = if (showToggle) View.VISIBLE else View.GONE

        syncStreamingClients(prefs, currentIp)
        backgroundView.resumePlayback()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        backgroundView.pausePlayback()
        stopStreamingClients()
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        sendThread.quitSafely()
        transport?.disconnect()
        stopStreamingClients()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming client management
    // ─────────────────────────────────────────────────────────────────────────

    private fun syncStreamingClients(
        prefs: android.content.SharedPreferences,
        serverIp: String
    ) {
        val audioEnabled  = prefs.getBoolean("audio_stream_enabled",  false)
        val screenEnabled = prefs.getBoolean("screen_stream_enabled", false)
        val audioVolume   = prefs.getInt("audio_stream_volume", 80) / 100f

        // ── Audio ─────────────────────────────────────────────────────────────
        if (audioEnabled) {
            if (audioClient == null || !audioClient!!.isRunning) {
                audioClient?.stop()
                audioClient = AudioStreamClient(serverIp, initialVolume = audioVolume).also { it.start() }
            } else {
                audioClient?.volume = audioVolume
            }
            audioIndicator.visibility = View.VISIBLE
        } else {
            audioClient?.stop()
            audioClient = null
            audioIndicator.visibility = View.GONE
        }

        // ── Screen ────────────────────────────────────────────────────────────
        if (screenEnabled) {
            if (screenClient == null || !screenClient!!.isRunning) {
                screenClient?.stop()
                backgroundView.setMode(BackgroundMediaView.Mode.SCREEN_STREAM)
                screenClient = ScreenStreamClient(serverIp) { bmp ->
                    runOnUiThread { backgroundView.pushStreamFrame(bmp) }
                }.also { it.start() }
            }
            screenIndicator.visibility = View.VISIBLE
        } else {
            screenClient?.stop()
            screenClient = null
            screenIndicator.visibility = View.GONE
            backgroundView.setMode(BackgroundMediaView.Mode.SOLID)
            applyTheme()
        }

        // ── Mic ───────────────────────────────────────────────────────────────
        // mic_stream_enabled (Settings): the feature is turned on
        // micActive (in-memory):         currently streaming in this session
        //   - starts false on each app launch
        //   - flipped by HUD button via toggleMic()
        //   - when mic_stream_enabled=true, we auto-start on first resume
        val micEnabled = prefs.getBoolean("mic_stream_enabled", false)
        val showMicBtn = prefs.getBoolean("mic_toggle_visible", false)
        btnMicToggle.visibility = if (showMicBtn) View.VISIBLE else View.GONE

        if (micEnabled) {
            // Auto-start mic if it isn't running yet (e.g. fresh launch or after returning from Settings)
            if (!micActive) micActive = true
            if (micClient == null || !micClient!!.isRunning) {
                micClient?.stop()
                micClient = MicStreamClient(serverIp).also { client ->
                    client.onEchoWarning = {
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "⚠️ Echo cancellation not available on this device. Use headphones to avoid echo.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    client.start()
                }
            }
        } else {
            micActive = false
            micClient?.stop()
            micClient = null
        }
        btnMicToggle.text = if (micActive) "🔇" else "🎙️"
    }

    private fun stopStreamingClients() {
        audioClient?.stop()
        screenClient?.stop()
        micClient?.stop()
        // Don't null them here — syncStreamingClients handles null-check on resume
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun initViews() {
        backgroundView      = findViewById(R.id.background_view)
        statusText          = findViewById(R.id.status_text)
        pingText            = findViewById(R.id.ping_text)
        connectionIndicator = findViewById(R.id.connection_indicator)
        gyroIndicator       = findViewById(R.id.gyro_indicator)
        audioIndicator      = findViewById(R.id.audio_indicator)
        screenIndicator     = findViewById(R.id.screen_indicator)
        gamepadLayout       = findViewById(R.id.gamepad_layout)
        leftStick           = findViewById(R.id.left_stick)
        rightStick          = findViewById(R.id.right_stick)
        dpad                = findViewById(R.id.dpad)
        faceButtons         = findViewById(R.id.face_buttons)
        ltTrigger           = findViewById(R.id.lt_trigger)
        rtTrigger           = findViewById(R.id.rt_trigger)
        btnStart            = findViewById(R.id.btn_start)
        btnSelect           = findViewById(R.id.btn_select)
        btnLB               = findViewById(R.id.btn_lb)
        btnRB               = findViewById(R.id.btn_rb)
        btnLock             = findViewById(R.id.btn_lock)
        gyroCalibrationBtn  = findViewById(R.id.btn_gyro_calibrate)

        touchpadView        = findViewById(R.id.touchpad_view)
        btnTouchpadToggle   = findViewById(R.id.btn_touchpad_toggle)
        btnMicToggle        = findViewById(R.id.btn_mic_toggle)
        gamepadLayout.visibility = View.VISIBLE

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            if (!isScreenLocked)
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        btnLock.setOnClickListener {
            if (isScreenLocked) {
                val now = System.currentTimeMillis()
                if (now - lastLockTapTime < 500) {
                    if (++lockTapCount >= 2) { toggleScreenLock(); lockTapCount = 0 }
                } else {
                    lockTapCount = 1
                }
                lastLockTapTime = now
            } else {
                toggleScreenLock()
            }
        }

        gyroCalibrationBtn.setOnClickListener { if (!isGyroCalibrating) startGyroCalibration() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        hapticEnabled   = prefs.getBoolean("haptic_enabled",  true)
        hapticStrength  = prefs.getInt("haptic_strength",     128)
        gyroSensitivity = prefs.getFloat("gyro_sensitivity",  1.0f)
        gyroMode        = prefs.getString("gyro_mode",        "left_stick") ?: "left_stick"
        gyroEnabled     = prefs.getBoolean("gyro_enabled",    false)
        gyroOffsetX     = prefs.getFloat("gyro_offset_x",    0f)
        gyroOffsetY     = prefs.getFloat("gyro_offset_y",    0f)
    }

    private fun applyTheme() {
        try {
            val (theme, accent) = ThemeManager.load(this)
            ThemeManager.apply(
                context        = this,
                theme          = theme,
                accentOverride = accent,
                backgroundView = backgroundView,
                ltTrigger      = ltTrigger,
                rtTrigger      = rtTrigger,
                dpad           = dpad,
                leftStick      = leftStick,
                rightStick     = rightStick,
                faceButtons    = faceButtons,
                btnLB          = btnLB,
                btnRB          = btnRB,
                btnTouchpad    = btnLock
            )
            val ca = theme.opacityCenterButtons
            btnStart.alpha           = ca
            btnSelect.alpha          = ca
            gyroCalibrationBtn.alpha = ca
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initNetwork() {
        val prefs    = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        val connMode = prefs.getString("connection_mode", "wifi_manual") ?: "wifi_manual"
        val ip       = prefs.getString("server_ip",       "192.168.1.100") ?: "192.168.1.100"

        transport?.disconnect()
        controllerState = ControllerState()

        // Always reset to Disconnected before trying.
        // For UDP modes: connect() just sets the remote address — no handshake occurs.
        // The HUD will flip to Connected once the first ACK arrives via updateHud().
        // For Bluetooth: connect() is a real blocking handshake so it's trustworthy.
        isConnected = false
        resetHudToDisconnected()

        when (connMode) {
            "bluetooth" -> {
                val btAddr = prefs.getString("bt_device_address", "") ?: ""
                val bt = BluetoothClient()
                isConnected = bt.connect(btAddr)   // real handshake
                transport   = bt
                updateStatus()   // BT result is reliable — show it immediately
            }
            "hotspot" -> {
                val udp = UdpClient()
                udp.rumbleListener = object : UdpClient.RumbleListener {
                    override fun onRumble(leftMotor: Int, rightMotor: Int) {
                        handleRumble(leftMotor, rightMotor)
                    }
                }
                val discovered = try {
                    com.andropadpro.client.network.DiscoveryClient().discover(2000)
                } catch (_: Exception) { emptyList() }
                val targetIp = discovered.firstOrNull()?.first
                    ?: ip.ifBlank { "192.168.43.1" }
                udp.connect(targetIp)   // UDP — always succeeds, not a real connection
                if (discovered.isNotEmpty()) {
                    prefs.edit().putString("server_ip", targetIp).apply()
                }
                transport = udp
                // isConnected stays false — updateHud() will set it live
            }
            else -> {
                val udp = UdpClient()
                udp.rumbleListener = object : UdpClient.RumbleListener {
                    override fun onRumble(leftMotor: Int, rightMotor: Int) {
                        handleRumble(leftMotor, rightMotor)
                    }
                }
                udp.connect(ip)   // UDP — always succeeds, not a real connection
                transport = udp
                // isConnected stays false — updateHud() will set it live
            }
        }

        lastKnownIp  = ip
        lastConnMode = connMode

        // Choreographer must be called from the main thread.
        // initNetwork() now runs on sendHandler so we post back.
        runOnUiThread {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /** Force indicator red and text "Disconnected" immediately. */
    private fun resetHudToDisconnected() {
        runOnUiThread {
            connectionIndicator.setBackgroundResource(R.drawable.connection_indicator_red)
            statusText.text = "Disconnected"
            statusText.setTextColor(0xFFFF4444.toInt())
            pingText.text = "○"
            pingText.setTextColor(0xFFFF4444.toInt())
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // TYPE_GAME_ROTATION_VECTOR: Android-fused absolute orientation.
        // Already stable and drift-corrected by the OS — no custom filter needed.
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        if (gyroEnabled) {
            rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    private fun setupListeners() {
        leftStick.listener = object : JoystickView.JoystickListener {
            override fun onJoystickMoved(x: Float, y: Float) { controllerState.setLeftStick(x, y) }
            override fun onJoystickPressed()  { controllerState.setButton(ControllerState.L3, true);  vibrate() }
            override fun onJoystickReleased() { controllerState.setButton(ControllerState.L3, false) }
        }
        rightStick.listener = object : JoystickView.JoystickListener {
            override fun onJoystickMoved(x: Float, y: Float) { controllerState.setRightStick(x, y) }
            override fun onJoystickPressed()  { controllerState.setButton(ControllerState.R3, true);  vibrate() }
            override fun onJoystickReleased() { controllerState.setButton(ControllerState.R3, false) }
        }
        dpad.listener = object : DPadView.DPadListener {
            override fun onDirectionChanged(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
                controllerState.setButton(ControllerState.DPAD_UP,    up)
                controllerState.setButton(ControllerState.DPAD_DOWN,  down)
                controllerState.setButton(ControllerState.DPAD_LEFT,  left)
                controllerState.setButton(ControllerState.DPAD_RIGHT, right)
                if (up || down || left || right) vibrate()
            }
        }
        faceButtons.listener = object : FaceButtonsView.FaceButtonsListener {
            override fun onButtonPressed(button: String) {
                when (button) {
                    "A" -> controllerState.setButton(ControllerState.A, true)
                    "B" -> controllerState.setButton(ControllerState.B, true)
                    "X" -> controllerState.setButton(ControllerState.X, true)
                    "Y" -> controllerState.setButton(ControllerState.Y, true)
                }
                vibrate()
            }
            override fun onButtonReleased(button: String) {
                when (button) {
                    "A" -> controllerState.setButton(ControllerState.A, false)
                    "B" -> controllerState.setButton(ControllerState.B, false)
                    "X" -> controllerState.setButton(ControllerState.X, false)
                    "Y" -> controllerState.setButton(ControllerState.Y, false)
                }
            }
        }

        ltTrigger.onValueChanged = { v ->
            if (controllerState.leftTrigger  == 0 && v > 0.05f) vibrate()
            controllerState.setLeftTrigger(v)
        }
        rtTrigger.onValueChanged = { v ->
            if (controllerState.rightTrigger == 0 && v > 0.05f) vibrate()
            controllerState.setRightTrigger(v)
        }

        fun btnTouch(btn: Button, bit: Int) = btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->             { controllerState.setButton(bit, true);  vibrate() }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->           { controllerState.setButton(bit, false) }
            }; true
        }
        btnTouch(btnStart,  ControllerState.START)
        btnTouch(btnSelect, ControllerState.SELECT)
        btnTouch(btnLB,     ControllerState.LB)
        btnTouch(btnRB,     ControllerState.RB)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gyroscope
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (!gyroEnabled) return
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        // values[0] = X component (left/right tilt = roll  → maps to stick X)
        // values[1] = Y component (fwd/back tilt  = pitch → maps to stick Y)
        // These are already OS-fused, stable, and in roughly -1..1 range.
        var x = event.values.getOrElse(0) { 0f } * gyroSensitivity
        var y = event.values.getOrElse(1) { 0f } * gyroSensitivity

        if (isGyroCalibrating) {
            gyroCalibrationCount++
            gyroCalSumX += x
            gyroCalSumY += y
            return
        }

        // Subtract calibration offset so current resting position = centre
        x = (x - gyroOffsetX).coerceIn(-1f, 1f)
        y = (y - gyroOffsetY).coerceIn(-1f, 1f)

        // Also pack into packet gyro fields for server-side stick override
        controllerState.gyroX = x
        controllerState.gyroY = y

        if (gyroMode == "right_stick") controllerState.setRightStick(x, y)
        else                           controllerState.setLeftStick(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startGyroCalibration() {
        if (!gyroEnabled) {
            Toast.makeText(this, "Enable Gyroscope in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        if (rotationSensor == null) {
            Toast.makeText(this, "No rotation sensor found", Toast.LENGTH_SHORT).show()
            return
        }

        // Sample the current orientation for 2 seconds then store average as offset
        isGyroCalibrating    = true
        gyroCalibrationCount = 0
        gyroCalSumX          = 0f
        gyroCalSumY          = 0f
        gyroCalibrationBtn.text      = "CAL…"
        gyroCalibrationBtn.isEnabled = false

        Handler(Looper.getMainLooper()).postDelayed({
            if (gyroCalibrationCount > 5) {
                gyroOffsetX = gyroCalSumX / gyroCalibrationCount
                gyroOffsetY = gyroCalSumY / gyroCalibrationCount
                // Save offsets so they survive app restarts
                getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE).edit()
                    .putFloat("gyro_offset_x", gyroOffsetX)
                    .putFloat("gyro_offset_y", gyroOffsetY)
                    .apply()
                gyroCalibrationBtn.text = "CAL ✓"
                vibrateHeavy()
            } else {
                gyroCalibrationBtn.text = "CAL"
                Toast.makeText(this, "Calibration failed — move less next time", Toast.LENGTH_SHORT).show()
            }
            isGyroCalibrating        = false
            gyroCalibrationBtn.isEnabled = true
            Handler(Looper.getMainLooper()).postDelayed({ gyroCalibrationBtn.text = "CAL" }, 1500)
        }, 2000)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Haptic
    // ─────────────────────────────────────────────────────────────────────────

    private fun vibrate()      = doVibrate(50L, hapticStrength)
    private fun vibrateHeavy() = doVibrate(80L, 255)

    private fun handleRumble(leftMotor: Int, rightMotor: Int) {
        if (!hapticEnabled) return
        val intensity = ((leftMotor + rightMotor) / 2).coerceIn(0, 255)
        // Stop any pending stop-runnable so a new rumble packet extends the buzz
        rumbleHandler.removeCallbacks(rumbleStopRunnable)
        if (intensity < 5) {
            // Explicit motor-off packet — stop immediately
            rumbleLeftMotor  = 0
            rumbleRightMotor = 0
            return
        }
        rumbleLeftMotor  = leftMotor
        rumbleRightMotor = rightMotor
        // Vibrate for 250 ms; server sends rumble at ~20 Hz so packets overlap,
        // keeping the buzz alive for as long as the game requests it.
        doVibrate(250L, intensity)
        // Schedule stop 300 ms after last packet (enough gap to feel stop)
        rumbleHandler.postDelayed(rumbleStopRunnable, 300L)
    }

    private fun doVibrate(durationMs: Long, amplitude: Int) {
        if (!hapticEnabled) return
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hasAmp = try { v.hasAmplitudeControl() } catch (_: Exception) { false }
                val amp    = if (hasAmp) amplitude.coerceIn(1, 255) else 255
                try { v.vibrate(VibrationEffect.createOneShot(durationMs, amp)) } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                try { v.vibrate(durationMs) } catch (_: Exception) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send / HUD
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendControllerState() {
        // Pack accumulated touchpad mouse data
        if (touchpadVisible) {
            controllerState.mouseDx      = mouseDxAccum.coerceIn(-32768, 32767)
            controllerState.mouseDy      = mouseDyAccum.coerceIn(-32768, 32767)
            controllerState.mouseButtons = mouseBtns and 0xFF
            controllerState.gestureCode  = gestureAccum
            mouseDxAccum = 0
            mouseDyAccum = 0
            // gestureAccum cleared by its own delayed callback
        } else {
            controllerState.mouseDx      = 0
            controllerState.mouseDy      = 0
            controllerState.mouseButtons = 0
            controllerState.gestureCode  = 0
        }
        controllerState.sequence = sequence and 0xFF
        transport?.send(controllerState)
        sequence++
        if (sequence % 30 == 0) {
            val udp = transport as? UdpClient
            val rtt = udp?.latencyMs ?: -1L
            // "sent" here means the server is actually responding with ACKs —
            // UDP send() always succeeds locally so we can't use it as a signal.
            // Instead check whether an ACK arrived within the last 500 ms.
            val serverAlive = udp?.let {
                it.lastAckTimeMs > 0L &&
                (System.currentTimeMillis() - it.lastAckTimeMs) < 500L
            } ?: (transport?.isConnected() == true)  // BT: use real connection state
            updateHud(serverAlive, rtt)
        }
    }

    private fun updateHud(sent: Boolean, rtt: Long) {
        runOnUiThread {
            connectionIndicator.setBackgroundResource(
                if (sent) R.drawable.connection_indicator_green
                else      R.drawable.connection_indicator_red
            )
            // Keep status text in sync with the indicator — isConnected is set
            // once at connect time, but sent reflects the live packet state.
            statusText.text = if (sent) "Connected" else "Disconnected"
            statusText.setTextColor(if (sent) 0xFF00FF00.toInt() else 0xFFFF4444.toInt())

            pingText.text = when {
                sent && rtt >= 0 -> "${rtt}ms"
                sent             -> "●"
                else             -> "○"
            }
            pingText.setTextColor(when {
                !sent        -> 0xFFFF4444.toInt()
                rtt < 0      -> 0xFF00FF00.toInt()
                rtt < 30     -> 0xFF00FF00.toInt()
                rtt < 80     -> 0xFFFFBB00.toInt()
                else         -> 0xFFFF4444.toInt()
            })
        }
    }

    private fun updateStatus() {
        runOnUiThread {
            statusText.text = if (isConnected) "Connected" else "Disconnected"
            statusText.setTextColor(if (isConnected) 0xFF00FF00.toInt() else 0xFFFF4444.toInt())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        if (isScreenLocked) {
            btnLock.text = "🔒"
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            btnLock.text = "🔓"
            lockTapCount = 0
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyCustomLayoutPositions() {
        val prefs   = getSharedPreferences("AndroPadPro", Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val views   = mapOf(
            "left_stick"   to leftStick,  "right_stick" to rightStick,
            "dpad"         to dpad,       "face_buttons" to faceButtons,
            "btn_start"    to btnStart,   "btn_select"   to btnSelect,
            "btn_lb"       to btnLB,      "btn_rb"       to btnRB,
            "lt_trigger"   to ltTrigger,  "rt_trigger"   to rtTrigger
        )
        for ((tag, view) in views) {
            val x    = prefs.getFloat("${tag}_x",   -1f)
            val y    = prefs.getFloat("${tag}_y",   -1f)
            val size = prefs.getInt("${tag}_size",  -1)
            if (x < 0 || y < 0) continue
            val lp = android.widget.FrameLayout.LayoutParams(
                if (size > 0) (size * density).toInt() else android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                if (size > 0) (size * density).toInt() else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = (x * density).toInt()
            lp.topMargin  = (y * density).toInt()
            lp.gravity    = android.view.Gravity.TOP or android.view.Gravity.START
            view.layoutParams = lp
        }
    }
}
