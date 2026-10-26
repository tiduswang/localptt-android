cmake_minimum_required(VERSION 3.22.1)
project(localptt_opus)

# ============================================================
# libopus 构建（OpusCodec.kt 的 JNI 后端）
#
# 使用方式：
#   1. 下载 libopus 源码并解压到 app/src/main/cpp/opus/
#      （或执行 cpp/fetch_opus.sh 自动拉取）
#   2. 在 app/build.gradle.kts 的 android{} 中加入：
#        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
#      并安装 NDK（sdkmanager "ndk;26.1.10909125"）与 CMake。
#   3. 取消 OpusCodec.kt 中 System.loadLibrary("localptt_opus") 的注释，
#      并把 AudioEngine(useOpus = true)。
#
# 未集成 NDK 时无需本文件参与构建（gradle 未开启 externalNativeBuild），
# 语音编解码由 AmrCodec（MediaCodec）兜底，功能等价。
# ============================================================

set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opus)

if (EXISTS ${OPUS_DIR}/CMakeLists.txt)
    # 用 libopus 自带的 CMake 构建静态库
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_FUZZING OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_CMAKE_CONFIG OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_PKG_CONFIG OFF CACHE BOOL "" FORCE)
    set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)
    add_subdirectory(${OPUS_DIR} opus EXCLUDE_FROM_ALL)

    add_library(localptt_opus SHARED opus_wrapper.c)
    target_include_directories(localptt_opus PRIVATE
        ${OPUS_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR})
    find_library(log log)
    target_link_libraries(localptt_opus opus ${log})
else()
    message(WARNING
        "未找到 cpp/opus 源码，跳过 localptt_opus 构建；语音将走 AmrCodec 兜底。"
        "如需 Opus，请先执行 cpp/fetch_opus.sh")
endif()
