package com.byf3332.radexcvr

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class UsbSerialController(
    private val usbManager: UsbManager
) {

    data class SerialSession(
        val driver: UsbSerialDriver,
        val port: UsbSerialPort,
        val deviceKey: String,
        val portIndex: Int,
        val baudRate: Int,
        val rts: Boolean,
        val dtr: Boolean
    )

    private var session: SerialSession? = null
    @Volatile private var readThread: Thread? = null
    @Volatile private var readLoopRunning = false
    @Volatile private var onBytesReceived: ((ByteArray) -> Unit)? = null

    fun isOpen(): Boolean = session != null

    fun currentSession(): SerialSession? = session

    fun setOnBytesReceivedListener(listener: ((ByteArray) -> Unit)?) {
        onBytesReceived = listener
    }

    fun open(
        driver: UsbSerialDriver,
        portIndex: Int,
        baudRate: Int,
        rts: Boolean,
        dtr: Boolean
    ): Result<SerialSession> {
        close()
        return runCatching {
            val connection = usbManager.openDevice(driver.device)
                ?: error("USB device open failed")
            val port = driver.ports.getOrNull(portIndex)
                ?: error("Serial port index $portIndex is not available")

            try {
                port.open(connection)
                port.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                port.setRTS(rts)
                port.setDTR(dtr)
            } catch (t: Throwable) {
                runCatching { port.close() }
                runCatching { connection.close() }
                throw t
            }

            SerialSession(
                driver = driver,
                port = port,
                deviceKey = driver.device.deviceName,
                portIndex = portIndex,
                baudRate = baudRate,
                rts = rts,
                dtr = dtr
            ).also {
                session = it
                startReadLoop(it)
            }
        }
    }

    fun close() {
        stopReadLoop()
        val current = session ?: return
        session = null
        runCatching { current.port.close() }
    }

    fun setRts(enabled: Boolean): Result<Unit> {
        val current = session ?: return Result.failure(IllegalStateException("Serial port not open"))
        return runCatching {
            current.port.setRTS(enabled)
            session = current.copy(rts = enabled)
        }
    }

    fun setDtr(enabled: Boolean): Result<Unit> {
        val current = session ?: return Result.failure(IllegalStateException("Serial port not open"))
        return runCatching {
            current.port.setDTR(enabled)
            session = current.copy(dtr = enabled)
        }
    }

    fun writeAscii(text: String): Result<Int> {
        val current = session ?: return Result.failure(IllegalStateException("Serial port not open"))
        return runCatching {
            val bytes = text.toByteArray(StandardCharsets.US_ASCII)
            current.port.write(bytes, 500)
            bytes.size
        }
    }

    fun writeBytes(bytes: ByteArray): Result<Int> {
        val current = session ?: return Result.failure(IllegalStateException("Serial port not open"))
        return runCatching {
            current.port.write(bytes, 500)
            bytes.size
        }
    }

    private fun startReadLoop(current: SerialSession) {
        stopReadLoop()
        readLoopRunning = true
        readThread = thread(name = "usb-serial-read") {
            val buffer = ByteArray(512)
            while (readLoopRunning && session === current) {
                try {
                    val count = current.port.read(buffer, 200)
                    if (count > 0) {
                        onBytesReceived?.invoke(buffer.copyOf(count))
                    }
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    private fun stopReadLoop() {
        readLoopRunning = false
        val thread = readThread
        readThread = null
        if (thread != null && thread.isAlive) {
            runCatching { thread.join(300) }
        }
    }
}
