package com.example.localptt.audio

/**
 * Opus 封装（JNI）。so 由 CMake + libopus 源码编译得到（见 cpp/CMakeLists.txt）。
 * 默认参数：16kHz 单声道，20ms 帧(320 sample)，码率 24kbps，VOIP 模式。
 *
 * 生产环境强烈推荐使用 libopus（NDK 编译）：低延迟(默认 20ms)、抗丢包、
 * 窄带/宽带自适应，是对讲语音的最佳选择。
 * 本机未安装 NDK 时默认不启用（见 [AudioEngine] useOpus=false），
 * 由 [AmrCodec]（MediaCodec 软件编解码）兜底，功能等价、全平台可用。
 */
class OpusCodec(sampleRate: Int = 16000, channels: Int = 1) : AudioCodec {
    // 集成 NDK 后取消下行注释即可加载原生库：
    // companion object { init { System.loadLibrary("localptt_opus") } }
    companion object

    private val rate: Int = sampleRate
    private val nativePtr: Long = createNative(sampleRate, channels)

    override val sampleRate: Int get() = rate
    override val frameSamples: Int get() = rate / 50      // 20ms

    private external fun createNative(sampleRate: Int, channels: Int): Long
    private external fun encodeNative(ptr: Long, pcm: ShortArray, pcmLen: Int): ByteArray
    private external fun decodeNative(ptr: Long, data: ByteArray, len: Int): ShortArray
    private external fun destroyNative(ptr: Long)

    override fun encode(pcm: ShortArray, pcmLen: Int) = encodeNative(nativePtr, pcm, pcmLen)
    override fun decode(data: ByteArray, len: Int) = decodeNative(nativePtr, data, len)
    override fun release() = destroyNative(nativePtr)
}
