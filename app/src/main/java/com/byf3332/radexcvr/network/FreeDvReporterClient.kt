package com.byf3332.radexcvr.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FreeDvReporterClient(private val scope: CoroutineScope) {
    companion object {
        private const val FIXED_REPORT_MODE = "RADEV1"
    }
    data class Config(
        val enabled: Boolean = false,
        val callsign: String = "",
        val gridSquare: String = "",
        val version: String = "RADEXCVR/1.2.0-lab",
        val rxOnly: Boolean = true,
        val host: String = "qso.freedv.org"
    )

    data class Station(
        val sid: String,
        val lastUpdate: String,
        val callsign: String,
        val gridSquare: String,
        val version: String,
        val rxOnly: Boolean,
        val frequencyHz: Long,
        val mode: String,
        val transmitting: Boolean,
        val message: String,
        val lastTx: String,
        val connectTime: String,
        val lastRxCallsign: String,
        val snr: String,
        val lastRxEpochMs: Long,
        val lastRxHasValidCallsign: Boolean
    )

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _stations = MutableStateFlow<Map<String, Station>>(emptyMap())
    val stations: StateFlow<Map<String, Station>> = _stations.asStateFlow()

    private var config = Config()
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pingIntervalMs: Long = 25000

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    fun updateConfig(cfg: Config) {
        config = cfg
        if (!cfg.enabled) {
            disconnect()
            return
        }
        if (ws == null) connect()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        ws?.close(1000, "disabled")
        ws = null
        _connected.value = false
        _stations.value = emptyMap()
    }

    fun emitFreqChange(freqHz: Long) {
        if (!_connected.value) return
        val obj = JSONObject().put("freq", freqHz)
        sendEvent("freq_change", obj)
    }

    fun emitTxReport(mode: String, transmitting: Boolean) {
        if (!_connected.value) return
        val obj = JSONObject().put("mode", FIXED_REPORT_MODE).put("transmitting", transmitting)
        sendEvent("tx_report", obj)
    }

    fun emitRxReport(rxCallsign: String, snr: Int, mode: String) {
        if (!_connected.value) return
        val obj = JSONObject()
            .put("callsign", rxCallsign)
            .put("snr", snr)
            .put("mode", FIXED_REPORT_MODE)
        sendEvent("rx_report", obj)
    }

    fun emitMessageUpdate(message: String) {
        if (!_connected.value) return
        sendEvent("message_update", JSONObject().put("message", message))
    }

    private fun connect() {
        if (ws != null) return
        try {
            val req = Request.Builder()
                .url("wss://${config.host}/socket.io/?EIO=4&transport=websocket")
                .build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {}
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleEngineIo(text)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ws = null
                    _connected.value = false
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ws = null
                    _connected.value = false
                    scheduleReconnect()
                }
            })
        } catch (_: Throwable) {
            ws = null
            _connected.value = false
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            if (config.enabled && ws == null) connect()
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pingIntervalMs)
                ws?.send("2")
            }
        }
    }

    private fun handleEngineIo(raw: String) {
        if (raw.isEmpty()) return
        when (raw[0]) {
            '0' -> {
                val obj = JSONObject(raw.substring(1))
                pingIntervalMs = obj.optLong("pingInterval", 25000)
                val auth = JSONObject().apply {
                    put("protocol_version", 2)
                    if (config.callsign.isBlank() || config.gridSquare.isBlank()) {
                        put("role", "view")
                    } else {
                        put("role", "report")
                        put("callsign", config.callsign)
                        put("grid_square", config.gridSquare)
                        put("version", config.version)
                        put("rx_only", config.rxOnly)
                        put("os", "Android")
                    }
                }
                ws?.send("40$auth")
                startPingLoop()
            }
            '1' -> disconnect()
            '2' -> ws?.send("3")
            '4' -> handleSocketIo(raw.substring(1))
        }
    }

    private fun handleSocketIo(data: String) {
        if (data.isEmpty()) return
        when (data[0]) {
            '0' -> _connected.value = true
            '2' -> {
                val arr = JSONArray(data.substring(1))
                val event = arr.getString(0)
                val payload = if (arr.length() > 1) arr.get(1) else null
                onEvent(event, payload)
            }
            '4' -> disconnect()
        }
    }

    private fun onEvent(event: String, payload: Any?) {
        when (event) {
            "bulk_update" -> {
                val updates = payload as? JSONArray ?: return
                val current = _stations.value.toMutableMap()
                for (i in 0 until updates.length()) {
                    val evt = updates.optJSONArray(i) ?: continue
                    if (evt.length() < 2) continue
                    val name = evt.optString(0, "")
                    val obj = evt.optJSONObject(1) ?: continue
                    applySingleEvent(current, name, obj)
                }
                _stations.value = current
            }
            "new_connection", "remove_connection", "tx_report", "rx_report", "freq_change", "message_update" -> {
                val obj = payload as? JSONObject ?: return
                val current = _stations.value.toMutableMap()
                applySingleEvent(current, event, obj)
                _stations.value = current
            }
            "connection_successful" -> {
                _connected.value = true
            }
        }
    }

    private fun applySingleEvent(map: MutableMap<String, Station>, event: String, obj: JSONObject) {
        val sid = obj.optString("sid")
        if (sid.isBlank()) return
        if (event == "remove_connection") {
            map.remove(sid)
            return
        }
        val old = map[sid]
        when (event) {
            "new_connection" -> {
                map[sid] = Station(
                    sid = sid,
                    lastUpdate = obj.optString("last_update", old?.lastUpdate ?: ""),
                    callsign = obj.optString("callsign", old?.callsign ?: ""),
                    gridSquare = obj.optString("grid_square", old?.gridSquare ?: ""),
                    version = obj.optString("version", old?.version ?: ""),
                    rxOnly = obj.optBoolean("rx_only", old?.rxOnly ?: false),
                    frequencyHz = old?.frequencyHz ?: 0L,
                    mode = old?.mode ?: "",
                    transmitting = old?.transmitting ?: false,
                    message = old?.message ?: "",
                    lastTx = old?.lastTx ?: "",
                    connectTime = obj.optString("connect_time", old?.connectTime ?: ""),
                    lastRxCallsign = old?.lastRxCallsign ?: "",
                    snr = old?.snr ?: "",
                    lastRxEpochMs = old?.lastRxEpochMs ?: 0L,
                    lastRxHasValidCallsign = old?.lastRxHasValidCallsign ?: false
                )
            }
            "freq_change" -> {
                if (old == null) return
                map[sid] = old.copy(
                    lastUpdate = obj.optString("last_update", old.lastUpdate),
                    frequencyHz = obj.optLong("freq", old.frequencyHz)
                )
            }
            "tx_report" -> {
                if (old == null) return
                map[sid] = old.copy(
                    lastUpdate = obj.optString("last_update", old.lastUpdate),
                    mode = obj.optString("mode", old.mode),
                    transmitting = obj.optBoolean("transmitting", old.transmitting),
                    lastTx = obj.optString("last_tx", old.lastTx)
                )
            }
            "rx_report" -> {
                if (old == null) return
                val snrText = when {
                    obj.has("snr") -> obj.opt("snr")?.toString() ?: old.snr
                    else -> old.snr
                }
                map[sid] = old.copy(
                    lastUpdate = obj.optString("last_update", old.lastUpdate),
                    lastRxCallsign = obj.optString("callsign", old.lastRxCallsign),
                    snr = snrText,
                    lastRxEpochMs = System.currentTimeMillis(),
                    lastRxHasValidCallsign = obj.optString("callsign", old.lastRxCallsign).trim().isNotEmpty()
                )
            }
            "message_update" -> {
                if (old == null) return
                map[sid] = old.copy(
                    lastUpdate = obj.optString("last_update", old.lastUpdate),
                    message = obj.optString("message", old.message)
                )
            }
            else -> {
                if (old == null) return
                map[sid] = old.copy(lastUpdate = obj.optString("last_update", old.lastUpdate))
            }
        }
    }

    private fun sendEvent(name: String, data: JSONObject) {
        val payload = JSONArray().put(name).put(data)
        ws?.send("42$payload")
    }
}
