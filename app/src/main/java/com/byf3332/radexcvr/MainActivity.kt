
package com.byf3332.radexcvr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        init {
            System.loadLibrary("radexcvr")
        }

        private const val REQ_PERMISSIONS = 1001

        private const val SPEECH_SR = 16000
        private const val BASEBAND_SR = 8000

        private const val SPEECH_FRAME = 160
        private const val BASEBAND_FRAME = 80

        private const val MAX_LOG_LINES = 3

        // Route needs a brief settle time before real baseband goes out.
        private const val TX_PREP_DELAY_MS = 250L
    }

    external fun startTxMic(): Int
    external fun processTxMicFrame(
        inputSpeech160: ShortArray,
        outputBaseband80: ShortArray
    ): Int
    external fun stopTx()

    external fun startRxAudio(): Int
    external fun processRxBasebandFrame(
        inputBaseband80: ShortArray,
        outputSpeech160: ShortArray
    ): Int
    external fun stopRx()

    external fun resetRxNative()
    external fun getRxSyncNative(): Int
    external fun getRxSnrNative(): Int

    private enum class Mode {
        IDLE,
        RX,
        TX_PREPARING,
        TX
    }

    data class AudioDev(
        val id: Int,
        val name: String,
        val info: AudioDeviceInfo
    ) {
        override fun toString(): String = name
    }

    private lateinit var audioManager: AudioManager

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusPanel: LinearLayout
    private lateinit var syncStatusView: TextView
    private lateinit var meterTitleView: TextView
    private lateinit var meterValueView: TextView
    private lateinit var meterTrackView: FrameLayout
    private lateinit var meterFillView: View

    private lateinit var btnRefresh: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnResync: Button
    private lateinit var btnPtt: Button

    private lateinit var spTxInput: Spinner
    private lateinit var spTxOutput: Spinner
    private lateinit var spRxInput: Spinner
    private lateinit var spRxOutput: Spinner

    private val logLines = ArrayDeque<String>()
    private val stateLock = Any()
    private val nativeRxLock = Any()

    @Volatile
    private var rxResyncInProgress = false

    @Volatile
    private var running = false

    @Volatile
    private var pttDown = false

    @Volatile
    private var lastRxMeterUiUpdateMs =0L

    @Volatile
    private var lastTxMeterUiUpdateMs =0L

    @Volatile
    private var smoothedMicDb = -50.0

    @Volatile
    private var txAgcGain = 1.0

    private val txAgcTargetDb = -25.0
    private val txAgcMaxGain = 8.0
    private val txAgcMinGain = 0.25

    @Volatile
    private var mode = Mode.IDLE

    // Increased every time TX/RX/session state changes.
    @Volatile
    private var switchToken = 0L

    private var rxRecorder: AudioRecord? = null
    private var rxPlayer: AudioTrack? = null
    private var rxThread: Thread? = null

    private var txRecorder: AudioRecord? = null
    private var txPlayer: AudioTrack? = null
    private var txThread: Thread? = null

    private var inputDevices: List<AudioDev> = emptyList()
    private var outputDevices: List<AudioDev> = emptyList()

    @Volatile
    private var pendingStartAfterPermission = false


    @Volatile
    private var uiMicDbValue = -50.0

    @Volatile
    private var uiMicPercent = 0

    @Volatile
    private var uiMicColor = 0xFF4CAF50.toInt()

    private var displayMicDbValue = -50.0
    private var displayMicPercent = 0.0
    private var lastDisplayedMicColor = 0xFF4CAF50.toInt()

    @Volatile
    private var meterUiTickerRunning = false

    private val meterUiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val meterUiTicker = object : Runnable {
        override fun run() {
            if (!meterUiTickerRunning) return
            if (::meterTitleView.isInitialized) {
                val targetDb = uiMicDbValue.coerceIn(-50.0, 0.0)
                val targetPercent = uiMicPercent.coerceIn(0, 100).toDouble()

                val dbAlpha = if (targetDb > displayMicDbValue) 0.35 else 0.88
                displayMicDbValue = dbAlpha * displayMicDbValue + (1.0 - dbAlpha) * targetDb
                val alpha = if (targetPercent > displayMicPercent) 0.30 else 0.75
                displayMicPercent = alpha * displayMicPercent + (1.0 - alpha) * targetPercent

                meterTitleView.text = "MIC LEVEL"

                val shownDbText = "${displayMicDbValue.toInt()} dB"
                if (meterValueView.text.toString() != shownDbText) {
                    meterValueView.text = shownDbText
                }

                if (lastDisplayedMicColor != uiMicColor) {
                    meterFillView.setBackgroundColor(uiMicColor)
                    lastDisplayedMicColor = uiMicColor
                }

                meterTrackView.post {
                    val total = meterTrackView.width
                    val newWidth = (total * displayMicPercent.coerceIn(0.0, 100.0) / 100.0).toInt()
                    val lp = meterFillView.layoutParams
                    if (lp.width != newWidth) {
                        lp.width = newWidth
                        meterFillView.layoutParams = lp
                    }
                }
            }
            meterUiHandler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        btnRefresh = Button(this).apply {
            text = "REFRESH AUDIO DEVICES"
            setOnClickListener {
                refreshDevices()
                log("Devices refreshed")
            }
        }
        root.addView(btnRefresh)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        }

        logView = TextView(this).apply {
            textSize = 13f
        }
        logScroll.addView(logView)
        root.addView(logScroll)

        spTxInput = Spinner(this)
        spTxOutput = Spinner(this)
        spRxInput = Spinner(this)
        spRxOutput = Spinner(this)

        root.addView(label("TX Input"))
        root.addView(spTxInput)

        root.addView(label("TX Output"))
        root.addView(spTxOutput)

        root.addView(label("RX Input"))
        root.addView(spRxInput)

        root.addView(label("RX Output"))
        root.addView(spRxOutput)

        btnStart = Button(this).apply {
            text = "START SESSION"
            setOnClickListener { ensurePermissionsAndStart() }
        }

        btnStop = Button(this).apply {
            text = "STOP SESSION"
            isEnabled = false
            setOnClickListener { stopSession() }
        }

        val sessionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        sessionRow.addView(
            btnStart,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(4)
            }
        )

        sessionRow.addView(
            btnStop,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = dp(4)
            }
        )

        root.addView(sessionRow)

        btnResync = Button(this).apply {
            text = "RESYNC RX"
            isEnabled = false
            setOnClickListener { resyncRx() }
        }
        root.addView(btnResync)

        syncStatusView = TextView(this).apply {
            text = "SEARCHING"
            textSize = 18f
            setTextColor(0xFF9E9E9E.toInt())
        }

        meterTitleView = TextView(this).apply {
            text = "RX SNR"
            textSize = 14f
        }

        meterValueView = TextView(this).apply {
            text = "NaN"
            textSize = 14f
        }

        meterTrackView = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
            ).apply {
                topMargin = dp(6)
            }
            setBackgroundColor(0xFF404040.toInt())
        }

        meterFillView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF4CAF50.toInt())
        }
        meterTrackView.addView(meterFillView)

        statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        statusPanel.addView(syncStatusView)
        statusPanel.addView(meterTitleView)
        statusPanel.addView(meterValueView)
        statusPanel.addView(meterTrackView)
        root.addView(statusPanel)

        btnPtt = Button(this).apply {
            text = "PTT"
            textSize = 26f
            isAllCaps = false
            gravity = android.view.Gravity.CENTER

            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0

            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            elevation = 0f

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }

            setTextColor(0xFFFFFFFF.toInt())
            background = makePttBackground(0xFF9E9E9E.toInt())

            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        onPttPressed()
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        onPttReleased()
                        true
                    }

                    else -> false
                }
            }
        }

        root.addView(btnPtt)
        setContentView(root)
        refreshDevices()
        log("Ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQ_PERMISSIONS) return

        val recordGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (pendingStartAfterPermission && recordGranted) {
            pendingStartAfterPermission = false
            startSession()
        } else {
            pendingStartAfterPermission = false
            log("Start cancelled: RECORD_AUDIO not granted")
        }
    }


    private fun dp(x: Int): Int =
        (x * resources.displayMetrics.density).toInt()

    private fun label(s: String): TextView =
        TextView(this).apply { text = s }

    private fun log(msg: String) {
        runOnUiThread {
            val t = java.time.LocalTime.now()
            val line = String.format(
                "%02d:%02d:%02d  %s",
                t.hour, t.minute, t.second, msg
            )

            if (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(line)

            logView.text = logLines.joinToString("\n")
            logScroll.post {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun buildDeviceName(d: AudioDeviceInfo): String {
        val name = d.productName?.toString() ?: "Unknown"
        val typeName = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Analog"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Digital"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Speaker Safe"
            else -> "Type ${d.type}"
        }
        return "$typeName / $name (id=${d.id})"
    }

    private fun refreshDevices() {
        val prevTxIn = selectedTxInput()?.id
        val prevTxOut = selectedTxOutput()?.id
        val prevRxIn = selectedRxInput()?.id
        val prevRxOut = selectedRxOutput()?.id

        inputDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { shouldKeepDevice(it) }
            .map { AudioDev(it.id, buildDeviceName(it), it) }

        outputDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { shouldKeepDevice(it) }
            .map { AudioDev(it.id, buildDeviceName(it), it) }

        val adapterIn = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            inputDevices
        )
        val adapterOut = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            outputDevices
        )

        spTxInput.adapter = adapterIn
        spRxInput.adapter = adapterIn
        spTxOutput.adapter = adapterOut
        spRxOutput.adapter = adapterOut

        restoreSelection(spTxInput, prevTxIn)
        restoreSelection(spTxOutput, prevTxOut)
        restoreSelection(spRxInput, prevRxIn)
        restoreSelection(spRxOutput, prevRxOut)
    }

    private fun shouldKeepDevice(d: AudioDeviceInfo): Boolean {
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true

            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> false

            else -> false
        }
    }

    private fun restoreSelection(sp: Spinner, id: Int?) {
        if (id == null) return
        val adapter = sp.adapter as? ArrayAdapter<AudioDev> ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i)?.id == id) {
                sp.setSelection(i)
                return
            }
        }
    }

    private fun selectedTxInput() = spTxInput.selectedItem as? AudioDev
    private fun selectedTxOutput() = spTxOutput.selectedItem as? AudioDev
    private fun selectedRxInput() = spRxInput.selectedItem as? AudioDev
    private fun selectedRxOutput() = spRxOutput.selectedItem as? AudioDev

    private fun ensurePermissionsAndStart() {
        val missing = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isEmpty()) {
            startSession()
            return
        }

        pendingStartAfterPermission = true

        ActivityCompat.requestPermissions(
            this,
            missing.toTypedArray(),
            REQ_PERMISSIONS
        )
    }


    private fun applyTxAgcInPlace(samples: ShortArray) {
        if (samples.isEmpty()) return

        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sum += v * v
        }

        val rms = kotlin.math.sqrt(sum / samples.size)
        val db = 20.0 * kotlin.math.log10(maxOf(rms, 1.0) / 32767.0)

        val desiredGainDb = (txAgcTargetDb - db).coerceIn(-12.0, 18.0)
        var targetGain = kotlin.math.exp(desiredGainDb / 20.0 * kotlin.math.ln(10.0))
        targetGain = targetGain.coerceIn(txAgcMinGain, txAgcMaxGain)

        val alpha = if (targetGain > txAgcGain) 0.55 else 0.88
        txAgcGain = alpha * txAgcGain + (1.0 - alpha) * targetGain

        for (i in samples.indices) {
            val out = samples[i] * txAgcGain
            val clipped = when {
                out > 32767.0 -> 32767
                out < -32768.0 -> -32768
                else -> out.toInt()
            }
            samples[i] = clipped.toShort()
        }
    }

    private fun startMeterUiTicker() {
        if (meterUiTickerRunning) return
        meterUiTickerRunning = true
        meterUiHandler.post(meterUiTicker)
    }

    private fun stopMeterUiTicker() {
        meterUiTickerRunning = false
        meterUiHandler.removeCallbacks(meterUiTicker)
    }

    private fun setDeviceSelectionEnabled(enabled: Boolean) {
        spTxInput.isEnabled = enabled
        spTxOutput.isEnabled = enabled
        spRxInput.isEnabled = enabled
        spRxOutput.isEnabled = enabled
        btnRefresh.isEnabled = enabled
    }

    private fun makePttBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(5).toFloat()
            setColor(color)
        }
    }

    private fun startSession() {
        synchronized(stateLock) {
            if (running) return
            running = true
            pttDown = false
            mode = Mode.IDLE
            switchToken++
        }

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnPtt.isEnabled = true
        btnResync.isEnabled = true
        rxResyncInProgress = false

        setDeviceSelectionEnabled(false)
        setPttIdleColor()

        SessionKeepAliveService.start(this)
        lastRxMeterUiUpdateMs = 0L
        lastTxMeterUiUpdateMs = 0L
        txAgcGain = 1.0
        resetStatusUi()
        startRxFull(switchToken)
        log("Session started")
    }

    private fun stopSession() {
        synchronized(stateLock) {
            if (!running && mode == Mode.IDLE) return
            running = false
            pttDown = false
            mode = Mode.IDLE
            rxResyncInProgress = false
            switchToken++
        }

        stopTxInternal()
        stopRxInternal()
        clearAudioRoute()
        SessionKeepAliveService.stop(this)

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnPtt.isEnabled = false
        btnResync.isEnabled = false
        setDeviceSelectionEnabled(true)
        setPttIdleColor()
        resetStatusUi()

        log("Session stopped")
    }
    private fun resyncRx() {
        synchronized(stateLock) {
            if (!running) return
            if (pttDown) return
            if (rxResyncInProgress) return
            rxResyncInProgress = true
        }

        runOnUiThread {
            btnResync.isEnabled = false
        }

        thread(name = "rx-soft-resync") {
            try {
                synchronized(nativeRxLock) {
                    resetRxNative()
                }
                updateSyncUi("SEARCHING")
                log("RX resynced")
            } finally {
                rxResyncInProgress = false
                runOnUiThread {
                    if (running && !pttDown) {
                        btnResync.isEnabled = true
                    }
                }
            }
        }
    }


    private fun onPttPressed() {
        synchronized(stateLock) {
            if (!running) return
            if (pttDown) return
            pttDown = true
            switchToken++
        }

        val token = switchToken
        lastTxMeterUiUpdateMs = 0L
        uiMicDbValue = -50.0
        uiMicPercent = 0
        uiMicColor = 0xFF4CAF50.toInt()
        displayMicDbValue = -50.0
        displayMicPercent = 0.0
        lastDisplayedMicColor = 0xFF4CAF50.toInt()
        startMeterUiTicker()
        setPttPreparingColor()
        updateMeterUi("MIC LEVEL", "0 dB", 0, 0xFF4CAF50.toInt())

        thread(name = "ptt-press") {
            log("TX preparing...")

            stopRxInternal()

            if (!running || !pttDown || token != switchToken) {
                if (running && token == switchToken) {
                    startRxFull(token)
                }
                setPttIdleColor()
                return@thread
            }

            val ok = startTxFull(token)
            if (!ok) {
                log("TX start failed")
                if (running && token == switchToken) {
                    startRxFull(token)
                }
                setPttIdleColor()
                return@thread
            }

            // Allow system route to settle before enabling actual TX output.
            Thread.sleep(TX_PREP_DELAY_MS)

            if (!running || !pttDown || token != switchToken) {
                stopTxInternal()
                if (running && token == switchToken) {
                    startRxFull(token)
                }
                setPttIdleColor()
                return@thread
            }

            mode = Mode.TX
            setPttTxColor()
            updateSyncUi("TRANSMITTING")
            log("TX ready")
        }
    }

    private fun onPttReleased() {
        synchronized(stateLock) {
            if (!running) return
            if (!pttDown) return
            pttDown = false
            switchToken++
        }

        val token = switchToken
        lastRxMeterUiUpdateMs = 0L
        stopMeterUiTicker()

        thread(name = "ptt-release") {
            stopTxInternal()

            if (running && token == switchToken) {
                startRxFull(token)
                mode = Mode.RX
                setPttIdleColor()
                log("RX standby")
            } else {
                setPttIdleColor()
            }
        }
    }

    private fun setPttIdleColor() {
        runOnUiThread {
            if (::btnPtt.isInitialized) {
                btnPtt.background = makePttBackground(0xFF9E9E9E.toInt())
            }
        }
    }

    private fun setPttPreparingColor() {
        runOnUiThread {
            if (::btnPtt.isInitialized) {
                btnPtt.background = makePttBackground(0xFF1976D2.toInt())
            }
        }
    }

    private fun setPttTxColor() {
        runOnUiThread {
            if (::btnPtt.isInitialized) {
                btnPtt.background = makePttBackground(0xFF2E7D32.toInt())
            }
        }
    }

    private fun updateSyncUi(state: String) {
        runOnUiThread {
            if (::syncStatusView.isInitialized) {

                syncStatusView.text = state

                val color = when (state) {
                    "SYNCED" -> 0xFF4CAF50.toInt()      // Green
                    "SEARCHING" -> 0xFF9E9E9E.toInt()   // Gray
                    "TRANSMITTING" -> 0xFFE53935.toInt() // Red
                    else -> 0xFF9E9E9E.toInt()
                }

                syncStatusView.setTextColor(color)
            }
        }
    }

    private fun updateMeterUi(title: String, valueText: String, percent: Int, color: Int) {
        if (title == "MIC LEVEL") {
            uiMicDbValue = valueText.removeSuffix(" dB").toDoubleOrNull() ?: -50.0
            uiMicPercent = percent.coerceIn(0, 100)
            uiMicColor = color
            return
        }

        val pct = percent.coerceIn(0, 100)
        runOnUiThread {
            if (::meterTitleView.isInitialized) {
                meterTitleView.text = title
                meterValueView.text = valueText
                meterFillView.setBackgroundColor(color)
                meterTrackView.post {
                    val total = meterTrackView.width
                    val lp = meterFillView.layoutParams
                    val newWidth = (total * pct) / 100
                    if (lp.width != newWidth) {
                        lp.width = newWidth
                        meterFillView.layoutParams = lp
                    }
                }
            }
        }
    }

    private fun resetStatusUi() {
        updateSyncUi("SEARCHING")
        updateMeterUi("RX SNR", "NaN", 0, 0xFF4CAF50.toInt())
    }

    private fun applyOutputRoute(dev: AudioDev?) {
        if (dev == null) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.setCommunicationDevice(dev.info)
                } catch (_: Exception) {
                }
            }

            when (dev.info.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    audioManager.isSpeakerphoneOn = true
                }

                else -> {
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun clearAudioRoute() {
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecorder(sr: Int, buf: Int, dev: AudioDeviceInfo): AudioRecord? {
        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                r.preferredDevice = dev
            }
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                try { r.release() } catch (_: Exception) {}
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPlayer(sr: Int, buf: Int, dev: AudioDeviceInfo): AudioTrack? {
        return try {
            val t = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                buf,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                t.preferredDevice = dev
            }
            if (t.state == AudioTrack.STATE_INITIALIZED) t else {
                try { t.release() } catch (_: Exception) {}
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRxFull(token: Long): Boolean {
        if (!running) return false

        val rxIn = selectedRxInput() ?: return false
        val rxOut = selectedRxOutput() ?: return false

        val minRec = AudioRecord.getMinBufferSize(
            BASEBAND_SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minPlay = AudioTrack.getMinBufferSize(
            SPEECH_SR,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minRec <= 0 || minPlay <= 0) return false

        applyOutputRoute(rxOut)

        val recorder = buildRecorder(BASEBAND_SR, minRec * 2, rxIn.info) ?: return false
        val player = buildPlayer(SPEECH_SR, minPlay * 2, rxOut.info) ?: run {
            recorder.release()
            return false
        }

        val rc = synchronized(nativeRxLock) {
            startRxAudio()
        }
        if (rc != 0) {
            recorder.release()
            player.release()
            return false
        }

        rxRecorder = recorder
        rxPlayer = player

        try {
            recorder.startRecording()
            player.pause()
            player.flush()
            player.play()
        } catch (_: Exception) {
            try { stopRx() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
            rxRecorder = null
            rxPlayer = null
            return false
        }

        rxThread = thread(name = "rx-thread") {
            val in80 = ShortArray(BASEBAND_FRAME)
            val out160 = ShortArray(SPEECH_FRAME)

            while (running && !pttDown) {
                if (rxRecorder !== recorder || rxPlayer !== player) {
                    break
                }

                val n = try {
                    recorder.read(in80, 0, in80.size)
                } catch (_: Exception) {
                    break
                }

                if (n != in80.size) continue

                val ret = try {
                    synchronized(nativeRxLock) {
                        processRxBasebandFrame(in80, out160)
                    }
                } catch (_: Exception) {
                    -1
                }

                val now = System.currentTimeMillis()
                if (now - lastRxMeterUiUpdateMs >= 300) {
                    lastRxMeterUiUpdateMs = now

                    val sync = synchronized(nativeRxLock) { getRxSyncNative() }

                    if (sync == 0) {
                        updateSyncUi("SEARCHING")
                        updateMeterUi("RX SNR", "NaN", 0, 0xFF4CAF50.toInt())
                    } else {
                        val snr = synchronized(nativeRxLock) { getRxSnrNative() }

                        updateSyncUi("SYNCED")

                        val snrClamped = snr.coerceIn(-5, 20)
                        val percent = ((snrClamped + 5) * 100) / 25

                        updateMeterUi("RX SNR", "${snr} dB", percent, 0xFF4CAF50.toInt())
                    }
                }

                if (ret < 0) {
                    out160.fill(0)
                }

                try {
                    player.write(out160, 0, out160.size)
                } catch (_: Exception) {
                    break
                }
            }
        }

        mode = Mode.RX
        setPttIdleColor()
        log("RX standby")
        return true
    }

    private fun startTxFull(token: Long): Boolean {
        if (!running || token != switchToken) return false

        val txIn = selectedTxInput() ?: return false
        val txOut = selectedTxOutput() ?: return false

        val minRec = AudioRecord.getMinBufferSize(
            SPEECH_SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minPlay = AudioTrack.getMinBufferSize(
            BASEBAND_SR,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minRec <= 0 || minPlay <= 0) return false

        applyOutputRoute(txOut)

        val recorder = buildRecorder(SPEECH_SR, minRec * 2, txIn.info) ?: return false
        val player = buildPlayer(BASEBAND_SR, minPlay * 2, txOut.info) ?: run {
            recorder.release()
            return false
        }

        val rc = startTxMic()
        if (rc != 0) {
            recorder.release()
            player.release()
            return false
        }

        txRecorder = recorder
        txPlayer = player

        try {
            recorder.startRecording()
            player.pause()
            player.flush()
            player.play()
        } catch (_: Exception) {
            stopTx()
            try { recorder.release() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
            txRecorder = null
            txPlayer = null
            return false
        }

        txThread = thread(name = "tx-thread") {
            val in160 = ShortArray(SPEECH_FRAME)
            val out80 = ShortArray(BASEBAND_FRAME)
            var txCanSend = false
            val prepDeadline = System.currentTimeMillis() + TX_PREP_DELAY_MS

            while (running && pttDown && token == switchToken) {
                if (txRecorder !== recorder || txPlayer !== player) {
                    break
                }

                try {
                    val n = recorder.read(in160, 0, in160.size)
                    if (n != in160.size) continue
                    applyTxAgcInPlace(in160)
                    var sum = 0.0
                    for (s in in160) {
                        val v = s.toDouble()
                        sum += v * v
                    }

                    val rms = kotlin.math.sqrt(sum / in160.size)
                    val safe = maxOf(rms, 1.0)
                    val db = 20.0 * kotlin.math.log10(safe / 32767.0)

                    // smooth
                    val alpha = if (db > smoothedMicDb) 0.6 else 0.9
                    smoothedMicDb = alpha * smoothedMicDb + (1.0 - alpha) * db

                    val dbClamped = smoothedMicDb.coerceIn(-50.0, 0.0)
                    val level = ((dbClamped + 50.0) * 2.0).toInt()

                    val color = when {
                        smoothedMicDb >= -10.0 -> 0xFFE53935.toInt()
                        smoothedMicDb >= -15.0 -> 0xFFFFB300.toInt()
                        else -> 0xFF4CAF50.toInt()
                    }

                    updateMeterUi(
                        "MIC LEVEL",
                        "${smoothedMicDb.toInt()} dB",
                        level,
                        color
                    )

                    val ret = processTxMicFrame(in160, out80)
                    if (ret < 0) continue

                    if (!txCanSend && System.currentTimeMillis() >= prepDeadline) {
                        txCanSend = true
                    }

                    if (txCanSend) {
                        player.write(out80, 0, out80.size)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        mode = Mode.TX_PREPARING
        return true
    }

    private fun stopRxInternal() {
        val th = rxThread
        val recorder = rxRecorder
        val player = rxPlayer

        rxThread = null
        rxRecorder = null
        rxPlayer = null

        try { recorder?.stop() } catch (_: Exception) {}

        try { th?.join(600) } catch (_: Exception) {}

        synchronized(nativeRxLock) {
            try { stopRx() } catch (_: Exception) {}
        }

        try { recorder?.release() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
    }

    private fun stopTxInternal() {
        val th = txThread
        val recorder = txRecorder
        val player = txPlayer

        txThread = null
        txRecorder = null
        txPlayer = null

        try { recorder?.stop() } catch (_: Exception) {}
        try { stopTx() } catch (_: Exception) {}

        try { th?.join(600) } catch (_: Exception) {}

        try { recorder?.release() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
    }
}
