package com.example.localptt.audio

/**
 * 编解码器统一接口。
 *
 * [sampleRate] / [frameSamples] 由实现给出，[AudioEngine] 据此配置 AudioRecord/AudioTrack，
 * 从而同时适配 AMR-NB（8kHz / 160 样本 / 20ms）与 Opus（16kHz / 320 样本 / 20ms）。
 *
 * 约定：encode/decode 的 PCM 为 16bit 有符号、小端序、单声道 ShortArray。
 */
interface AudioCodec {
    /** 编解码采样率（Hz）。 */
    val sampleRate: Int

    /** 每帧样本数（固定 20ms 帧长，即 sampleRate/50）。 */
    val frameSamples: Int

    /** 编码一帧 PCM -> 压缩字节（AMR 每帧自带 1 字节 ToC 头，见 RFC 3267）。 */
    fun encode(pcm: ShortArray, pcmLen: Int): ByteArray

    /** 解码压缩字节（可含多帧）-> PCM ShortArray。 */
    fun decode(data: ByteArray, len: Int): ShortArray

    fun release()
}
