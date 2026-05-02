#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <string>
#include <vector>

extern "C" {
#include "rade/rade_api.h"
#include "dnn/lpcnet.h"
#include "dnn/fargan.h"
#include "eoo/eoo_callsign_codec_c.h"
}

static short clamp_pcm16(float x) {
    if (x > 32767.0f) x = 32767.0f;
    if (x < -32768.0f) x = -32768.0f;
    return (short)lrintf(x);
}

static float pcm16_to_float(short x) {
    return ((float)x) / 32768.0f;
}

static constexpr float TX_SCALING = 16383.0f;

/* =========================
 * TX state
 * 16 kHz speech -> 8 kHz real baseband
 * ========================= */

static struct rade* g_tx_r = nullptr;
static LPCNetEncState* g_tx_enc = nullptr;
static int g_tx_started = 0;

static int g_tx_n_features = 0;
static int g_tx_n_tx = 0;
static int g_tx_n_eoo = 0;
static int g_tx_block_idx = 0;

static float* g_tx_features_accum = nullptr;
static RADE_COMP* g_tx_buf = nullptr;

static short g_tx_out_queue[80 * 64];
static int g_tx_out_queue_len = 0;
static std::string g_tx_callsign;

static void tx_queue_reset() {
    g_tx_out_queue_len = 0;
}

static void tx_queue_push80(const short* x80) {
    if (g_tx_out_queue_len + 80 > (int)(sizeof(g_tx_out_queue) / sizeof(g_tx_out_queue[0]))) return;
    memcpy(g_tx_out_queue + g_tx_out_queue_len, x80, sizeof(short) * 80);
    g_tx_out_queue_len += 80;
}

