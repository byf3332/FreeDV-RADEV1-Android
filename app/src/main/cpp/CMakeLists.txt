cmake_minimum_required(VERSION 3.22.1)

project(radexcvr)

# ---------- RADE core ----------

add_library(rade STATIC

        rade/rade_api_nopy.c
        rade/rade_enc.c
        rade/rade_dec.c
        rade/rade_dsp.c
        rade/rade_ofdm.c
        rade/rade_bpf.c
        rade/rade_acq.c
        rade/rade_tx.c
        rade/rade_rx.c
        rade/rade_enc_data.c
        rade/rade_dec_data.c

        dnn/nnet.c
        dnn/nnet_default.c
        dnn/parse_lpcnet_weights.c
        dnn/burg.c
        dnn/freq.c
        dnn/fargan.c
        dnn/fargan_data.c
        dnn/nnet.c
        dnn/lpcnet_enc.c
        dnn/lpcnet_plc.c
        dnn/lpcnet_tables.c
        dnn/plc_data.c
        dnn/pitchdnn.c
        dnn/pitchdnn_data.c
        dnn/osce.c
        dnn/osce_features.c
        dnn/nndsp.c
        dnn/lace_data.c
        dnn/nolace_data.c
        dnn/bbwenet_data.c
        dnn/arm/arm_dnn_map.c

        celt/kiss_fft.c
        celt/mathops.c
        celt/celt_lpc.c
        celt/pitch.c
        celt/celt.c
        celt/bands.c
        celt/quant_bands.c
        celt/rate.c
        celt/mdct.c
        celt/entcode.c
        celt/entenc.c
        celt/entdec.c
        celt/laplace.c
        celt/cwrs.c
        celt/vq.c
)

# ---------- JNI ----------

add_library(radexcvr SHARED
        native-lib.cpp
)

target_include_directories(rade PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/celt
        ${CMAKE_CURRENT_SOURCE_DIR}/opus
        ${CMAKE_CURRENT_SOURCE_DIR}/dnn
        ${CMAKE_CURRENT_SOURCE_DIR}/dnn/arm
        ${CMAKE_CURRENT_SOURCE_DIR}/silk
        ${CMAKE_CURRENT_SOURCE_DIR}/rade
)

target_include_directories(radexcvr PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/celt
        ${CMAKE_CURRENT_SOURCE_DIR}/opus
        ${CMAKE_CURRENT_SOURCE_DIR}/dnn
        ${CMAKE_CURRENT_SOURCE_DIR}/dnn/arm
        ${CMAKE_CURRENT_SOURCE_DIR}/silk
        ${CMAKE_CURRENT_SOURCE_DIR}/rade
)

target_compile_definitions(rade PRIVATE
        OPUS_BUILD
        VAR_ARRAYS
)

find_library(log-lib log)

target_link_libraries(radexcvr
        rade
        ${log-lib}
        m
)