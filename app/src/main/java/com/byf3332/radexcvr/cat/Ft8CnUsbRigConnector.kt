package com.byf3332.radexcvr.cat

import com.bg7yoz.ft8cn.connector.BaseRigConnector
import com.bg7yoz.ft8cn.database.ControlMode

class Ft8CnUsbRigConnector(
    controlMode: Int,
    private val transportOpen: () -> Boolean,
    private val writeBytes: (ByteArray) -> Result<Int>,
    private val setRts: (Boolean) -> Result<Unit>,
    private val setDtr: (Boolean) -> Result<Unit>,
    private val onLog: (String) -> Unit
) : BaseRigConnector(controlMode) {

    override fun sendData(data: ByteArray) {
        if (!transportOpen()) {
            getOnConnectorStateChanged().onRunError("Serial port not open")
            return
        }
        writeBytes(data)
            .onFailure { error ->
                getOnConnectorStateChanged().onRunError(error.message ?: "Serial write failed")
            }
    }

    override fun setPttOn(on: Boolean) {
        if (!transportOpen()) {
            getOnConnectorStateChanged().onRunError("Serial port not open")
            return
        }
        when (getControlMode()) {
            ControlMode.RTS -> {
                setRts(on).onFailure { error ->
                    getOnConnectorStateChanged().onRunError(error.message ?: "RTS control failed")
                }
            }

            ControlMode.DTR -> {
                setDtr(on).onFailure { error ->
                    getOnConnectorStateChanged().onRunError(error.message ?: "DTR control failed")
                }
            }

            else -> {
                onLog("FT8CN PTT line control ignored in mode ${getControlMode()}")
            }
        }
    }

    override fun setPttOn(command: ByteArray) {
        sendData(command)
    }

    override fun connect() {
        if (transportOpen()) {
            getOnConnectorStateChanged().onConnected()
        } else {
            getOnConnectorStateChanged().onRunError("Serial port not open")
        }
    }

    override fun disconnect() {
        getOnConnectorStateChanged().onDisconnected()
    }
}