static int tx_queue_pop80(short* out80) {
    if (g_tx_out_queue_len < 80) {
        memset(out80, 0, sizeof(short) * 80);
        return 0;
    }
    memcpy(out80, g_tx_out_queue, sizeof(short) * 80);
    memmove(g_tx_out_queue, g_tx_out_queue + 80, sizeof(short) * (g_tx_out_queue_len - 80));
    g_tx_out_queue_len -= 80;
    return 1;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_startTxMic(
        JNIEnv*,
        jobject)
{
    if (g_tx_started) return 0;

    rade_initialize();

    g_tx_r = rade_open(nullptr, RADE_USE_C_ENCODER);
    g_tx_enc = lpcnet_encoder_create();

    if (!g_tx_r || !g_tx_enc) {
        if (g_tx_enc) {
            lpcnet_encoder_destroy(g_tx_enc);
            g_tx_enc = nullptr;
        }
        if (g_tx_r) {
            rade_close(g_tx_r);
            g_tx_r = nullptr;
        }
        rade_finalize();
        return -1;
    }

    g_tx_n_features = rade_n_features_in_out(g_tx_r);
    g_tx_n_tx = rade_n_tx_out(g_tx_r);
    g_tx_n_eoo = rade_n_eoo_bits(g_tx_r);

    g_tx_features_accum = (float*)malloc(sizeof(float) * g_tx_n_features);
    g_tx_buf = (RADE_COMP*)malloc(sizeof(RADE_COMP) * g_tx_n_tx);

    if (!g_tx_features_accum || !g_tx_buf) {
        if (g_tx_features_accum) free(g_tx_features_accum);
        if (g_tx_buf) free(g_tx_buf);
        g_tx_features_accum = nullptr;
        g_tx_buf = nullptr;

        lpcnet_encoder_destroy(g_tx_enc);
        g_tx_enc = nullptr;

        rade_close(g_tx_r);
        g_tx_r = nullptr;

        rade_finalize();
        return -2;
    }

    memset(g_tx_features_accum, 0, sizeof(float) * g_tx_n_features);
    g_tx_block_idx = 0;
    tx_queue_reset();
    g_tx_started = 1;
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_setTxCallsign(
        JNIEnv* env,
        jobject,
        jstring callsign)
{
    if (callsign == nullptr) {
        g_tx_callsign.clear();
        return;
    }
    const char* cs = env->GetStringUTFChars(callsign, nullptr);
    g_tx_callsign = cs ? cs : "";
    env->ReleaseStringUTFChars(callsign, cs);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_appendTxEoo(
        JNIEnv*,
        jobject)
{
    if (!g_tx_started || g_tx_r == nullptr || g_tx_callsign.empty() || g_tx_n_eoo <= 0) {
        return 0;
    }

    std::vector<float> eooBits(g_tx_n_eoo, 0.0f);
    eoo_callsign_encode(g_tx_callsign.c_str(), eooBits.data(), g_tx_n_eoo);
    rade_tx_set_eoo_bits(g_tx_r, eooBits.data());

    const int nEooOut = rade_n_tx_eoo_out(g_tx_r);
    if (nEooOut <= 0) return 0;

    std::vector<RADE_COMP> eooOut(nEooOut);
    const int produced = rade_tx_eoo(g_tx_r, eooOut.data());
    if (produced <= 0) return 0;

    short block80[80] = {0};
    int idx = 0;
    for (int i = 0; i < produced; ++i) {
        block80[idx++] = clamp_pcm16(TX_SCALING * eooOut[i].real);
        if (idx == 80) {
            tx_queue_push80(block80);
            idx = 0;
        }
    }
    if (idx > 0) {
        for (int i = idx; i < 80; ++i) block80[i] = 0;
        tx_queue_push80(block80);
    }
    return produced;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_processTxMicFrame(
        JNIEnv* env,
        jobject,
        jshortArray inputSpeech160,
        jshortArray outputBaseband80)
{
    if (!g_tx_started) return -1;
    if (env->GetArrayLength(inputSpeech160) < 160 || env->GetArrayLength(outputBaseband80) < 80) return -2;

    jshort* in = env->GetShortArrayElements(inputSpeech160, nullptr);
    jshort* out = env->GetShortArrayElements(outputBaseband80, nullptr);

    float feat36[36];

    if (lpcnet_compute_single_frame_features(g_tx_enc, (const opus_int16*)in, feat36, 0) != 0) {
        memset(out, 0, sizeof(jshort) * 80);
        env->ReleaseShortArrayElements(inputSpeech160, in, JNI_ABORT);
        env->ReleaseShortArrayElements(outputBaseband80, out, 0);
        return -3;
    }

    memcpy(g_tx_features_accum + g_tx_block_idx * 36, feat36, sizeof(float) * 36);
    g_tx_block_idx++;

    if (g_tx_block_idx == 12) {
        int nout = rade_tx(g_tx_r, g_tx_buf, g_tx_features_accum);

        if (nout == 960) {
            for (int blk = 0; blk < 12; ++blk) {
                short bb80[80];
                for (int i = 0; i < 80; ++i) {
                    float s = TX_SCALING * g_tx_buf[blk * 80 + i].real;
                    bb80[i] = clamp_pcm16(s);
                }
                tx_queue_push80(bb80);
            }
        }

        g_tx_block_idx = 0;
    }

    tx_queue_pop80((short*)out);

    env->ReleaseShortArrayElements(inputSpeech160, in, JNI_ABORT);
    env->ReleaseShortArrayElements(outputBaseband80, out, 0);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_stopTx(
        JNIEnv*,
        jobject)
{
    if (!g_tx_started) return;

    if (g_tx_features_accum) {
        free(g_tx_features_accum);
        g_tx_features_accum = nullptr;
    }

    if (g_tx_buf) {
        free(g_tx_buf);
        g_tx_buf = nullptr;
    }

    if (g_tx_enc) {
        lpcnet_encoder_destroy(g_tx_enc);
        g_tx_enc = nullptr;
    }

    if (g_tx_r) {
        rade_close(g_tx_r);
        g_tx_r = nullptr;
    }

    rade_finalize();

    g_tx_started = 0;
    g_tx_n_features = 0;
    g_tx_n_tx = 0;
    g_tx_n_eoo = 0;
    g_tx_block_idx = 0;
    tx_queue_reset();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_drainTxQueuedFrame(
        JNIEnv* env,
        jobject,
        jshortArray outputBaseband80)
{
    if (!g_tx_started) return -1;
    if (env->GetArrayLength(outputBaseband80) < 80) return -2;

    jshort* out = env->GetShortArrayElements(outputBaseband80, nullptr);
    const int had = tx_queue_pop80((short*)out);
    env->ReleaseShortArrayElements(outputBaseband80, out, 0);
    return had;
}

/* =========================
 * RX state
 * 8 kHz real baseband -> 16 kHz decoded speech
 * ========================= */

static struct rade* g_rx_r = nullptr;
static int g_rx_started = 0;
static int g_rx_fargan_ready = 0;
static int g_rx_warm_count = 0;

static int g_rx_nin = 0;
static int g_rx_nfeatures = 0;
static int g_rx_n_eoo = 0;

static RADE_COMP* g_rx_in_buf = nullptr;
static float* g_rx_features = nullptr;
static float g_rx_manual_offset_hz = 0.0f;
static float g_rx_shift_phase = 0.0f;
static float g_rx_shift_phase_inc = 0.0f;
static std::string g_rx_last_callsign;

static short g_rx_in_queue[80 * 64];
static int g_rx_in_queue_len = 0;

static short g_rx_out_queue[160 * 128];
static int g_rx_out_queue_len = 0;

static FARGANState g_fargan;
static float g_warmup[5 * NB_TOTAL_FEATURES];

static void rx_in_reset() {
    g_rx_in_queue_len = 0;
}

static void rx_out_reset() {
    g_rx_out_queue_len = 0;
}

static void rx_in_push80(const short* x80) {
    if (g_rx_in_queue_len + 80 > (int)(sizeof(g_rx_in_queue) / sizeof(g_rx_in_queue[0]))) return;
    memcpy(g_rx_in_queue + g_rx_in_queue_len, x80, sizeof(short) * 80);
    g_rx_in_queue_len += 80;
}

static int rx_in_pop960(short* out960) {
    if (g_rx_in_queue_len < 960) return 0;
    memcpy(out960, g_rx_in_queue, sizeof(short) * 960);
    memmove(g_rx_in_queue, g_rx_in_queue + 960, sizeof(short) * (g_rx_in_queue_len - 960));
    g_rx_in_queue_len -= 960;
    return 1;
}

static void rx_out_push160(const short* x160) {
    if (g_rx_out_queue_len + 160 > (int)(sizeof(g_rx_out_queue) / sizeof(g_rx_out_queue[0]))) return;
    memcpy(g_rx_out_queue + g_rx_out_queue_len, x160, sizeof(short) * 160);
    g_rx_out_queue_len += 160;
}

static void rx_out_pop160(short* out160) {
    if (g_rx_out_queue_len < 160) {
        memset(out160, 0, sizeof(short) * 160);
        return;
    }
    memcpy(out160, g_rx_out_queue, sizeof(short) * 160);
    memmove(g_rx_out_queue, g_rx_out_queue + 160, sizeof(short) * (g_rx_out_queue_len - 160));
    g_rx_out_queue_len -= 160;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_startRxAudio(
        JNIEnv*,
        jobject)
{
    if (g_rx_started) return 0;

    rade_initialize();

    g_rx_r = rade_open(nullptr, RADE_USE_C_DECODER);
    if (!g_rx_r) {
        rade_finalize();
        return -1;
    }

    g_rx_nin = rade_nin(g_rx_r);
    g_rx_nfeatures = rade_n_features_in_out(g_rx_r);
    g_rx_n_eoo = rade_n_eoo_bits(g_rx_r);

    g_rx_in_buf = (RADE_COMP*)malloc(sizeof(RADE_COMP) * g_rx_nin);
    g_rx_features = (float*)malloc(sizeof(float) * g_rx_nfeatures);

    if (!g_rx_in_buf || !g_rx_features) {
        if (g_rx_in_buf) free(g_rx_in_buf);
        if (g_rx_features) free(g_rx_features);
        g_rx_in_buf = nullptr;
        g_rx_features = nullptr;
        rade_close(g_rx_r);
        g_rx_r = nullptr;
        rade_finalize();
        return -2;
    }

    fargan_init(&g_fargan);
    memset(g_warmup, 0, sizeof(g_warmup));
    g_rx_shift_phase = 0.0f;
    g_rx_shift_phase_inc = 2.0f * (float)M_PI * g_rx_manual_offset_hz / 8000.0f;

    g_rx_fargan_ready = 0;
    g_rx_warm_count = 0;
    rx_in_reset();
    rx_out_reset();
    g_rx_last_callsign.clear();

    g_rx_started = 1;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_processRxBasebandFrame(
        JNIEnv* env,
        jobject,
        jshortArray inputBaseband80,
        jshortArray outputSpeech160)
{
    if (!g_rx_started) return -1;
    if (env->GetArrayLength(inputBaseband80) < 80 || env->GetArrayLength(outputSpeech160) < 160) return -2;

    jshort* in = env->GetShortArrayElements(inputBaseband80, nullptr);
    jshort* out = env->GetShortArrayElements(outputSpeech160, nullptr);

    rx_in_push80((short*)in);

    short bb960[960];
    if (rx_in_pop960(bb960)) {
        for (int i = 0; i < 960; ++i) {
            float x = pcm16_to_float(bb960[i]);
            float phase = g_rx_shift_phase + g_rx_shift_phase_inc * i;
            g_rx_in_buf[i].real = x * cosf(phase);
            g_rx_in_buf[i].imag = x * sinf(phase);
        }
        g_rx_shift_phase += g_rx_shift_phase_inc * 960.0f;
        while (g_rx_shift_phase > (float)M_PI) g_rx_shift_phase -= 2.0f * (float)M_PI;
        while (g_rx_shift_phase < -(float)M_PI) g_rx_shift_phase += 2.0f * (float)M_PI;

        int has_eoo_out = 0;
        std::vector<float> eoo_out(g_rx_n_eoo > 0 ? g_rx_n_eoo : 64, 0.0f);
        int nf = rade_rx(g_rx_r, g_rx_features, &has_eoo_out, eoo_out.data(), g_rx_in_buf);
        if (has_eoo_out && g_rx_n_eoo > 0) {
            char cs[32] = {0};
            if (eoo_callsign_decode(eoo_out.data(), g_rx_n_eoo / 2, cs, (int)sizeof(cs) - 1)) {
                g_rx_last_callsign = cs;
            }
        }

        if (nf == g_rx_nfeatures) {
            for (int block = 0; block < 12; ++block) {
                float* f36 = g_rx_features + block * 36;

                if (!g_rx_fargan_ready) {
                    memcpy(g_warmup + g_rx_warm_count * NB_TOTAL_FEATURES,
                           f36,
                           sizeof(float) * NB_TOTAL_FEATURES);
                    g_rx_warm_count++;

                    if (g_rx_warm_count == 5) {
                        float zeros[320] = {0};
                        fargan_cont(&g_fargan, zeros, g_warmup);
                        g_rx_fargan_ready = 1;
                    }

                    short z[160] = {0};
                    rx_out_push160(z);
                    continue;
                }

                float features20[NB_FEATURES];
                float fpcm[LPCNET_FRAME_SIZE];
                short pcm160[LPCNET_FRAME_SIZE];

                memcpy(features20, f36, sizeof(float) * NB_FEATURES);
                fargan_synthesize(&g_fargan, fpcm, features20);

                for (int i = 0; i < LPCNET_FRAME_SIZE; ++i) {
                    pcm160[i] = clamp_pcm16(32768.0f * fpcm[i]);
                }

                rx_out_push160(pcm160);
            }
        }
    }

    rx_out_pop160((short*)out);

    env->ReleaseShortArrayElements(inputBaseband80, in, JNI_ABORT);
    env->ReleaseShortArrayElements(outputSpeech160, out, 0);
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_stopRx(
        JNIEnv*,
        jobject)
{
    if (!g_rx_started) return;

    if (g_rx_in_buf) {
        free(g_rx_in_buf);
        g_rx_in_buf = nullptr;
    }

    if (g_rx_features) {
        free(g_rx_features);
        g_rx_features = nullptr;
    }

    if (g_rx_r) {
        rade_close(g_rx_r);
        g_rx_r = nullptr;
    }

    rade_finalize();

    g_rx_started = 0;
    g_rx_fargan_ready = 0;
    g_rx_warm_count = 0;
    g_rx_nin = 0;
    g_rx_nfeatures = 0;
    g_rx_n_eoo = 0;
    rx_in_reset();
    rx_out_reset();
    g_rx_last_callsign.clear();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_pollRxCallsign(
        JNIEnv* env,
        jobject)
{
    if (g_rx_last_callsign.empty()) return nullptr;
    std::string out = g_rx_last_callsign;
    g_rx_last_callsign.clear();
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_resetRxNative(
        JNIEnv*,
        jobject)
{
    if (g_rx_r != nullptr) {
        rade_reset_rx(g_rx_r);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_getRxSyncNative(
        JNIEnv*,
        jobject)
{
    if (g_rx_r == nullptr) return 0;
    return rade_sync(g_rx_r);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_getRxSnrNative(
        JNIEnv*,
        jobject)
{
    if (g_rx_r == nullptr) return 0;
    return rade_snrdB_3k_est(g_rx_r);
}


extern "C"
JNIEXPORT jfloat JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_getRxFreqOffsetNative(
        JNIEnv*,
        jobject)
{
    if (g_rx_r == nullptr) return 0.0f;
    return rade_freq_offset(g_rx_r);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_byf3332_radexcvr_NativeRadioBridge_setRxManualOffsetNative(
        JNIEnv*,
        jobject,
        jfloat offsetHz)
{
    g_rx_manual_offset_hz = offsetHz;
    g_rx_shift_phase_inc = 2.0f * (float)M_PI * g_rx_manual_offset_hz / 8000.0f;
}
