package com.byf3332.radexcvr

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresPermission
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.rigs.BaseRig
import com.bg7yoz.ft8cn.rigs.OnRigStateChanged
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.byf3332.radexcvr.cat.Ft8CnRigCatalog
import com.byf3332.radexcvr.cat.Ft8CnRigFactory
import com.byf3332.radexcvr.cat.Ft8CnRigProfile
import com.byf3332.radexcvr.cat.Ft8CnUsbRigConnector
import com.byf3332.radexcvr.network.FreeDvReporterClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AppPage {
    HOME,
    SETTINGS,
    REPORTER,
    LOGS
}

enum class RigControlMode {
    VOX,
    CAT,
    RTS,
    DTR
}

enum class SessionMode {
    IDLE,
    RX,
    TX_PREPARING,
    TX
}

data class AudioDeviceOption(
    val id: Int,
    val name: String
)

data class UsbSerialDeviceOption(
    val key: String,
    val label: String,
    val detail: String,
    val portCount: Int = 0,
    val portIndex: Int = 0
)

data class RigProfileOption(
    val key: String,
    val label: String
)

data class MeterUiState(
    val title: String = "RX SNR",
    val valueText: String = "NaN",
    val percent: Int = 0,
    val colorArgb: Int = 0xFF4CAF50.toInt()
)

data class DeviceSelectionUiState(
    val txInputId: Int? = null,
    val txOutputId: Int? = null,
    val rxInputId: Int? = null,
    val rxOutputId: Int? = null
)

data class ReporterStationUi(
    val sid: String,
    val callsign: String,
    val gridSquare: String,
    val version: String,
    val frequencyHz: Long,
    val mode: String,
    val status: String,
    val message: String,
    val lastTx: String,
    val lastRxCallsign: String,
    val snr: String,
    val lastUpdate: String
)

data class ReporterUiState(
    val connected: Boolean = false,
    val status: String = "Disconnected",
    val stations: List<ReporterStationUi> = emptyList()
)

data class AppUiState(
    val currentPage: AppPage = AppPage.HOME,
    val logs: List<String> = emptyList(),
    val inputDevices: List<AudioDeviceOption> = emptyList(),
    val outputDevices: List<AudioDeviceOption> = emptyList(),
    val selectedDevices: DeviceSelectionUiState = DeviceSelectionUiState(),
    val sessionRunning: Boolean = false,
    val startEnabled: Boolean = true,
    val stopEnabled: Boolean = false,
    val resyncEnabled: Boolean = false,
    val pttEnabled: Boolean = false,
    val selectorsEnabled: Boolean = true,
    val refreshEnabled: Boolean = true,
    val syncStatus: String = "SEARCHING",
    val pttColorArgb: Int = 0xFF9E9E9E.toInt(),
    val mode: SessionMode = SessionMode.IDLE,
    val rxSnrDb: Int? = null,
    val rxFreqOffsetHz: Float? = null,
    val manualRxOffsetHz: Int = 0,
    val agcEnabled: Boolean = true,
    val agcClipDb: Float = -3.0f,
    val agcStrength: Float = 1.0f,
    val rigControlMode: RigControlMode = RigControlMode.VOX,
    val rigProfiles: List<RigProfileOption> = emptyList(),
    val selectedRigProfileKey: String? = null,
    val selectedRigProfileLabel: String = "None",
    val usbSerialDevices: List<UsbSerialDeviceOption> = emptyList(),
    val selectedUsbSerialDeviceKey: String? = null,
    val selectedUsbSerialPermissionGranted: Boolean = false,
    val usbSerialPermissionStatus: String = "No USB serial device selected",
    val usbSerialDiscoveryStatus: String = "Tap Find Devices to scan supported USB serial adapters",
    val serialDevicesRefreshEnabled: Boolean = true,
    val serialBaudRate: Int = 19200,
    val serialPortOpen: Boolean = false,
    val serialPortStatus: String = "Serial port closed",
    val serialRtsEnabled: Boolean = false,
    val serialDtrEnabled: Boolean = false,
    val rigCivAddress: String = "",
    val rigFrequencyText: String = "",
    val rigStatusText: String = "CAT idle",
    val rigConnected: Boolean = false,
    val rigCurrentFreqHz: Long? = null,
    val rigPttActive: Boolean = false,
    val waterfall: List<List<Float>> = emptyList(),
    val reporterEnabled: Boolean = false,
    val reporterConnected: Boolean = false,
    val reporterCallsign: String = "",
    val reporterGridSquare: String = "",
    val reporterMessage: String = "",
    val reporterRxOnly: Boolean = true,
    val reporterManualFreqMHz: String = ""
)

