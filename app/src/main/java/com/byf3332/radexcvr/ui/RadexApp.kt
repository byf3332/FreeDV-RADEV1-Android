package com.byf3332.radexcvr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.defaultMinSize
import com.byf3332.radexcvr.AppPage
import com.byf3332.radexcvr.AppUiState
import com.byf3332.radexcvr.AudioDeviceOption
import com.byf3332.radexcvr.MeterUiState
import com.byf3332.radexcvr.RadioSessionController
import com.byf3332.radexcvr.RigProfileOption
import com.byf3332.radexcvr.RigControlMode
import com.byf3332.radexcvr.SessionMode
import com.byf3332.radexcvr.UsbSerialDeviceOption
import com.byf3332.radexcvr.ui.theme.RADEXCVRTheme
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import android.view.MotionEvent

@Composable
fun RadexApp(
    controller: RadioSessionController,
    onRequestStart: () -> Unit
) {
    val state by controller.uiState.collectAsState()
    val meterState by controller.meterState.collectAsState()
    val reporterState by controller.reporterUiState.collectAsState()

    RADEXCVRTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TabRow(selectedTabIndex = state.currentPage.ordinal) {
                        AppPage.entries.forEach { page ->
                            Tab(
                                selected = state.currentPage == page,
                                onClick = { controller.selectPage(page) },
                                text = { Text(pageTitle(page)) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (state.currentPage) {
                    AppPage.HOME -> HomePage(
                        state = state,
                        meter = meterState,
                        onRequestStart = onRequestStart,
                        onStop = controller::stopSession,
                        onResync = controller::resyncRx,
                        onOffsetDown = { controller.adjustManualRxOffset(-5) },
                        onOffsetUp = { controller.adjustManualRxOffset(5) },
                        onPttPressed = controller::onPttPressed,
                        onPttReleased = controller::onPttReleased,
                        modifier = Modifier.padding(padding)
                    )

                    AppPage.SETTINGS -> SettingsPage(
                        state = state,
                        onRefresh = controller::refreshDevices,
                        onRefreshSerialDevices = controller::findAndAuthorizeUsbSerialDevices,
                        onTxInputSelected = controller::onTxInputSelected,
                        onTxOutputSelected = controller::onTxOutputSelected,
                        onRxInputSelected = controller::onRxInputSelected,
                        onRxOutputSelected = controller::onRxOutputSelected,
                        onControlModeSelected = controller::onRigControlModeSelected,
                        onRigProfileSelected = controller::onRigProfileSelected,
                        onUsbSerialDeviceSelected = controller::onUsbSerialDeviceSelected,
                        onSerialBaudRateSelected = controller::onSerialBaudRateSelected,
                        onTestPtt = controller::pulseRigPttTest,
                        onRigFrequencyTextChanged = controller::onRigFrequencyTextChanged,
                        onSendRigFrequency = controller::sendRigFrequency,
                        onAgcEnabledChanged = controller::onAgcEnabledChanged,
                        onAgcClipDbChanged = controller::onAgcClipDbChanged,
                        onAgcStrengthChanged = controller::onAgcStrengthChanged,
                        modifier = Modifier.padding(padding)
                    )

                    AppPage.REPORTER -> ReporterPage(
                        state = state,
                        reporterState = reporterState,
                        onReporterEnabledChanged = controller::onReporterEnabledChanged,
                        onReporterCallsignChanged = controller::onReporterCallsignChanged,
                        onReporterGridChanged = controller::onReporterGridChanged,
                        onReporterMessageChanged = controller::onReporterMessageChanged,
                        onReporterManualFrequencyChanged = controller::onReporterManualFrequencyChanged,
                        onReporterFrequencyPresetSelected = controller::onReporterFrequencyPresetSelected,
                        onReporterSendMessage = controller::onReporterSendMessage,
                        onReporterRxOnlyChanged = controller::onReporterRxOnlyChanged,
                        modifier = Modifier.padding(padding)
                    )

                    AppPage.LOGS -> LogsPage(
                        logs = state.logs,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

private fun pageTitle(page: AppPage): String = when (page) {
    AppPage.HOME -> "Home"
    AppPage.SETTINGS -> "Settings"
    AppPage.REPORTER -> "Reporter"
    AppPage.LOGS -> "Logs"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(
    state: AppUiState,
    meter: MeterUiState,
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
    onResync: () -> Unit,
    onOffsetDown: () -> Unit,
    onOffsetUp: () -> Unit,
    onPttPressed: () -> Unit,
    onPttReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pttTouchActive by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(
                state = rememberScrollState(),
                enabled = !pttTouchActive
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.sessionRunning) {
            Button(
                onClick = onStop,
                enabled = state.stopEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("STOP MODEM")
            }
        } else {
            OutlinedButton(
                onClick = onRequestStart,
                enabled = state.startEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("START MODEM")
            }
        }

        OutlinedButton(
            onClick = onResync,
            enabled = state.resyncEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("RESYNC RX")
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFDFEFF)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val statusHeadline = state.eooRxCallsignDisplay.ifBlank { state.syncStatus }
                val statusColor = if (state.eooRxCallsignDisplay.isNotBlank()) {
                    Color(0xFF246B3D)
                } else {
                    syncColor(state.syncStatus)
                }
                Text(
                    text = statusHeadline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = statusColor
                )

                MeterBlock(
                    title = meter.title,
                    valueText = meter.valueText,
                    percent = meter.percent,
                    color = Color(meter.colorArgb)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ParamChip(
                        label = "Mode",
                        width = 88.dp,
                        value = when (state.mode) {
                            SessionMode.IDLE -> "IDLE"
                            SessionMode.RX -> "RX"
                            SessionMode.TX_PREPARING -> "TX PREP"
                            SessionMode.TX -> "TX"
                        }
                    )
                    ParamChip(
                        label = "Freq Offset",
                        width = 108.dp,
                        value = state.rxFreqOffsetHz?.let { "%+.1f Hz".format(it) } ?: "NaN"
                    )
                    ParamChip(
                        label = "RX SNR",
                        width = 88.dp,
                        value = state.rxSnrDb?.let { "$it dB" } ?: "NaN"
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFDFEFF)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "RX Waterfall",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF12202F)
                )
                WaterfallView(
                    waterfall = state.waterfall,
                    manualOffsetHz = state.manualRxOffsetHz.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color(0xFFC4D4E2))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RepeatAdjustButton(
                        label = "-5 Hz",
                        onStep = onOffsetDown,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Offset ${state.manualRxOffsetHz} Hz",
                        color = Color(0xFF12202F),
                        style = MaterialTheme.typography.titleSmall
                    )
                    RepeatAdjustButton(
                        label = "+5 Hz",
                        onStep = onOffsetUp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

                PttButton(
                    enabled = state.pttEnabled,
                    color = Color(state.pttColorArgb),
                    onPressed = {
                        pttTouchActive = true
                        onPttPressed()
                    },
                    onReleased = {
                        pttTouchActive = false
                        onPttReleased()
                    }
                )
            }
        }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPage(
    state: AppUiState,
    onRefresh: () -> Unit,
    onRefreshSerialDevices: () -> Unit,
    onTxInputSelected: (Int) -> Unit,
    onTxOutputSelected: (Int) -> Unit,
    onRxInputSelected: (Int) -> Unit,
    onRxOutputSelected: (Int) -> Unit,
    onControlModeSelected: (RigControlMode) -> Unit,
    onRigProfileSelected: (String) -> Unit,
    onUsbSerialDeviceSelected: (String) -> Unit,
    onSerialBaudRateSelected: (Int) -> Unit,
    onTestPtt: () -> Unit,
    onRigFrequencyTextChanged: (String) -> Unit,
    onSendRigFrequency: () -> Unit,
    onAgcEnabledChanged: (Boolean) -> Unit,
    onAgcClipDbChanged: (Float) -> Unit,
    onAgcStrengthChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var rigPresetExpanded by rememberSaveable { mutableStateOf(false) }
    val rigFreqPresets = remember {
        listOf(
            "1.8700", "3.6250", "3.6430", "3.6930", "3.6970", "3.8030",
            "5.4035", "5.3665", "5.3685", "7.1770", "7.1970", "14.2360",
            "14.2400", "18.1180", "21.3130", "24.9330", "28.3300", "28.7200",
            "10,489.6400"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFEFF))) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Audio",
                    color = Color(0xFF12202F),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    "Audio device selection and processing",
                    color = Color(0xFF5C7388),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Audio Devices",
                        color = Color(0xFF12202F),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = state.refreshEnabled,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 96.dp, minHeight = 40.dp)
                    ) {
                        Text(
                            "REFRESH",
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }

                DeviceSelector(
                    title = "TX Input",
                    options = state.inputDevices,
                    selectedId = state.selectedDevices.txInputId,
                    enabled = state.selectorsEnabled,
                    onSelected = onTxInputSelected
                )

                DeviceSelector(
                    title = "TX Output",
                    options = state.outputDevices,
                    selectedId = state.selectedDevices.txOutputId,
                    enabled = state.selectorsEnabled,
                    onSelected = onTxOutputSelected
                )

                DeviceSelector(
                    title = "RX Input",
                    options = state.inputDevices,
                    selectedId = state.selectedDevices.rxInputId,
                    enabled = state.selectorsEnabled,
                    onSelected = onRxInputSelected
                )

                DeviceSelector(
                    title = "RX Output",
                    options = state.outputDevices,
                    selectedId = state.selectedDevices.rxOutputId,
                    enabled = state.selectorsEnabled,
                    onSelected = onRxOutputSelected
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "TX Audio AGC",
                        color = Color(0xFF12202F),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = state.agcEnabled,
                        onCheckedChange = onAgcEnabledChanged
                    )
                }

                Text(
                    text = "Clip ${state.agcClipDb.toInt()} dBFS",
                    color = Color(0xFF12202F),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = state.agcClipDb,
                    onValueChange = onAgcClipDbChanged,
                    valueRange = -18f..0f,
                    enabled = state.agcEnabled
                )

                Text(
                    text = "Gain Strength ${(state.agcStrength * 100).toInt()}%",
                    color = Color(0xFF12202F),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = state.agcStrength,
                    onValueChange = onAgcStrengthChanged,
                    valueRange = 0f..1.5f,
                    enabled = state.agcEnabled
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFEFF))) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Radio Control",
                            color = Color(0xFF12202F),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "USB serial device control and CAT/PTT routing",
                            color = Color(0xFF5C7388),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRefreshSerialDevices,
                    enabled = state.serialDevicesRefreshEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("FIND DEVICES")
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RigControlMode.entries.forEach { mode ->
                        ModeChip(
                            text = mode.name,
                            selected = state.rigControlMode == mode,
                            onClick = { onControlModeSelected(mode) }
                        )
                    }
                }

                if (state.rigControlMode != RigControlMode.VOX) {
                    if (state.rigControlMode == RigControlMode.CAT) {
                        Text(
                            text = "Rig Profile",
                            color = Color(0xFF12202F),
                            style = MaterialTheme.typography.titleMedium
                        )
                        RigProfileSelector(
                            selectedLabel = state.selectedRigProfileLabel,
                            options = state.rigProfiles,
                            onSelected = onRigProfileSelected
                        )
                        Text(
                            text = state.rigStatusText,
                            color = if (state.rigConnected) Color(0xFF246B3D) else Color(0xFF5C7388),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "CI-V / address: ${state.rigCivAddress.ifBlank { "N/A" }}",
                            color = Color(0xFF5C7388),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { rigPresetExpanded = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Preset")
                            }
                            DropdownMenu(
                                expanded = rigPresetExpanded,
                                onDismissRequest = { rigPresetExpanded = false },
                                modifier = Modifier.heightIn(max = 320.dp)
                            ) {
                                rigFreqPresets.forEach { freq ->
                                    DropdownMenuItem(
                                        text = { Text(freq, color = Color(0xFF12202F)) },
                                        onClick = {
                                            rigPresetExpanded = false
                                            onRigFrequencyTextChanged(freq.replace(",", ""))
                                        }
                                    )
                                }
                            }
                            androidx.compose.material3.OutlinedTextField(
                                value = state.rigFrequencyText,
                                onValueChange = onRigFrequencyTextChanged,
                                modifier = Modifier.weight(1.3f),
                                label = { Text("Rig freq") },
                                singleLine = true
                            )
                        }
                        Text(
                            text = state.rigCurrentFreqHz?.let { "Rig reports %.6f MHz".format(it / 1_000_000.0) } ?: "Rig frequency not read yet",
                            color = Color(0xFF5C7388),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = onSendRigFrequency,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SET FREQ")
                        }
                    }

                    Text(
                        text = state.usbSerialDiscoveryStatus,
                        color = Color(0xFF5C7388),
                        style = MaterialTheme.typography.bodySmall
                    )

                    BaudRateSelector(
                        selectedBaudRate = state.serialBaudRate,
                        enabled = true,
                        onSelected = onSerialBaudRateSelected
                    )

                    Text(
                        text = state.serialPortStatus,
                        color = if (state.serialPortOpen) Color(0xFF246B3D) else Color(0xFF5C7388),
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedButton(
                        onClick = onTestPtt,
                        enabled = state.serialPortOpen,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("TEST PTT")
                    }

                    Text(
                        text = "Detected Serial Devices",
                        color = Color(0xFF12202F),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.usbSerialDevices.isEmpty()) {
                        Text(
                            text = "No supported USB serial devices detected.",
                            color = Color(0xFF5C7388),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        state.usbSerialDevices.forEach { device ->
                            UsbSerialCard(
                                option = device,
                                selected = state.selectedUsbSerialDeviceKey == device.key,
                                onSelect = { onUsbSerialDeviceSelected(device.key) }
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFFE3F0FB) else Color(0xFFF8FBFE),
            contentColor = Color(0xFF12202F)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Color(0xFF7AA6CC) else Color(0xFFD4E0EB)
        )
    ) {
        Text(text)
    }
}

@Composable
private fun BaudRateSelector(
    selectedBaudRate: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    val baudRates = listOf(1200, 2400, 4800, 9600, 14400, 19200, 38400, 43000, 56000, 57600, 115200)
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Baud Rate",
            color = Color(0xFF12202F),
            style = MaterialTheme.typography.titleMedium
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("$selectedBaudRate")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 132.dp, max = 168.dp)
                    .heightIn(max = 280.dp)
            ) {
                baudRates.forEach { baudRate ->
                    DropdownMenuItem(
                        text = { Text("$baudRate") },
                        onClick = {
                            expanded = false
                            onSelected(baudRate)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UsbSerialCard(
    option: UsbSerialDeviceOption,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFEAF3FB) else Color(0xFFF8FBFE)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = option.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
                color = Color(0xFF12202F),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
            Text(
                text = option.detail,
                color = Color(0xFF5C7388),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onSelect) {
                Text(if (selected) "SELECTED" else "SELECT")
            }
        }
    }
}

@Composable
private fun LogsPage(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color(0xFFFDFEFF), shape = MaterialTheme.shapes.medium)
            .border(1.dp, Color(0xFFD1DCE8), shape = MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Session Log",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF12202F)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = if (logs.isEmpty()) "No logs yet." else logs.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1E3145)
            )
        }
    }
}

@Composable
private fun ReporterPage(
    state: AppUiState,
    reporterState: com.byf3332.radexcvr.ReporterUiState,
    onReporterEnabledChanged: (Boolean) -> Unit,
    onReporterCallsignChanged: (String) -> Unit,
    onReporterGridChanged: (String) -> Unit,
    onReporterMessageChanged: (String) -> Unit,
    onReporterManualFrequencyChanged: (String) -> Unit,
    onReporterFrequencyPresetSelected: (String) -> Unit,
    onReporterSendMessage: () -> Unit,
    onReporterRxOnlyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val lastMessageBySid = remember { mutableStateMapOf<String, String>() }
    val pinkUntilBySid = remember { mutableStateMapOf<String, Long>() }
    val reporterFreqPresets = remember {
        listOf(
            "1.8700", "3.6250", "3.6430", "3.6930", "3.6970", "3.8030",
            "5.4035", "5.3665", "5.3685", "7.1770", "7.1970", "14.2360",
            "14.2400", "18.1180", "21.3130", "24.9330", "28.3300", "28.7200",
            "10,489.6400"
        )
    }
    var presetExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(reporterState.stations) {
        val now = System.currentTimeMillis()
        reporterState.stations.forEach { station ->
            val previous = lastMessageBySid[station.sid]
            val current = station.message.trim()
            if (previous != null && current.isNotBlank() && previous != current) {
                pinkUntilBySid[station.sid] = now + 5_000L
            }
            lastMessageBySid[station.sid] = current
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFEFF))) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "FreeDV Reporter",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF12202F)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF12202F)
                    )
                    Switch(
                        checked = state.reporterEnabled,
                        onCheckedChange = onReporterEnabledChanged
                    )
                }
                Text(
                    text = if (state.reporterEnabled) {
                        if (reporterState.connected) "Connected" else "Connecting/Disconnected"
                    } else "Disabled",
                    color = if (reporterState.connected) Color(0xFF246B3D) else Color(0xFF5D7286),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = state.reporterCallsign,
                    onValueChange = onReporterCallsignChanged,
                    label = { Text("Callsign") },
                    singleLine = true,
                    enabled = state.reporterEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.reporterGridSquare,
                    onValueChange = onReporterGridChanged,
                    label = { Text("Grid Square") },
                    singleLine = true,
                    enabled = state.reporterEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.reporterMessage,
                        onValueChange = onReporterMessageChanged,
                        label = { Text("Message") },
                        singleLine = true,
                        enabled = state.reporterEnabled,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onReporterSendMessage,
                        enabled = state.reporterEnabled
                    ) {
                        Text("SEND")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RX Only",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF12202F)
                    )
                    Switch(
                        checked = state.reporterRxOnly,
                        onCheckedChange = onReporterRxOnlyChanged
                    )
                }

                if (state.rigControlMode != RigControlMode.CAT) {
                    Text(
                        text = "Reporter Frequency (MHz)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF12202F)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { presetExpanded = true },
                            enabled = state.reporterEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Preset")
                        }
                        DropdownMenu(
                            expanded = presetExpanded && state.reporterEnabled,
                            onDismissRequest = { presetExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            reporterFreqPresets.forEach { freq ->
                                DropdownMenuItem(
                                    text = { Text(freq, color = Color(0xFF12202F)) },
                                    onClick = {
                                        presetExpanded = false
                                        onReporterFrequencyPresetSelected(freq.replace(",", ""))
                                    }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.reporterManualFreqMHz,
                            onValueChange = onReporterManualFrequencyChanged,
                            label = { Text("MHz") },
                            singleLine = true,
                            enabled = state.reporterEnabled,
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFEFF))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Stations (${reporterState.stations.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF12202F)
                )
                if (reporterState.stations.isEmpty()) {
                    Text(
                        text = "No stations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5D7286)
                    )
                } else {
                    val hScroll = rememberScrollState()
                    val rowHeight = 34.dp
                    val maxVisibleRows = 20
                    val visibleRows = reporterState.stations.size.coerceAtMost(maxVisibleRows)
                    val tableBodyHeight = rowHeight * visibleRows
                    val colWCallsign = 96.dp
                    val colWLocator = 80.dp
                    val colWKm = 64.dp
                    val colWHdg = 64.dp
                    val colWVersion = 190.dp
                    val colWMhz = 92.dp
                    val colWMode = 72.dp
                    val colWStatus = 72.dp
                    val colWMsg = 220.dp
                    val colWLastTx = 128.dp
                    val colWRxCall = 92.dp
                    val colWSnr = 64.dp
                    val colWLastUpdate = 170.dp

                    Row(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .background(Color(0xFFEFF3F8))
                            .padding(vertical = 6.dp)
                    ) {
                        ReporterCell("Callsign", colWCallsign, true)
                        ReporterCell("Locator", colWLocator, true)
                        ReporterCell("km", colWKm, true)
                        ReporterCell("Hdg", colWHdg, true)
                        ReporterCell("Version", colWVersion, true)
                        ReporterCell("MHz", colWMhz, true)
                        ReporterCell("Mode", colWMode, true)
                        ReporterCell("Status", colWStatus, true)
                        ReporterCell("Msg", colWMsg, true)
                        ReporterCell("Last TX", colWLastTx, true)
                        ReporterCell("RX Call", colWRxCall, true)
                        ReporterCell("SNR", colWSnr, true)
                        ReporterCell("Last Update", colWLastUpdate, true)
                    }

                    if (reporterState.stations.size <= maxVisibleRows) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(tableBodyHeight)
                        ) {
                            reporterState.stations.forEach { row ->
                                val now = System.currentTimeMillis()
                                val isPink = (pinkUntilBySid[row.sid] ?: 0L) > now
                                val rowBackground = when {
                                    isPink -> Color(0xFFD28CD5)
                                    row.status == "TX" -> Color(0xFFFF5400)
                                    row.status == "RX" && row.rxActive -> Color(0xFF43A6BD)
                                    else -> Color(0xFFFFFFFF)
                                }

                                Row(
                                    modifier = Modifier
                                        .horizontalScroll(hScroll)
                                        .background(rowBackground)
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ReporterCell(normalizeReporterCallsign(row.callsign), colWCallsign)
                                    ReporterCell(if (row.gridSquare.isBlank()) "--" else row.gridSquare, colWLocator)
                                    ReporterCell("--", colWKm)
                                    ReporterCell("--", colWHdg)
                                    ReporterCell(if (row.version.isBlank()) "--" else row.version, colWVersion)
                                    ReporterCell(if (row.frequencyHz > 0) String.format("%.4f", row.frequencyHz / 1_000_000.0) else "0.0000", colWMhz)
                                    ReporterCell(if (row.mode.isBlank()) "--" else row.mode, colWMode)
                                    ReporterCell(row.status, colWStatus)
                                    ReporterCell(if (row.message.isBlank()) "--" else row.message, colWMsg)
                                    ReporterCell(if (row.lastTx.isBlank()) "--" else row.lastTx, colWLastTx)
                                    ReporterCell(if (row.lastRxCallsign.isBlank()) "--" else row.lastRxCallsign, colWRxCall)
                                    ReporterCell(if (row.snr.isBlank()) "--" else row.snr, colWSnr)
                                    ReporterCell(if (row.lastUpdate.isBlank()) "--" else row.lastUpdate, colWLastUpdate)
                                }
                            }
                        }
                    } else {
                        val tableVScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(tableBodyHeight)
                                .verticalScroll(tableVScroll)
                        ) {
                            reporterState.stations.forEach { row ->
                                val now = System.currentTimeMillis()
                                val isPink = (pinkUntilBySid[row.sid] ?: 0L) > now
                                val rowBackground = when {
                                    isPink -> Color(0xFFD28CD5)
                                    row.status == "TX" -> Color(0xFFFF5400)
                                    row.status == "RX" && row.rxActive -> Color(0xFF43A6BD)
                                    else -> Color(0xFFFFFFFF)
                                }

                                Row(
                                    modifier = Modifier
                                        .horizontalScroll(hScroll)
                                        .background(rowBackground)
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ReporterCell(normalizeReporterCallsign(row.callsign), colWCallsign)
                                    ReporterCell(if (row.gridSquare.isBlank()) "--" else row.gridSquare, colWLocator)
                                    ReporterCell("--", colWKm)
                                    ReporterCell("--", colWHdg)
                                    ReporterCell(if (row.version.isBlank()) "--" else row.version, colWVersion)
                                    ReporterCell(if (row.frequencyHz > 0) String.format("%.4f", row.frequencyHz / 1_000_000.0) else "0.0000", colWMhz)
                                    ReporterCell(if (row.mode.isBlank()) "--" else row.mode, colWMode)
                                    ReporterCell(row.status, colWStatus)
                                    ReporterCell(if (row.message.isBlank()) "--" else row.message, colWMsg)
                                    ReporterCell(if (row.lastTx.isBlank()) "--" else row.lastTx, colWLastTx)
                                    ReporterCell(if (row.lastRxCallsign.isBlank()) "--" else row.lastRxCallsign, colWRxCall)
                                    ReporterCell(if (row.snr.isBlank()) "--" else row.snr, colWSnr)
                                    ReporterCell(if (row.lastUpdate.isBlank()) "--" else row.lastUpdate, colWLastUpdate)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReporterCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp)
            .then(if (header) Modifier else Modifier.basicMarquee()),
        maxLines = 1,
        overflow = if (header) TextOverflow.Ellipsis else TextOverflow.Visible,
        style = if (header) {
            MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        color = Color(0xFF12202F)
    )
}

private fun normalizeReporterCallsign(raw: String): String {
    val s = raw.trim().uppercase()
    return if (s.isBlank()) "N/A" else s
}

@Composable
private fun MeterBlock(
    title: String,
    valueText: String,
    percent: Int,
    color: Color
) {
    val isMicMeter = title == "MIC LEVEL"
    val isPrepareMeter = isMicMeter && valueText == "NaN"
    val latestPercent by rememberUpdatedState(percent)
    val latestValueText by rememberUpdatedState(valueText)
    val latestColor by rememberUpdatedState(color)
    var displayedPercent by remember(title) { mutableFloatStateOf(percent.toFloat()) }
    var displayedDb by remember(title) {
        mutableFloatStateOf(valueText.removeSuffix(" dB").toFloatOrNull() ?: -50f)
    }
    var displayedColor by remember(title) { mutableStateOf(color) }

    LaunchedEffect(title, percent, valueText, color) {
        if (!isMicMeter) {
            displayedPercent = percent.toFloat()
            displayedDb = valueText.removeSuffix(" dB").toFloatOrNull() ?: displayedDb
            displayedColor = color
            return@LaunchedEffect
        }
        if (isPrepareMeter) {
            displayedPercent = 0f
            displayedColor = color
        }
    }

    LaunchedEffect(title) {
        if (!isMicMeter) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastFrameNanos == 0L) {
                    lastFrameNanos = now
                }
                val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrameNanos = now

                val targetPercent = latestPercent.toFloat()
                val targetDb = latestValueText.removeSuffix(" dB").toFloatOrNull() ?: displayedDb

                displayedPercent = if (targetPercent >= displayedPercent) {
                    targetPercent
                } else {
                    val releasePerSecond = 160f
                    (displayedPercent - releasePerSecond * dt).coerceAtLeast(targetPercent)
                }

                displayedDb = if (targetDb >= displayedDb) {
                    targetDb
                } else {
                    val releaseDbPerSecond = 42f
                    (displayedDb - releaseDbPerSecond * dt).coerceAtLeast(targetDb)
                }

                displayedColor = latestColor
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            when {
                isPrepareMeter -> "NaN"
                isMicMeter -> valueText
                else -> valueText
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(Color(0xFF404040))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        (if (isMicMeter) displayedPercent else percent.toFloat())
                            .coerceIn(0f, 100f) / 100f
                    )
                    .height(18.dp)
                    .background(if (isMicMeter) displayedColor else color)
            )
        }
    }
}

