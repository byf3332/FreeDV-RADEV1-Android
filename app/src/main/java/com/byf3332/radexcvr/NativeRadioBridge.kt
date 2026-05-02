package com.byf3332.radexcvr

object NativeRadioBridge {
    init {
        System.loadLibrary("radexcvr")
    }

    external fun startTxMic(): Int
    external fun processTxMicFrame(
        inputSpeech160: ShortArray,
        outputBaseband80: ShortArray
    ): Int
    external fun stopTx()
    external fun setTxCallsign(callsign: String)
    external fun appendTxEoo(): Int
    external fun drainTxQueuedFrame(outputBaseband80: ShortArray): Int

    external fun startRxAudio(): Int
    external fun processRxBasebandFrame(
        inputBaseband80: ShortArray,
        outputSpeech160: ShortArray
    ): Int
    external fun stopRx()

    external fun resetRxNative()
    external fun getRxSyncNative(): Int
    external fun getRxSnrNative(): Int
    external fun getRxFreqOffsetNative(): Float
    external fun pollRxCallsign(): String?
    external fun setRxManualOffsetNative(offsetHz: Float)
}