class RadioSessionController(
    private val context: Context,
    private val nativeBridge: NativeRadioBridge = NativeRadioBridge
) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.byf3332.radexcvr.USB_SERIAL_PERMISSION"
        private const val PREFS_NAME = "radexcvr_prefs"
        private const val PREF_AGC_ENABLED = "agc_enabled"
        private const val PREF_AGC_CLIP_DB = "agc_clip_db"
        private const val PREF_AGC_STRENGTH = "agc_strength"
        private const val PREF_RIG_CONTROL_MODE = "rig_control_mode"
        private const val PREF_USB_SERIAL_DEVICE_KEY = "usb_serial_device_key"
        private const val PREF_SERIAL_BAUD_RATE = "serial_baud_rate"
        private const val PREF_RIG_PROFILE_KEY = "rig_profile_key"
        private const val PREF_RIG_CIV_ADDRESS = "rig_civ_address"
        private const val PREF_REPORTER_ENABLED = "reporter_enabled"
        private const val PREF_REPORTER_CALLSIGN = "reporter_callsign"
        private const val PREF_REPORTER_GRID = "reporter_grid"
        private const val PREF_REPORTER_MESSAGE = "reporter_message"
        private const val PREF_REPORTER_RX_ONLY = "reporter_rx_only"
        private const val PREF_REPORTER_MANUAL_FREQ_MHZ = "reporter_manual_freq_mhz"
        private const val SPEECH_SR = 16000
        private const val BASEBAND_SR = 8000
        private const val SPEECH_FRAME = 160
        private const val BASEBAND_FRAME = 80
        private const val MAX_LOG_LINES = 200
        private const val WATERFALL_HISTORY = 120
        private const val SPECTRUM_BINS = 96
        private const val SPECTRUM_WINDOW = 512
        private const val WATERFALL_FREQ_START_HZ = 0.0
        private const val WATERFALL_FREQ_END_HZ = 3000.0
    }

    data class AudioDev(
        val id: Int,
        val name: String,
        val info: AudioDeviceInfo
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appVersionName: String = runCatching {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "dev"
    }.getOrDefault("dev")
    private val usbSerialController = UsbSerialController(usbManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reporterClient = FreeDvReporterClient(scope)
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val _reporterUiState = MutableStateFlow(ReporterUiState())
    val reporterUiState: StateFlow<ReporterUiState> = _reporterUiState.asStateFlow()
    private val _meterState = MutableStateFlow(MeterUiState())
    val meterState: StateFlow<MeterUiState> = _meterState.asStateFlow()

    private val logLines = ArrayDeque<String>()
    private val stateLock = Any()
    private val nativeRxLock = Any()

    @Volatile private var pendingStartAfterPermission = false
    @Volatile private var running = false
    @Volatile private var pttDown = false
    @Volatile private var txReleaseInProgress = false
    @Volatile private var rxResyncInProgress = false
    @Volatile private var mode = SessionMode.IDLE
    @Volatile private var switchToken = 0L
    @Volatile private var lastRxStatsUpdateMs = 0L
    @Volatile private var smoothedMicDb = -50.0
    @Volatile private var txAgcGain = 1.0
    @Volatile private var manualRxOffsetHz = 0

    private val txAgcTargetDb = -25.0
    private val txAgcMaxGain = 8.0
    private val txAgcMinGain = 0.25

    private var rxRecorder: AudioRecord? = null
    private var rxPlayer: AudioTrack? = null
    private var rxThread: Thread? = null
    private var txRecorder: AudioRecord? = null
    private var txPlayer: AudioTrack? = null
    private var txThread: Thread? = null
    private var txPlaybackThread: Thread? = null
    private var txPlaybackQueue: LinkedBlockingQueue<ShortArray>? = null
    private var usbPermissionReceiverRegistered = false

    private var inputDevices: List<AudioDev> = emptyList()
    private var outputDevices: List<AudioDev> = emptyList()
    private var rigProfiles: List<Ft8CnRigProfile> = emptyList()
    private var activeRigProfile: Ft8CnRigProfile? = null
    private var activeRig: BaseRig? = null
    private var activeRigConnector: Ft8CnUsbRigConnector? = null

    private val spectrumWindow = ShortArray(SPECTRUM_WINDOW)
    private var spectrumWriteIndex = 0
    private var spectrumSampleCount = 0
    private var waterfallFloorDb = -70.0
    private var waterfallPeakDb = -25.0
    private var targetMicMeter = MeterUiState(
        title = "MIC LEVEL",
        valueText = "-50 dB",
        percent = 0,
        colorArgb = 0xFF4CAF50.toInt()
    )
    private var displayedMicDb = -50.0
    private var displayedMicPercent = 0.0
    private var displayedMicColor = 0xFF4CAF50.toInt()
    private var meterTickerJob: Job? = null
    private val supportedBaudRates = listOf(
        1200,
        2400,
        4800,
        9600,
        14400,
        19200,
        38400,
        43000,
        56000,
        57600,
        115200
    )
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val permissionDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val physicalDeviceKey = permissionDevice?.deviceName
            val deviceKey = physicalDeviceKey?.let { attachedKey ->
                _uiState.value.usbSerialDevices.firstOrNull { option ->
                    parseUsbSerialPortKey(option.key).first == attachedKey
                }?.key
            } ?: _uiState.value.selectedUsbSerialDeviceKey
            probeUsbSerialDevices()
            val selected = _uiState.value.usbSerialDevices.firstOrNull {
                it.key == deviceKey
            }
            _uiState.update {
                it.copy(
                    selectedUsbSerialDeviceKey = deviceKey,
                    selectedUsbSerialPermissionGranted = granted && selected != null,
                    usbSerialPermissionStatus = when {
                        selected == null -> "Granted device is not in supported serial list"
                        granted -> "USB access granted"
                        else -> "USB access denied"
                    }
                )
            }
            addLog(
                if (granted) "USB serial permission granted"
                else "USB serial permission denied"
            )
            if (granted && deviceKey != null && selected != null) {
                openUsbSerialPort()
            }
        }
    }

    private val rigStateChanged = object : OnRigStateChanged {
        override fun onDisconnected() {
            _uiState.update {
                it.copy(
                    rigConnected = false,
                    rigPttActive = false,
                    rigStatusText = "CAT disconnected"
                )
            }
        }

        override fun onConnected() {
            _uiState.update {
                it.copy(
                    rigConnected = true,
                    rigStatusText = activeRigProfile?.let { profile ->
                        "CAT ready: ${profile.modelName}"
                    } ?: "CAT ready"
                )
            }
        }

        override fun onPttChanged(isOn: Boolean) {
            _uiState.update {
                it.copy(
                    rigPttActive = isOn,
                    rigStatusText = "Rig PTT ${if (isOn) "ON" else "OFF"}"
                )
            }
        }

        override fun onFreqChanged(freq: Long) {
            _uiState.update {
                it.copy(
                    rigCurrentFreqHz = freq,
                    rigFrequencyText = formatRigFrequencyMHz(freq),
                    rigStatusText = "Frequency read from rig"
                )
            }
            reporterClient.emitFreqChange(freq)
        }

        override fun onRunError(message: String?) {
            _uiState.update {
                it.copy(
                    rigConnected = false,
                    rigStatusText = "CAT error: ${message ?: "unknown error"}"
                )
            }
            addLog("CAT ERROR ${message ?: "unknown error"}")
        }
    }

    init {
        usbSerialController.setOnBytesReceivedListener { bytes ->
            val ascii = bytes.toString(StandardCharsets.US_ASCII)
                .replace("\r", "\\r")
                .replace("\n", "\\n")
            val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            addLog("SERIAL RX ascii=\"$ascii\" hex=$hex")
            activeRig?.onReceiveData(bytes)
        }
        registerUsbPermissionReceiver()
        loadPreferences()
        loadRigProfiles()
        refreshDevices()
        probeUsbSerialDevices()
        addLog("Ready")
        nativeBridge.setRxManualOffsetNative(0f)
        syncNativeTxCallsignForEoo()

        scope.launch(Dispatchers.Default) {
            reporterClient.connected.collect { connected ->
                _reporterUiState.update {
                    it.copy(
                        connected = connected,
                        status = if (connected) "Connected" else "Disconnected"
                    )
                }
                _uiState.update { it.copy(reporterConnected = connected) }
                if (connected) {
                    emitReporterFrequencySnapshot()
                    reporterClient.emitTxReport("RADEV1", false)
                }
            }
        }
        scope.launch(Dispatchers.Default) {
            reporterClient.stations.collect { map ->
                val rawRows = map.values.map { s ->
                    ReporterStationUi(
                        sid = s.sid,
                        callsign = s.callsign,
                        gridSquare = s.gridSquare,
                        version = s.version,
                        frequencyHz = s.frequencyHz,
                        mode = s.mode,
                        status = when {
                            s.transmitting -> "TX"
                            s.rxOnly -> "RX Only"
                            else -> "RX"
                        },
                        message = s.message,
                        lastTx = s.lastTx,
                        lastRxCallsign = s.lastRxCallsign,
                        snr = s.snr,
                        lastUpdate = s.lastUpdate
                    )
                }

                val ourCallsign = _uiState.value.reporterCallsign.trim().uppercase()
                val dedupedRows = if (ourCallsign.isBlank()) {
                    rawRows
                } else {
                    val (ours, others) = rawRows.partition { it.callsign.trim().uppercase() == ourCallsign }
                    val latestOurs = ours.maxByOrNull { it.lastUpdate }
                    if (latestOurs != null) others + latestOurs else others
                }

                val rows = dedupedRows.sortedBy { it.callsign.ifBlank { it.sid } }
                _reporterUiState.update { it.copy(stations = rows) }
            }
        }
        applyReporterConfigFromState()
    }

    fun selectPage(page: AppPage) {
        _uiState.update { it.copy(currentPage = page) }
    }

    fun handleUsbDeviceAttached(deviceKey: String?) {
        probeUsbSerialDevices()
        selectPage(AppPage.SETTINGS)
        if (deviceKey != null) {
            _uiState.value.usbSerialDevices.firstOrNull {
                parseUsbSerialPortKey(it.key).first == deviceKey
            }?.let { option ->
                onUsbSerialDeviceSelected(option.key)
            }
        }
        addLog("USB serial device attached")
    }

    fun markPendingStartAfterPermission() {
        pendingStartAfterPermission = true
    }

    fun onPermissionResult(recordGranted: Boolean) {
        if (pendingStartAfterPermission && recordGranted) {
            pendingStartAfterPermission = false
            startSession()
        } else {
            pendingStartAfterPermission = false
            addLog("Start cancelled: RECORD_AUDIO not granted")
        }
    }

    fun refreshDevices() {
        val previous = _uiState.value.selectedDevices

        inputDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(::shouldKeepDevice)
            .map { AudioDev(it.id, buildDeviceName(it), it) }

        outputDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::shouldKeepDevice)
            .map { AudioDev(it.id, buildDeviceName(it), it) }

        _uiState.update { state ->
            val newSelection = DeviceSelectionUiState(
                txInputId = restoreSelectionId(previous.txInputId, inputDevices),
                txOutputId = restoreSelectionId(previous.txOutputId, outputDevices),
                rxInputId = restoreSelectionId(previous.rxInputId, inputDevices),
                rxOutputId = restoreSelectionId(previous.rxOutputId, outputDevices)
            )

            state.copy(
                inputDevices = inputDevices.map { AudioDeviceOption(it.id, it.name) },
                outputDevices = outputDevices.map { AudioDeviceOption(it.id, it.name) },
                selectedDevices = newSelection
            )
        }
    }

    fun probeUsbSerialDevices() {
        val devices = usbManager.deviceList.values
            .let { allDevices ->
                val probedDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                allDevices.mapNotNull { device ->
                    val driver = probedDrivers.firstOrNull { it.device.deviceId == device.deviceId } ?: return@mapNotNull null
                    driver
                }
            }
            .sortedWith(compareBy<UsbSerialDriver> { it.device.productName ?: "" }.thenBy { it.device.deviceName })
            .flatMap { driver ->
                driver.ports.mapIndexed { portIndex, _ ->
                    UsbSerialDeviceOption(
                        key = buildUsbSerialPortKey(driver.device.deviceName, portIndex),
                        label = buildUsbSerialDeviceLabel(driver, portIndex),
                        detail = buildUsbSerialDeviceDetail(driver, portIndex),
                        portCount = driver.ports.size,
                        portIndex = portIndex
                    )
                }
            }

        val restoredKey = restoreUsbSerialDeviceKey(
            prefs.getString(PREF_USB_SERIAL_DEVICE_KEY, null),
            devices
        )

        _uiState.update {
            it.copy(
                usbSerialDevices = devices,
                selectedUsbSerialDeviceKey = restoredKey,
                selectedUsbSerialPermissionGranted = restoredKey?.let { key ->
                    val (physicalDeviceKey, _) = parseUsbSerialPortKey(key)
                    usbManager.deviceList.values
                        .firstOrNull { device -> device.deviceName == physicalDeviceKey }
                        ?.let(usbManager::hasPermission)
                } ?: false,
                usbSerialPermissionStatus = usbPermissionStatusFor(restoredKey),
                usbSerialDiscoveryStatus = if (devices.isEmpty()) {
                    "No supported USB serial devices found"
                } else {
                    "Found ${devices.size} supported USB serial device(s)"
                },
                serialDevicesRefreshEnabled = true
            )
        }
        addLog(
            if (devices.isEmpty()) "USB PROBE found no supported serial devices"
            else "USB PROBE found ${devices.size} supported serial device(s)"
        )
        if (!usbSerialController.isOpen() &&
            restoredKey != null &&
            usbManager.deviceList.values.firstOrNull {
                it.deviceName == parseUsbSerialPortKey(restoredKey).first
            }?.let(usbManager::hasPermission) == true
        ) {
            openUsbSerialPort()
        }
    }
    fun findAndAuthorizeUsbSerialDevices() {
        probeUsbSerialDevices()
        val currentState = _uiState.value
        val targetKey = currentState.selectedUsbSerialDeviceKey ?: currentState.usbSerialDevices.firstOrNull()?.key
        if (targetKey == null) {
            _uiState.update {
                it.copy(
                    usbSerialPermissionStatus = "No supported USB serial device found",
                    serialPortStatus = "Serial port closed"
                )
            }
            return
        }

        if (currentState.selectedUsbSerialDeviceKey != targetKey) {
            onUsbSerialDeviceSelected(targetKey)
        }

        val (physicalDeviceKey, _) = parseUsbSerialPortKey(targetKey)
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == physicalDeviceKey }
        if (device == null) {
            _uiState.update { it.copy(usbSerialPermissionStatus = "Selected USB serial device is not attached") }
            return
        }
        if (usbManager.hasPermission(device)) {
            _uiState.update {
                it.copy(
                    selectedUsbSerialPermissionGranted = true,
                    usbSerialPermissionStatus = "USB access granted"
                )
            }
            openUsbSerialPort()
        } else {
            requestUsbSerialPermission()
        }
    }

    fun onTxInputSelected(deviceId: Int) = updateSelection { copy(txInputId = deviceId) }
    fun onTxOutputSelected(deviceId: Int) = updateSelection { copy(txOutputId = deviceId) }
    fun onRxInputSelected(deviceId: Int) = updateSelection { copy(rxInputId = deviceId) }
    fun onRxOutputSelected(deviceId: Int) = updateSelection { copy(rxOutputId = deviceId) }
    fun onRigControlModeSelected(mode: RigControlMode) {
        _uiState.update { it.copy(rigControlMode = mode) }
        prefs.edit().putString(PREF_RIG_CONTROL_MODE, mode.name).apply()
        when (mode) {
            RigControlMode.VOX -> {
                detachActiveRig()
                _uiState.update {
                    it.copy(
                        rigConnected = false,
                        rigPttActive = false,
                        rigStatusText = "VOX control selected"
                    )
                }
            }

            RigControlMode.CAT -> {
                activeRig?.setControlMode(ft8CnControlMode(mode))
                activeRigConnector?.setControlMode(ft8CnControlMode(mode))
                connectSelectedRigProfileIfPossible()
            }

            RigControlMode.RTS,
            RigControlMode.DTR -> {
                detachActiveRig()
                _uiState.update {
                    it.copy(
                        rigConnected = usbSerialController.isOpen(),
                        rigPttActive = false,
                        rigStatusText = "${mode.name} line control selected"
                    )
                }
            }
        }
    }

    fun onRigProfileSelected(profileKey: String) {
        val profile = rigProfiles.firstOrNull { it.key == profileKey } ?: return
        activeRigProfile = profile
        prefs.edit().putString(PREF_RIG_PROFILE_KEY, profile.key).apply()
        _uiState.update {
            it.copy(
                selectedRigProfileKey = profile.key,
                selectedRigProfileLabel = profile.modelName,
                serialBaudRate = profile.baudRate,
                rigCivAddress = "%02X".format(profile.civAddress),
                rigStatusText = "Rig profile selected: ${profile.modelName}"
            )
        }
        prefs.edit()
            .putInt(PREF_SERIAL_BAUD_RATE, profile.baudRate)
            .putString(PREF_RIG_CIV_ADDRESS, "%02X".format(profile.civAddress))
            .apply()
        GeneralVariables.instructionSet = profile.instructionSet
        if (usbSerialController.isOpen()) {
            openUsbSerialPort()
        }
    }
    fun onUsbSerialDeviceSelected(deviceKey: String) {
        if (_uiState.value.selectedUsbSerialDeviceKey != deviceKey && usbSerialController.isOpen()) {
            closeUsbSerialPort()
        }
        val (physicalDeviceKey, _) = parseUsbSerialPortKey(deviceKey)
        val alreadyGranted = usbManager.deviceList.values
            .firstOrNull { device -> device.deviceName == physicalDeviceKey }
            ?.let(usbManager::hasPermission) ?: false
        _uiState.update {
            it.copy(
                selectedUsbSerialDeviceKey = deviceKey,
                selectedUsbSerialPermissionGranted = alreadyGranted,
                usbSerialPermissionStatus = usbPermissionStatusFor(deviceKey)
            )
        }
        prefs.edit().putString(PREF_USB_SERIAL_DEVICE_KEY, deviceKey).apply()
        if (alreadyGranted) {
            openUsbSerialPort()
        }
    }
    fun onSerialBaudRateSelected(baudRate: Int) {
        _uiState.update { it.copy(serialBaudRate = baudRate) }
        prefs.edit().putInt(PREF_SERIAL_BAUD_RATE, baudRate).apply()
        if (usbSerialController.isOpen()) {
            closeUsbSerialPort()
            openUsbSerialPort()
        }
    }
    fun requestUsbSerialPermission() {
        val deviceKey = _uiState.value.selectedUsbSerialDeviceKey
        if (deviceKey == null) {
            _uiState.update { it.copy(usbSerialPermissionStatus = "Select a USB serial device first") }
            return
        }
        val (physicalDeviceKey, _) = parseUsbSerialPortKey(deviceKey)
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == physicalDeviceKey }
        if (device == null) {
            probeUsbSerialDevices()
            _uiState.update { it.copy(usbSerialPermissionStatus = "Selected USB serial device is no longer attached") }
            return
        }
        if (usbManager.hasPermission(device)) {
            _uiState.update {
                it.copy(
                    selectedUsbSerialPermissionGranted = true,
                    usbSerialPermissionStatus = "USB access already granted"
                )
            }
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        _uiState.update { it.copy(usbSerialPermissionStatus = "Waiting for USB permission dialog") }
        usbManager.requestPermission(device, permissionIntent)
    }
    fun openUsbSerialPort() {
        detachActiveRig()
        val state = _uiState.value
        val deviceKey = state.selectedUsbSerialDeviceKey
        if (deviceKey == null) {
            _uiState.update { it.copy(serialPortStatus = "Select a USB serial device first") }
            return
        }
        val driver = findSelectedUsbSerialDriver(deviceKey)
        val portIndex = findSelectedUsbSerialPortIndex(deviceKey)
        if (driver == null) {
            probeUsbSerialDevices()
            _uiState.update { it.copy(serialPortStatus = "Selected USB serial device is not available") }
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            _uiState.update { it.copy(serialPortStatus = "USB access required before opening serial port") }
            return
        }
        val result = usbSerialController.open(
            driver = driver,
            portIndex = portIndex,
            baudRate = state.serialBaudRate,
            rts = state.serialRtsEnabled,
            dtr = state.serialDtrEnabled
        )
        result.onSuccess {
            _uiState.update {
                it.copy(
                    serialPortOpen = true,
                    serialPortStatus = "Serial open @ ${state.serialBaudRate} baud"
                )
            }
            addLog("SERIAL OPEN ${driver.javaClass.simpleName} port ${portIndex + 1} @ ${state.serialBaudRate}")
            connectSelectedRigProfileIfPossible()
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    serialPortOpen = false,
                    serialPortStatus = "Serial open failed: ${error.message ?: "unknown error"}"
                )
            }
            addLog("SERIAL OPEN FAILED ${error.message ?: "unknown error"}")
        }
    }
    fun closeUsbSerialPort() {
        detachActiveRig()
        if (!usbSerialController.isOpen()) {
            _uiState.update { it.copy(serialPortOpen = false, serialPortStatus = "Serial port closed") }
            return
        }
        usbSerialController.close()
        _uiState.update {
            it.copy(
                serialPortOpen = false,
                serialPortStatus = "Serial port closed",
                rigConnected = false,
                rigPttActive = false
            )
        }
        addLog("SERIAL CLOSE")
    }
    fun setSerialRts(enabled: Boolean) {
        _uiState.update { it.copy(serialRtsEnabled = enabled) }
        if (!usbSerialController.isOpen()) {
            _uiState.update { it.copy(serialPortStatus = "RTS staged. Grant USB access or select a ready device.") }
            return
        }
        usbSerialController.setRts(enabled)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        serialPortOpen = true,
                        serialPortStatus = "RTS ${if (enabled) "ON" else "OFF"}"
                    )
                }
                addLog("SERIAL CTRL RTS ${if (enabled) "ON" else "OFF"}")
            }
            .onFailure { error ->
                _uiState.update { it.copy(serialPortStatus = "RTS failed: ${error.message ?: "unknown error"}") }
                addLog("SERIAL CTRL RTS FAILED ${error.message ?: "unknown error"}")
            }
    }
    fun setSerialDtr(enabled: Boolean) {
        _uiState.update { it.copy(serialDtrEnabled = enabled) }
        if (!usbSerialController.isOpen()) {
            _uiState.update { it.copy(serialPortStatus = "DTR staged. Grant USB access or select a ready device.") }
            return
        }
        usbSerialController.setDtr(enabled)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        serialPortOpen = true,
                        serialPortStatus = "DTR ${if (enabled) "ON" else "OFF"}"
                    )
                }
                addLog("SERIAL CTRL DTR ${if (enabled) "ON" else "OFF"}")
            }
            .onFailure { error ->
                _uiState.update { it.copy(serialPortStatus = "DTR failed: ${error.message ?: "unknown error"}") }
                addLog("SERIAL CTRL DTR FAILED ${error.message ?: "unknown error"}")
            }
    }
    fun pulseRigPttTest() {
        if (!usbSerialController.isOpen()) {
            _uiState.update { it.copy(rigStatusText = "Open a serial device before testing PTT") }
            return
        }
        scope.launch {
            when (_uiState.value.rigControlMode) {
                RigControlMode.VOX -> {
                    _uiState.update { it.copy(rigStatusText = "VOX mode has no manual PTT test") }
                }

                RigControlMode.CAT -> {
                    val rig = activeRig
                    if (rig == null) {
                        _uiState.update { it.copy(rigStatusText = "Select a CAT rig profile first") }
                        return@launch
                    }
                    addLog("PTT TEST CAT ON")
                    rig.setPTT(true)
                    _uiState.update { it.copy(rigPttActive = true, rigStatusText = "PTT test running") }
                    delay(500)
                    rig.setPTT(false)
                    _uiState.update { it.copy(rigPttActive = false, rigStatusText = "PTT test complete") }
                    addLog("PTT TEST CAT OFF")
                }

                RigControlMode.RTS -> {
                    addLog("PTT TEST RTS ON")
                    setSerialRts(true)
                    _uiState.update { it.copy(rigPttActive = true, rigStatusText = "PTT test running") }
                    delay(500)
                    setSerialRts(false)
                    _uiState.update { it.copy(rigPttActive = false, rigStatusText = "PTT test complete") }
                    addLog("PTT TEST RTS OFF")
                }

                RigControlMode.DTR -> {
                    addLog("PTT TEST DTR ON")
                    setSerialDtr(true)
                    _uiState.update { it.copy(rigPttActive = true, rigStatusText = "PTT test running") }
                    delay(500)
                    setSerialDtr(false)
                    _uiState.update { it.copy(rigPttActive = false, rigStatusText = "PTT test complete") }
                    addLog("PTT TEST DTR OFF")
                }
            }
        }
    }
    fun onAgcEnabledChanged(enabled: Boolean) {
        _uiState.update { it.copy(agcEnabled = enabled) }
        prefs.edit().putBoolean(PREF_AGC_ENABLED, enabled).apply()
    }
    fun onReporterEnabledChanged(enabled: Boolean) {
        _uiState.update { it.copy(reporterEnabled = enabled) }
        prefs.edit().putBoolean(PREF_REPORTER_ENABLED, enabled).apply()
        applyReporterConfigFromState()
    }

    fun onReporterCallsignChanged(value: String) {
        if (!_uiState.value.reporterEnabled) return
        val normalized = value.trim().uppercase().filter { it.isLetterOrDigit() || it == '/' }
        _uiState.update { it.copy(reporterCallsign = normalized) }
        prefs.edit().putString(PREF_REPORTER_CALLSIGN, normalized).apply()
        syncNativeTxCallsignForEoo()
        applyReporterConfigFromState()
    }

    fun onReporterGridChanged(value: String) {
        if (!_uiState.value.reporterEnabled) return
        val normalized = value.trim().uppercase().filter { it.isLetterOrDigit() }
        _uiState.update { it.copy(reporterGridSquare = normalized) }
        prefs.edit().putString(PREF_REPORTER_GRID, normalized).apply()
        applyReporterConfigFromState()
    }

    fun onReporterMessageChanged(value: String) {
        if (!_uiState.value.reporterEnabled) return
        val normalized = value.take(80)
        _uiState.update { it.copy(reporterMessage = normalized) }
        prefs.edit().putString(PREF_REPORTER_MESSAGE, normalized).apply()
    }

    fun onReporterManualFrequencyChanged(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val normalized = buildString {
            var dotSeen = false
            for (ch in filtered) {
                if (ch == '.') {
                    if (dotSeen) continue
                    dotSeen = true
                }
                append(ch)
            }
        }
        _uiState.update { it.copy(reporterManualFreqMHz = normalized) }
        prefs.edit().putString(PREF_REPORTER_MANUAL_FREQ_MHZ, normalized).apply()
        applyReporterConfigFromState()
    }

    fun onReporterFrequencyPresetSelected(valueMHz: String) {
        _uiState.update { it.copy(reporterManualFreqMHz = valueMHz) }
        prefs.edit().putString(PREF_REPORTER_MANUAL_FREQ_MHZ, valueMHz).apply()
        applyReporterConfigFromState()
    }

    fun onReporterSendMessage() {
        val state = _uiState.value
        if (!state.reporterEnabled) {
            _reporterUiState.update { it.copy(status = "Please enable reporter first") }
            addLog("Reporter: enable switch is OFF")
            return
        }
        reporterClient.emitMessageUpdate(state.reporterMessage)
    }

    fun onReporterRxOnlyChanged(value: Boolean) {
        if (!_uiState.value.reporterEnabled) {
            _reporterUiState.update { it.copy(status = "Please enable reporter first") }
            addLog("Reporter: enable switch is OFF")
            return
        }
        _uiState.update { it.copy(reporterRxOnly = value) }
        prefs.edit().putBoolean(PREF_REPORTER_RX_ONLY, value).apply()
        applyReporterConfigFromState()
    }
    fun onRigCivAddressChanged(value: String) {
        val sanitized = value.trim().uppercase().filter { it.isDigit() || it in 'A'..'F' }
        _uiState.update { it.copy(rigCivAddress = sanitized) }
        prefs.edit().putString(PREF_RIG_CIV_ADDRESS, sanitized).apply()
    }
    fun onRigFrequencyTextChanged(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val sanitized = buildString {
            var dotSeen = false
            for (ch in filtered) {
                if (ch == '.') {
                    if (dotSeen) continue
                    dotSeen = true
                }
                append(ch)
            }
        }
        _uiState.update { it.copy(rigFrequencyText = sanitized) }
    }

    fun sendRigFrequency() {
        val mhz = _uiState.value.rigFrequencyText.toDoubleOrNull()
        val freq = mhz?.times(1_000_000.0)?.toLong()
        if (freq == null || mhz <= 0.0) {
            _uiState.update { it.copy(rigStatusText = "Enter a valid frequency in MHz") }
            return
        }
        val rig = activeRig
        if (rig != null) {
            rig.setFreq(freq)
            rig.setUsbModeToRig()
            rig.setFreqToRig()
            _uiState.update { it.copy(rigStatusText = "Setting frequency...") }
            scope.launch {
                delay(250)
                val before = _uiState.value.rigCurrentFreqHz
                rig.readFreqFromRig()
                delay(450)
                val after = _uiState.value.rigCurrentFreqHz
                _uiState.update {
                    when (after) {
                        freq -> it.copy(
                            rigCurrentFreqHz = after,
                            rigFrequencyText = formatRigFrequencyMHz(after),
                            rigStatusText = "Frequency set successfully"
                        )

                        before -> it.copy(
                            rigStatusText = "Set frequency failed: no updated rig response"
                        )

                        else -> it.copy(
                            rigStatusText = "Set frequency failed: rig reported ${after ?: "no frequency"}"
                        )
                    }
                }
            }
            return
        }
        _uiState.update { it.copy(rigStatusText = "Connect CAT before setting frequency") }
    }
    private fun setRigPttImmediate(on: Boolean): Boolean {
        return when (_uiState.value.rigControlMode) {
            RigControlMode.VOX -> true

            RigControlMode.CAT -> {
                val rig = activeRig
                if (rig == null) {
                    _uiState.update {
                        it.copy(rigStatusText = "PTT control is not ready")
                    }
                    false
                } else {
                    runCatching {
                        rig.setPTT(on)
                        _uiState.update {
                            it.copy(
                                rigPttActive = on,
                                rigStatusText = if (on) "Rig PTT ON" else "Rig PTT OFF"
                            )
                        }
                    }.isSuccess
                }
            }

            RigControlMode.RTS -> {
                usbSerialController.setRts(on)
                    .onSuccess {
                        _uiState.update {
                            it.copy(
                                rigConnected = true,
                                rigPttActive = on,
                                rigStatusText = if (on) "RTS PTT ON" else "RTS PTT OFF"
                            )
                        }
                        addLog("SERIAL CTRL RTS ${if (on) "ON" else "OFF"}")
                    }
                    .isSuccess
            }

            RigControlMode.DTR -> {
                usbSerialController.setDtr(on)
                    .onSuccess {
                        _uiState.update {
                            it.copy(
                                rigConnected = true,
                                rigPttActive = on,
                                rigStatusText = if (on) "DTR PTT ON" else "DTR PTT OFF"
                            )
                        }
                        addLog("SERIAL CTRL DTR ${if (on) "ON" else "OFF"}")
                    }
                    .isSuccess
            }
        }
    }
    fun onAgcClipDbChanged(value: Float) {
        _uiState.update { it.copy(agcClipDb = value) }
        prefs.edit().putFloat(PREF_AGC_CLIP_DB, value).apply()
    }
    fun onAgcStrengthChanged(value: Float) {
        _uiState.update { it.copy(agcStrength = value) }
        prefs.edit().putFloat(PREF_AGC_STRENGTH, value).apply()
    }
    fun adjustManualRxOffset(stepHz: Int) {
        manualRxOffsetHz = (manualRxOffsetHz + stepHz).coerceIn(-1000, 1000)
        nativeBridge.setRxManualOffsetNative(manualRxOffsetHz.toFloat())
        _uiState.update { it.copy(manualRxOffsetHz = manualRxOffsetHz) }
    }

    fun startSession() {
        synchronized(stateLock) {
            if (running) return
            running = true
            pttDown = false
            mode = SessionMode.IDLE
            switchToken++
        }

        lastRxStatsUpdateMs = 0L
        smoothedMicDb = -50.0
        txAgcGain = 1.0
        rxResyncInProgress = false
        resetSpectrum()
        stopMeterUiTicker()

        updateSessionUi(
            sessionRunning = true,
            startEnabled = false,
            stopEnabled = true,
            resyncEnabled = true,
            pttEnabled = true,
            selectorsEnabled = false,
            refreshEnabled = false,
            syncStatus = "SEARCHING",
            pttColor = 0xFF9E9E9E.toInt(),
            mode = SessionMode.IDLE,
            rxSnrDb = null,
            rxFreqOffsetHz = null
        )
        _meterState.value = MeterUiState()

        SessionKeepAliveService.start(context)
        startRxFull(switchToken)
        reporterClient.emitTxReport("RADEV1", false)
        addLog("Session started")
    }

    fun stopSession() {
        synchronized(stateLock) {
            if (!running && mode == SessionMode.IDLE) return
            running = false
            pttDown = false
            txReleaseInProgress = false
            mode = SessionMode.IDLE
            rxResyncInProgress = false
            switchToken++
        }

        stopTxInternal()
        stopRxInternal()
        stopMeterUiTicker()
        clearAudioRoute()
        SessionKeepAliveService.stop(context)
        resetSpectrum()

        updateSessionUi(
            sessionRunning = false,
            startEnabled = true,
            stopEnabled = false,
            resyncEnabled = false,
            pttEnabled = false,
            selectorsEnabled = true,
            refreshEnabled = true,
            syncStatus = "SEARCHING",
            pttColor = 0xFF9E9E9E.toInt(),
            mode = SessionMode.IDLE,
            rxSnrDb = null,
            rxFreqOffsetHz = null
        )
        _meterState.value = MeterUiState()

        addLog("Session stopped")
    }

    fun resyncRx() {
        synchronized(stateLock) {
            if (!running || pttDown || rxResyncInProgress) return
            rxResyncInProgress = true
        }

        _uiState.update { it.copy(resyncEnabled = false) }

        thread(name = "rx-soft-resync") {
            try {
                synchronized(nativeRxLock) {
                    nativeBridge.resetRxNative()
                }
                publishRxStats(sync = 0, snr = null, freqOffset = null)
                addLog("RX resynced")
            } finally {
                rxResyncInProgress = false
                _uiState.update { state ->
                    state.copy(resyncEnabled = running && !pttDown)
                }
            }
        }
    }

    fun onPttPressed() {
        synchronized(stateLock) {
            if (!running || pttDown || txReleaseInProgress) return
            pttDown = true
            txReleaseInProgress = false
            switchToken++
        }
        emitReporterFrequencySnapshot()

        val token = switchToken
        smoothedMicDb = -50.0
        targetMicMeter = MeterUiState(
            title = "MIC LEVEL",
            valueText = "NaN",
            percent = 0,
            colorArgb = 0xFF4CAF50.toInt()
        )
        displayedMicDb = -50.0
        displayedMicPercent = 0.0
        displayedMicColor = 0xFF4CAF50.toInt()
        publishMode(SessionMode.TX_PREPARING, 0xFF1976D2.toInt())
        publishMeter(targetMicMeter)
        syncNativeTxCallsignForEoo()

        thread(name = "ptt-press") {
            addLog("TX preparing...")

            if (!setRigPttImmediate(true)) {
                addLog("PTT control start failed")
                synchronized(stateLock) {
                    pttDown = false
                    switchToken++
                }
                publishMode(SessionMode.RX, 0xFF9E9E9E.toInt())
                return@thread
            }

            stopRxInternal()

            if (!running || !pttDown || token != switchToken) {
                setRigPttImmediate(false)
                if (running && token == switchToken) {
                    startRxFull(token)
                }
                publishMode(SessionMode.IDLE, 0xFF9E9E9E.toInt())
                return@thread
            }

            val ok = startTxFull(token)
            if (!ok) {
                addLog("TX start failed")
                setRigPttImmediate(false)
                if (running && token == switchToken) {
                    startRxFull(token)
                }
                publishMode(SessionMode.IDLE, 0xFF9E9E9E.toInt())
                return@thread
            }

            mode = SessionMode.TX
            _uiState.update { it.copy(mode = SessionMode.TX_PREPARING) }
        }
    }

    fun onPttReleased() {
        val token: Long
        synchronized(stateLock) {
            if (!running || !pttDown || txReleaseInProgress) return
            txReleaseInProgress = true
            token = switchToken
        }

        stopMeterUiTicker()

        thread(name = "ptt-release") {
            syncNativeTxCallsignForEoo()
            // Match reference behavior: stop feeding new mic frames before appending EOO.
            txThread?.join(300)

            val eooSamples = nativeBridge.appendTxEoo()
            if (eooSamples > 0) {
                addLog("EOO queued ($eooSamples samples)")
            } else {
                addLog("EOO skipped (empty callsign or unavailable)")
            }
            val playbackQueue = txPlaybackQueue
            if (playbackQueue != null) {
                val chunk = ShortArray(BASEBAND_FRAME)
                var idleRounds = 0
                val maxRounds = 600
                var rounds = 0
                while (rounds < maxRounds) {
                    val had = nativeBridge.drainTxQueuedFrame(chunk)
                    if (had > 0) {
                        val copy = chunk.copyOf()
                        if (!playbackQueue.offer(copy)) {
                            playbackQueue.poll()
                            playbackQueue.offer(copy)
                        }
                        idleRounds = 0
                    } else {
                        idleRounds++
                        if (idleRounds >= 6) break
                        Thread.sleep(10)
                    }
                    rounds++
                }
                // Match FreeDV desktop behavior: append 60 ms of post-EOO silence.
                repeat(6) {
                    val silence = ShortArray(BASEBAND_FRAME)
                    if (!playbackQueue.offer(silence)) {
                        playbackQueue.poll()
                        playbackQueue.offer(silence)
                    }
                }
            }

            val baseHoldMs = ((eooSamples / 8L) + 120L).coerceIn(140L, 1000L)
            Thread.sleep(baseHoldMs)

            val queueWaitStart = System.currentTimeMillis()
            while ((txPlaybackQueue?.isNotEmpty() == true) &&
                System.currentTimeMillis() - queueWaitStart < 1800L
            ) {
                Thread.sleep(10)
            }
            reporterClient.emitTxReport("RADEV1", false)

            val nextToken: Long
            synchronized(stateLock) {
                if (!running || token != switchToken) {
                    txReleaseInProgress = false
                    return@thread
                }
                pttDown = false
                txReleaseInProgress = false
                switchToken++
                nextToken = switchToken
            }

            stopTxInternal()
            setRigPttImmediate(false)

            if (running && nextToken == switchToken) {
                startRxFull(nextToken)
                mode = SessionMode.RX
                publishMode(SessionMode.RX, 0xFF9E9E9E.toInt())
                addLog("RX standby")
            } else {
                publishMode(SessionMode.IDLE, 0xFF9E9E9E.toInt())
            }
        }
    }

    fun destroy() {
        stopSession()
        unregisterUsbPermissionReceiver()
        scope.cancel()
    }

    private fun publishMode(nextMode: SessionMode, pttColor: Int) {
        _uiState.update {
            it.copy(
                mode = nextMode,
                pttColorArgb = pttColor,
                syncStatus = when (nextMode) {
                    SessionMode.TX_PREPARING -> "PREPARING"
                    SessionMode.TX -> "TRANSMITTING"
                    else -> it.syncStatus
                }
            )
        }
    }

    private fun publishMeter(meter: MeterUiState) {
        _meterState.value = meter
    }

    private fun startMeterUiTicker() {
        if (meterTickerJob?.isActive == true) return
        meterTickerJob = scope.launch {
            while (true) {
                val targetDb = targetMicMeter.valueText.removeSuffix(" dB").toDoubleOrNull() ?: -50.0
                val targetPercent = targetMicMeter.percent.toDouble()
                val dbAlpha = if (targetDb > displayedMicDb) 0.18 else 0.45
                val percentAlpha = if (targetPercent > displayedMicPercent) 0.14 else 0.38
                displayedMicDb = dbAlpha * displayedMicDb + (1.0 - dbAlpha) * targetDb
                displayedMicPercent = percentAlpha * displayedMicPercent + (1.0 - percentAlpha) * targetPercent
                displayedMicColor = targetMicMeter.colorArgb
                publishMeter(
                    MeterUiState(
                        title = "MIC LEVEL",
                        valueText = "${displayedMicDb.toInt()} dB",
                        percent = displayedMicPercent.toInt().coerceIn(0, 100),
                        colorArgb = displayedMicColor
                    )
                )
                delay(16)
            }
        }
    }

    private fun stopMeterUiTicker() {
        meterTickerJob?.cancel()
        meterTickerJob = null
    }

    private fun publishRxStats(sync: Int, snr: Int?, freqOffset: Float?) {
        val meter = if (sync == 0 || snr == null) {
            MeterUiState()
        } else {
            val snrClamped = snr.coerceIn(-5, 20)
            val percent = ((snrClamped + 5) * 100) / 25
            MeterUiState(
                title = "RX SNR",
                valueText = "$snr dB",
                percent = percent,
                colorArgb = 0xFF4CAF50.toInt()
            )
        }

        _uiState.update {
            it.copy(
                syncStatus = if (sync == 0) "SEARCHING" else "SYNCED",
                rxSnrDb = snr,
                rxFreqOffsetHz = if (sync == 0) null else freqOffset
            )
        }
        publishMeter(meter)
    }

    private fun updateSessionUi(
        sessionRunning: Boolean,
        startEnabled: Boolean,
        stopEnabled: Boolean,
        resyncEnabled: Boolean,
        pttEnabled: Boolean,
        selectorsEnabled: Boolean,
        refreshEnabled: Boolean,
        syncStatus: String,
        pttColor: Int,
        mode: SessionMode,
        rxSnrDb: Int?,
        rxFreqOffsetHz: Float?
    ) {
        _uiState.update {
            it.copy(
                sessionRunning = sessionRunning,
                startEnabled = startEnabled,
                stopEnabled = stopEnabled,
                resyncEnabled = resyncEnabled,
                pttEnabled = pttEnabled,
                selectorsEnabled = selectorsEnabled,
                refreshEnabled = refreshEnabled,
                syncStatus = syncStatus,
                pttColorArgb = pttColor,
                mode = mode,
                rxSnrDb = rxSnrDb,
                rxFreqOffsetHz = rxFreqOffsetHz
            )
        }
    }

    private fun updateSelection(transform: DeviceSelectionUiState.() -> DeviceSelectionUiState) {
        _uiState.update { it.copy(selectedDevices = it.selectedDevices.transform()) }
    }

    private fun loadRigProfiles() {
        rigProfiles = Ft8CnRigCatalog.load(context)
        val preferredKey = prefs.getString(PREF_RIG_PROFILE_KEY, null)
        activeRigProfile = rigProfiles.firstOrNull { it.key == preferredKey } ?: rigProfiles.firstOrNull()
        _uiState.update {
            it.copy(
                rigProfiles = rigProfiles.map { profile -> RigProfileOption(profile.key, profile.modelName) },
                selectedRigProfileKey = activeRigProfile?.key,
                selectedRigProfileLabel = activeRigProfile?.modelName ?: "None"
            )
        }
    }

    private fun loadPreferences() {
        _uiState.update {
            it.copy(
                rigControlMode = prefs.getString(PREF_RIG_CONTROL_MODE, null)
                    ?.let { saved -> RigControlMode.entries.firstOrNull { mode -> mode.name == saved } }
                    ?: it.rigControlMode,
                selectedUsbSerialDeviceKey = prefs.getString(PREF_USB_SERIAL_DEVICE_KEY, null),
                serialBaudRate = prefs.getInt(PREF_SERIAL_BAUD_RATE, it.serialBaudRate)
                    .takeIf { saved -> supportedBaudRates.contains(saved) } ?: it.serialBaudRate,
                rigCivAddress = prefs.getString(PREF_RIG_CIV_ADDRESS, it.rigCivAddress) ?: it.rigCivAddress,
                agcEnabled = prefs.getBoolean(PREF_AGC_ENABLED, it.agcEnabled),
                agcClipDb = prefs.getFloat(PREF_AGC_CLIP_DB, it.agcClipDb).coerceIn(-18f, 0f),
                agcStrength = prefs.getFloat(PREF_AGC_STRENGTH, it.agcStrength).coerceIn(0f, 1.5f),
                reporterEnabled = prefs.getBoolean(PREF_REPORTER_ENABLED, it.reporterEnabled),
                reporterCallsign = prefs.getString(PREF_REPORTER_CALLSIGN, it.reporterCallsign) ?: it.reporterCallsign,
                reporterGridSquare = prefs.getString(PREF_REPORTER_GRID, it.reporterGridSquare) ?: it.reporterGridSquare,
                reporterMessage = prefs.getString(PREF_REPORTER_MESSAGE, it.reporterMessage) ?: it.reporterMessage,
                reporterRxOnly = prefs.getBoolean(PREF_REPORTER_RX_ONLY, it.reporterRxOnly),
                reporterManualFreqMHz = prefs.getString(PREF_REPORTER_MANUAL_FREQ_MHZ, it.reporterManualFreqMHz) ?: it.reporterManualFreqMHz
            )
        }
    }

    private fun applyReporterConfigFromState() {
        val state = _uiState.value
        reporterClient.updateConfig(
            FreeDvReporterClient.Config(
                enabled = state.reporterEnabled,
                callsign = state.reporterCallsign,
                gridSquare = state.reporterGridSquare,
                version = "RADEXCVR/$appVersionName",
                rxOnly = state.reporterRxOnly
            )
        )
        syncNativeTxCallsignForEoo()
        emitReporterFrequencySnapshot()
    }

    private fun syncNativeTxCallsignForEoo() {
        val state = _uiState.value
        nativeBridge.setTxCallsign(if (state.reporterEnabled) state.reporterCallsign else "")
    }

    private fun currentReporterFrequencyHz(): Long? {
        val state = _uiState.value
        return if (state.rigControlMode == RigControlMode.CAT) {
            state.rigCurrentFreqHz
        } else {
            val mhz = state.reporterManualFreqMHz.toDoubleOrNull()
            if (mhz != null && mhz > 0) (mhz * 1_000_000.0).toLong() else null
        }
    }

    private fun emitReporterFrequencySnapshot() {
        currentReporterFrequencyHz()?.let { reporterClient.emitFreqChange(it) }
    }

    private fun ft8CnControlMode(mode: RigControlMode): Int {
        return when (mode) {
            RigControlMode.VOX -> com.bg7yoz.ft8cn.database.ControlMode.VOX
            RigControlMode.CAT -> com.bg7yoz.ft8cn.database.ControlMode.CAT
            RigControlMode.RTS -> com.bg7yoz.ft8cn.database.ControlMode.RTS
            RigControlMode.DTR -> com.bg7yoz.ft8cn.database.ControlMode.DTR
        }
    }

    private fun formatRigFrequencyMHz(freqHz: Long): String {
        return String.format("%.6f", freqHz / 1_000_000.0)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun maidenheadToLatLon(grid: String): Pair<Double, Double>? {
        val normalized = grid.uppercase()
        if (normalized.length < 4) return null
        if (!normalized[0].isLetter() || !normalized[1].isLetter()) return null
        if (!normalized[2].isDigit() || !normalized[3].isDigit()) return null

        var lon = (normalized[0] - 'A') * 20.0 - 180.0
        var lat = (normalized[1] - 'A') * 10.0 - 90.0
        lon += (normalized[2] - '0') * 2.0
        lat += (normalized[3] - '0') * 1.0
        var lonSize = 2.0
        var latSize = 1.0

        if (normalized.length >= 6 &&
            normalized[4].isLetter() &&
            normalized[5].isLetter()
        ) {
            lon += (normalized[4] - 'A') * (5.0 / 60.0)
            lat += (normalized[5] - 'A') * (2.5 / 60.0)
            lonSize = 5.0 / 60.0
            latSize = 2.5 / 60.0
        }

        return (lat + latSize / 2.0) to (lon + lonSize / 2.0)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(phi2)
        val x = kotlin.math.cos(phi1) * kotlin.math.sin(phi2) -
            kotlin.math.sin(phi1) * kotlin.math.cos(phi2) * kotlin.math.cos(deltaLon)
        val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    private fun connectSelectedRigProfileIfPossible() {
        val profile = activeRigProfile ?: return
        if (_uiState.value.rigControlMode != RigControlMode.CAT) return
        if (!usbSerialController.isOpen()) return

        detachActiveRig()

        val rig = Ft8CnRigFactory.create(profile)
        if (rig == null) {
            _uiState.update { it.copy(rigStatusText = "Unsupported FT8CN instruction set ${profile.instructionSet}") }
            return
        }

        GeneralVariables.instructionSet = profile.instructionSet
        GeneralVariables.connectMode = com.bg7yoz.ft8cn.connector.ConnectMode.USB_CABLE

        val connector = Ft8CnUsbRigConnector(
            controlMode = ft8CnControlMode(_uiState.value.rigControlMode),
            transportOpen = { usbSerialController.isOpen() },
            writeBytes = { bytes ->
                usbSerialController.writeBytes(bytes).onSuccess {
                    addLog("CAT TX ${bytes.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }}")
                }
            },
            setRts = { enabled -> usbSerialController.setRts(enabled) },
            setDtr = { enabled -> usbSerialController.setDtr(enabled) },
            onLog = ::addLog
        )

        rig.setCivAddress(profile.civAddress)
        rig.setBaudRate(_uiState.value.serialBaudRate)
        rig.setControlMode(ft8CnControlMode(_uiState.value.rigControlMode))
        rig.setOnRigStateChanged(rigStateChanged)
        rig.setConnector(connector)
        connector.connect()

        activeRigProfile = profile
        activeRig = rig
        activeRigConnector = connector
        _uiState.update {
            it.copy(
                rigConnected = rig.isConnected,
                rigStatusText = "CAT ready: ${profile.modelName}",
                rigCivAddress = "%02X".format(profile.civAddress)
            )
        }
    }

    private fun detachActiveRig() {
        runCatching { activeRig?.setPTT(false) }
        runCatching { activeRigConnector?.disconnect() }
        runCatching { activeRig?.onDisconnecting() }
        activeRig = null
        activeRigConnector = null
    }

    private fun usbPermissionStatusFor(deviceKey: String?): String {
        if (deviceKey == null) return "No USB serial device selected"
        val (physicalDeviceKey, _) = parseUsbSerialPortKey(deviceKey)
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == physicalDeviceKey }
            ?: return "Selected USB serial device is not attached"
        return if (usbManager.hasPermission(device)) {
            "USB access granted"
        } else {
            "USB access required"
        }
    }

    private fun findSelectedUsbSerialDriver(deviceKey: String): UsbSerialDriver? {
        val (physicalDeviceKey, _) = parseUsbSerialPortKey(deviceKey)
        return UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceName == physicalDeviceKey }
    }

    private fun findSelectedUsbSerialPortIndex(deviceKey: String): Int {
        return parseUsbSerialPortKey(deviceKey).second
    }

    private fun registerUsbPermissionReceiver() {
        if (usbPermissionReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        usbPermissionReceiverRegistered = true
    }

    private fun unregisterUsbPermissionReceiver() {
        if (!usbPermissionReceiverRegistered) return
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }
        usbPermissionReceiverRegistered = false
    }

    private fun restoreUsbSerialDeviceKey(
        preferredKey: String?,
        devices: List<UsbSerialDeviceOption>
    ): String? {
        return when {
            preferredKey != null && devices.any { it.key == preferredKey } -> preferredKey
            devices.isNotEmpty() -> devices.first().key
            else -> null
        }
    }

    private fun buildUsbSerialDeviceLabel(driver: UsbSerialDriver, portIndex: Int): String {
        val device = driver.device
        val chip = driver.javaClass.simpleName.removeSuffix("SerialDriver")
        val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
        val product = device.productName?.takeIf { it.isNotBlank() }
        val path = device.deviceName.substringAfterLast('/')
        val portLabel = if (driver.ports.size > 1) "Port ${portIndex + 1}" else null
        val parts = listOfNotNull(chip, manufacturer, product, portLabel, path).distinct()
        return parts.joinToString(" / ")
    }

    private fun buildUsbSerialDeviceDetail(driver: UsbSerialDriver, portIndex: Int): String {
        val device = driver.device
        val vid = "0x%04X".format(device.vendorId)
        val pid = "0x%04X".format(device.productId)
        return "VID:$vid PID:$pid PORT:${portIndex + 1}/${driver.ports.size} IF:${device.interfaceCount} PATH:${device.deviceName}"
    }

    private fun buildUsbSerialPortKey(deviceKey: String, portIndex: Int): String {
        return "$deviceKey#$portIndex"
    }

    private fun parseUsbSerialPortKey(key: String): Pair<String, Int> {
        val parts = key.split('#', limit = 2)
        if (parts.size != 2) return key to 0
        return parts[0] to (parts[1].toIntOrNull() ?: 0)
    }

    private fun restoreSelectionId(id: Int?, devices: List<AudioDev>): Int? {
        return when {
            id != null && devices.any { it.id == id } -> id
            devices.isNotEmpty() -> devices.first().id
            else -> null
        }
    }

    private fun selectedTxInput(): AudioDev? = inputDevices.firstOrNull {
        it.id == _uiState.value.selectedDevices.txInputId
    }

    private fun selectedTxOutput(): AudioDev? = outputDevices.firstOrNull {
        it.id == _uiState.value.selectedDevices.txOutputId
    }

    private fun selectedRxInput(): AudioDev? = inputDevices.firstOrNull {
        it.id == _uiState.value.selectedDevices.rxInputId
    }

    private fun selectedRxOutput(): AudioDev? = outputDevices.firstOrNull {
        it.id == _uiState.value.selectedDevices.rxOutputId
    }

    private fun addLog(message: String) {
        val t = java.time.LocalTime.now()
        val line = String.format(
            "%02d:%02d:%02d  %s",
            t.hour,
            t.minute,
            t.second,
            message
        )

        synchronized(logLines) {
            while (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(line)
            val snapshot = logLines.toList()
            scope.launch {
                _uiState.update { it.copy(logs = snapshot) }
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
            else -> false
        }
    }

    private fun resetSpectrum() {
        synchronized(spectrumWindow) {
            spectrumWindow.fill(0)
            spectrumWriteIndex = 0
            spectrumSampleCount = 0
        }
        waterfallFloorDb = -70.0
        waterfallPeakDb = -25.0
        _uiState.update { it.copy(waterfall = emptyList()) }
    }

    private fun appendRxSpectrumSamples(samples: ShortArray) {
        synchronized(spectrumWindow) {
            for (sample in samples) {
                spectrumWindow[spectrumWriteIndex] = sample
                spectrumWriteIndex = (spectrumWriteIndex + 1) % spectrumWindow.size
                spectrumSampleCount = min(spectrumSampleCount + 1, spectrumWindow.size)
            }
        }
    }

    private fun captureSpectrumLine(): List<Float> {
        val ordered = ShortArray(SPECTRUM_WINDOW)
        synchronized(spectrumWindow) {
            if (spectrumSampleCount < SPECTRUM_WINDOW) {
                return List(SPECTRUM_BINS) { 0f }
            }

            var idx = spectrumWriteIndex
            for (i in ordered.indices) {
                ordered[i] = spectrumWindow[idx]
                idx = (idx + 1) % spectrumWindow.size
            }
        }

        val dbBins = MutableList(SPECTRUM_BINS) { -80.0 }
        for (bin in 0 until SPECTRUM_BINS) {
            var real = 0.0
            var imag = 0.0
            val freqHz = WATERFALL_FREQ_START_HZ +
                (WATERFALL_FREQ_END_HZ - WATERFALL_FREQ_START_HZ) * bin / (SPECTRUM_BINS - 1)
            val freq = freqHz / BASEBAND_SR
            for (n in ordered.indices) {
                val window = 0.5 - 0.5 * cos((2.0 * Math.PI * n) / (ordered.size - 1))
                val sample = (ordered[n] / 32768.0) * window
                val phase = 2.0 * Math.PI * freq * n
                real += sample * cos(phase)
                imag -= sample * sin(phase)
            }
            val magnitude = sqrt(real * real + imag * imag) / ordered.size
            dbBins[bin] = (20.0 * log10(max(magnitude, 1e-6))).coerceIn(-90.0, 0.0)
        }

        val linePeakDb = dbBins.maxOrNull() ?: -90.0
        val lineMedianDb = dbBins.sorted()[dbBins.size / 2]

        waterfallPeakDb = 0.82 * waterfallPeakDb + 0.18 * linePeakDb
        waterfallFloorDb = 0.90 * waterfallFloorDb + 0.10 * (lineMedianDb - 7.0)

        if (waterfallPeakDb < waterfallFloorDb + 18.0) {
            waterfallPeakDb = waterfallFloorDb + 18.0
        }

        val dynamicRange = (waterfallPeakDb - waterfallFloorDb).coerceIn(18.0, 42.0)
        return dbBins.map { db ->
            (((db - waterfallFloorDb) / dynamicRange).coerceIn(0.0, 1.0)).toFloat()
        }
    }

    private fun pushWaterfallLine(line: List<Float>) {
        _uiState.update { state ->
            val history = (state.waterfall + listOf(line)).takeLast(WATERFALL_HISTORY)
            state.copy(waterfall = history)
        }
    }

    private fun calculateFrameDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return -50.0
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / samples.size)
        return 20.0 * log10(max(rms, 1.0) / 32767.0)
    }

    private fun applyTxAgcInPlace(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (!_uiState.value.agcEnabled) return

        val db = calculateFrameDb(samples)
        val desiredGainDb = (txAgcTargetDb - db).coerceIn(-12.0, 18.0)
        val agcStrength = _uiState.value.agcStrength.toDouble()
        var targetGain = exp(desiredGainDb / 20.0 * ln(10.0))
        targetGain = 1.0 + (targetGain - 1.0) * agcStrength
        targetGain = targetGain.coerceIn(txAgcMinGain, txAgcMaxGain)
        val clipDb = _uiState.value.agcClipDb.toDouble()
        val clipLevel = 32767.0 * exp(clipDb / 20.0 * ln(10.0))

        val alpha = if (targetGain > txAgcGain) 0.55 else 0.88
        txAgcGain = alpha * txAgcGain + (1.0 - alpha) * targetGain

        for (i in samples.indices) {
            val out = samples[i] * txAgcGain
            val clipped = when {
                out > clipLevel -> clipLevel.toInt()
                out < -clipLevel -> (-clipLevel).toInt()
                else -> out.toInt()
            }
            samples[i] = clipped.toShort()
        }
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

            audioManager.isSpeakerphoneOn =
                dev.info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
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
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                recorder.preferredDevice = dev
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) recorder else {
                try {
                    recorder.release()
                } catch (_: Exception) {
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPlayer(sr: Int, buf: Int, dev: AudioDeviceInfo): AudioTrack? {
        return try {
            val player = AudioTrack(
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
                player.preferredDevice = dev
            }
            if (player.state == AudioTrack.STATE_INITIALIZED) player else {
                try {
                    player.release()
                } catch (_: Exception) {
                }
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
        val player = buildPlayer(SPEECH_SR, minPlay * 4, rxOut.info) ?: run {
            recorder.release()
            return false
        }

        val rc = synchronized(nativeRxLock) { nativeBridge.startRxAudio() }
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
            try {
                nativeBridge.stopRx()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
            rxRecorder = null
            rxPlayer = null
            return false
        }

        rxThread = thread(name = "rx-thread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val in80 = ShortArray(BASEBAND_FRAME)
            val out160 = ShortArray(SPEECH_FRAME)

            while (running && !pttDown && token == switchToken) {
                if (rxRecorder !== recorder || rxPlayer !== player) break

                val n = try {
                    recorder.read(in80, 0, in80.size)
                } catch (_: Exception) {
                    break
                }

                if (n != in80.size) continue
                appendRxSpectrumSamples(in80)

                val ret = try {
                    synchronized(nativeRxLock) {
                        nativeBridge.processRxBasebandFrame(in80, out160)
                    }
                } catch (_: Exception) {
                    -1
                }

                val now = System.currentTimeMillis()
                if (now - lastRxStatsUpdateMs >= 120) {
                    lastRxStatsUpdateMs = now

                    val sync = synchronized(nativeRxLock) { nativeBridge.getRxSyncNative() }
                    if (sync == 0) {
                        publishRxStats(sync, null, null)
                    } else {
                        val snr = synchronized(nativeRxLock) { nativeBridge.getRxSnrNative() }
                        val freqOffset = synchronized(nativeRxLock) {
                            nativeBridge.getRxFreqOffsetNative()
                        }
                        publishRxStats(sync, snr, freqOffset)
                    }

                    pushWaterfallLine(captureSpectrumLine())
                }

                if (ret < 0) {
                    out160.fill(0)
                }
                val rxCallsign = synchronized(nativeRxLock) { nativeBridge.pollRxCallsign() }
                if (!rxCallsign.isNullOrBlank()) {
                    addLog("EOO callsign decoded: $rxCallsign")
                    val snrForReport = synchronized(nativeRxLock) { nativeBridge.getRxSnrNative() }
                    reporterClient.emitRxReport(rxCallsign, snrForReport, "RADEV1")
                }

                try {
                    player.write(out160, 0, out160.size)
                } catch (_: Exception) {
                    break
                }
            }
        }

        mode = SessionMode.RX
        _uiState.update {
            it.copy(
                mode = SessionMode.RX,
                syncStatus = "SEARCHING",
                pttColorArgb = 0xFF9E9E9E.toInt()
            )
        }
        addLog("RX standby")
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

        val rc = nativeBridge.startTxMic()
        if (rc != 0) {
            recorder.release()
            player.release()
            return false
        }

        txRecorder = recorder
        txPlayer = player
        val playbackQueue = LinkedBlockingQueue<ShortArray>(256)
        txPlaybackQueue = playbackQueue

        try {
            recorder.startRecording()
            player.pause()
            player.flush()
            player.play()
        } catch (_: Exception) {
            nativeBridge.stopTx()
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
            txRecorder = null
            txPlayer = null
            return false
        }

        txPlaybackThread = thread(name = "tx-playback-thread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var txHasStarted = false

            while (running && pttDown && token == switchToken) {
                if (txPlayer !== player) break

                val chunk = try {
                    playbackQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    player.write(chunk, 0, chunk.size)
                    val hasBaseband = chunk.any { it.toInt() != 0 }
                    if (!txHasStarted && hasBaseband) {
                        txHasStarted = true
                        startMeterUiTicker()
                        reporterClient.emitTxReport("RADEV1", true)
                        _uiState.update {
                            it.copy(
                                mode = SessionMode.TX,
                                syncStatus = "TRANSMITTING",
                                pttColorArgb = 0xFF2E7D32.toInt()
                            )
                        }
                        addLog("TX")
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        txThread = thread(name = "tx-thread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val in160 = ShortArray(SPEECH_FRAME)
            val out80 = ShortArray(BASEBAND_FRAME)

            while (running && pttDown && token == switchToken) {
                if (txReleaseInProgress) break
                if (txRecorder !== recorder || txPlayer !== player) break

                try {
                    val n = recorder.read(in160, 0, in160.size)
                    if (n != in160.size) continue

                    val agcEnabled = _uiState.value.agcEnabled
                    val inputDb = calculateFrameDb(in160)
                    applyTxAgcInPlace(in160)
                    val meterDb = if (agcEnabled) calculateFrameDb(in160) else inputDb
                    val alpha = if (meterDb > smoothedMicDb) 0.35 else 0.55
                    smoothedMicDb = alpha * smoothedMicDb + (1.0 - alpha) * meterDb

                    val dbClamped = smoothedMicDb.coerceIn(-50.0, 0.0)
                    val level = ((dbClamped + 50.0) * 2.0).toInt()
                    val color = when {
                        smoothedMicDb >= -10.0 -> 0xFFE53935.toInt()
                        smoothedMicDb >= -15.0 -> 0xFFFFB300.toInt()
                        else -> 0xFF4CAF50.toInt()
                    }
                    targetMicMeter = MeterUiState(
                        title = "MIC LEVEL",
                        valueText = "${smoothedMicDb.toInt()} dB",
                        percent = level,
                        colorArgb = color
                    )

                    val ret = nativeBridge.processTxMicFrame(in160, out80)
                    if (ret < 0) continue

                    if (!playbackQueue.offer(out80.copyOf())) {
                        playbackQueue.poll()
                        playbackQueue.offer(out80.copyOf())
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        mode = SessionMode.TX_PREPARING
        return true
    }

    private fun stopRxInternal() {
        val thread = rxThread
        val recorder = rxRecorder
        val player = rxPlayer

        rxThread = null
        rxRecorder = null
        rxPlayer = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            thread?.join(600)
        } catch (_: Exception) {
        }

        synchronized(nativeRxLock) {
            try {
                nativeBridge.stopRx()
            } catch (_: Exception) {
            }
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
    }

    private fun stopTxInternal() {
        val thread = txThread
        val playbackThread = txPlaybackThread
        val recorder = txRecorder
        val player = txPlayer

        txThread = null
        txPlaybackThread = null
        txRecorder = null
        txPlayer = null
        txPlaybackQueue = null

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            nativeBridge.stopTx()
        } catch (_: Exception) {
        }

        try {
            thread?.join(600)
        } catch (_: Exception) {
        }
        try {
            playbackThread?.join(600)
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
    }
}