@Composable
private fun ParamChip(label: String, value: String, width: androidx.compose.ui.unit.Dp) {
    Column(
        modifier = Modifier
            .width(width)
            .background(Color(0xFFF5F9FD), shape = MaterialTheme.shapes.medium)
            .border(1.dp, Color(0xFFD4E0EB), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF5B748A),
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF12202F),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WaterfallView(
    waterfall: List<List<Float>>,
    manualOffsetHz: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color(0xFF06111A))) {
        Canvas(
            modifier = Modifier.matchParentSize()
        ) {
            val rows = waterfall.size
            val cols = waterfall.maxOfOrNull { it.size } ?: 0
            if (cols > 0) {
                val displayRows = 120
                val cellHeight = size.height / displayRows
                val startY = size.height - rows.coerceAtMost(displayRows) * cellHeight

                waterfall.takeLast(displayRows).forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { colIndex, value ->
                        val freq0 = displayFrequencyForBin(colIndex.toFloat() - 0.5f, cols, manualOffsetHz)
                        val freq1 = displayFrequencyForBin(colIndex.toFloat() + 0.5f, cols, manualOffsetHz)
                        val x0 = mapFrequencyToX(freq0, size.width)
                        val x1 = mapFrequencyToX(freq1, size.width)
                        drawRect(
                            color = waterfallColor(value),
                            topLeft = Offset(
                                x = x0,
                                y = startY + rowIndex * cellHeight
                            ),
                            size = Size((x1 - x0).coerceAtLeast(2f), cellHeight)
                        )
                    }
                }
            }

            val leftBoundaryX = mapFrequencyToX(750f, size.width)
            val rightBoundaryX = mapFrequencyToX(2250f, size.width)
            drawRect(
                color = Color(0xB3F5F8FB),
                topLeft = Offset(leftBoundaryX, 0f),
                size = Size(2f, size.height)
            )
            drawRect(
                color = Color(0xB3F5F8FB),
                topLeft = Offset(rightBoundaryX, 0f),
                size = Size(2f, size.height)
            )
        }

        Text(
            text = "750 Hz",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 6.dp),
            color = Color(0xFFF6FBFF),
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = "2250 Hz",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 8.dp, top = 6.dp),
            color = Color(0xFFF6FBFF),
            style = MaterialTheme.typography.labelMedium
        )

        if (waterfall.isEmpty()) {
            Text(
                text = "Waiting for RX samples",
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFFE7F3FC),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun RepeatAdjustButton(
    label: String,
    onStep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFFEAF2F9), shape = MaterialTheme.shapes.medium)
            .border(1.dp, Color(0xFFD0DDE9), shape = MaterialTheme.shapes.medium)
            .pointerInput(onStep) {
                detectTapGestures(
                    onPress = {
                        onStep()
                        var repeatDelay = 360L
                        while (true) {
                            val released = withTimeoutOrNull(repeatDelay) {
                                tryAwaitRelease()
                            }
                            if (released != null) break
                            onStep()
                            repeatDelay = 70L
                        }
                    }
                )
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFF12202F),
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun PttButton(
    enabled: Boolean,
    color: Color,
    onPressed: () -> Unit,
    onReleased: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    DisposableEffect(enabled) {
        if (!enabled && pressed) {
            pressed = false
            onReleased()
        }
        onDispose {
            if (pressed) {
                pressed = false
                onReleased()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(color, shape = MaterialTheme.shapes.large)
            .pointerInteropFilter { event ->
                if (!enabled) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!pressed) {
                            pressed = true
                            onPressed()
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        if (pressed) {
                            pressed = false
                            onReleased()
                        }
                        true
                    } 
                    MotionEvent.ACTION_CANCEL -> {
                        if (pressed) {
                            pressed = false
                            onReleased()
                        }
                        true
                    }
                    else -> true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PTT",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
    }
}

@Composable
private fun DeviceSelector(
    title: String,
    options: List<AudioDeviceOption>,
    selectedId: Int?,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.id == selectedId }?.name ?: "Unavailable"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 360.dp)
                    .heightIn(max = 320.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(),
                                maxLines = 1,
                                overflow = TextOverflow.Visible
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RigProfileSelector(
    selectedLabel: String,
    options: List<RigProfileOption>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 320.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.key)
                        }
                    )
                }
            }
        }
    }
}

private fun syncColor(syncStatus: String): Color = when (syncStatus) {
    "SYNCED" -> Color(0xFF246B3D)
    "TRANSMITTING" -> Color(0xFFB23A2A)
    else -> Color(0xFF5D7286)
}

private fun displayFrequencyForBin(index: Float, cols: Int, manualOffsetHz: Float): Float {
    val clamped = index.coerceIn(0f, (cols - 1).toFloat())
    val base = 0f + (3000f - 0f) * clamped / (cols - 1).coerceAtLeast(1)
    return (base + manualOffsetHz).coerceIn(0f, 3000f)
}

private fun mapFrequencyToX(freqHz: Float, width: Float): Float {
    val normalized = (freqHz / 3000f).coerceIn(0f, 1f)
    return width * normalized
}

private fun waterfallColor(value: Float): Color {
    val clamped = value.coerceIn(0f, 1f)
    return when {
        clamped < 0.2f -> lerpColor(Color(0xFF071018), Color(0xFF0D3B66), clamped / 0.2f)
        clamped < 0.45f -> lerpColor(Color(0xFF0D3B66), Color(0xFF00A8CC), (clamped - 0.2f) / 0.25f)
        clamped < 0.7f -> lerpColor(Color(0xFF00A8CC), Color(0xFFFFC857), (clamped - 0.45f) / 0.25f)
        else -> lerpColor(Color(0xFFFFC857), Color(0xFFE9724C), (clamped - 0.7f) / 0.3f)
    }
}

private fun lerpColor(start: Color, end: Color, t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * x,
        green = start.green + (end.green - start.green) * x,
        blue = start.blue + (end.blue - start.blue) * x,
        alpha = 1f
    )
}


