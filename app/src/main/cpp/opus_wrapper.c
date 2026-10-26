// Opus JNI 封装（供 OpusCodec.kt 调用）。
// 编译：把 libopus 源码放入同目录，CMake 链接。
#include <jni.h>
#include <opus/opus.h>
#include <string.h>

typedef struct { OpusEncoder* enc; OpusDecoder* dec; } Ctx;

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_localptt_audio_OpusCodec_createNative(JNIEnv*, jobject, jint rate, jint ch) {
    Ctx* c = (Ctx*)malloc(sizeof(Ctx));
    int err;
    c->enc = opus_encoder_create(rate, ch, OPUS_APPLICATION_VOIP, &err);
    c->dec = opus_decoder_create(rate, ch, &err);
    opus_encoder_ctl(c->enc, OPUS_SET_BITRATE(24000));
    return (jlong)c;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_localptt_audio_OpusCodec_encodeNative(JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jint len) {
    Ctx* c = (Ctx*)ptr;
    jshort* in = env->GetShortArrayElements(pcm, 0);
    unsigned char out[1024];
    int n = opus_encode(c->enc, in, len, out, sizeof(out));
    env->ReleaseShortArrayElements(pcm, in, 0);
    jbyteArray res = env->NewByteArray(n);
    env->SetByteArrayRegion(res, 0, n, (jbyte*)out);
    return res;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_example_localptt_audio_OpusCodec_decodeNative(JNIEnv* env, jobject, jlong ptr, jbyteArray data, jint len) {
    Ctx* c = (Ctx*)ptr;
    jbyte* in = env->GetByteArrayElements(data, 0);
    jshort out[2048];
    int n = opus_decode(c->dec, (unsigned char*)in, len, out, 1024, 0);
    env->ReleaseByteArrayElements(data, in, 0);
    jshortArray res = env->NewShortArray(n);
    env->SetShortArrayRegion(res, 0, n, out);
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_localptt_audio_OpusCodec_destroyNative(JNIEnv*, jobject, jlong ptr) {
    Ctx* c = (Ctx*)ptr;
    opus_encoder_destroy(c->enc); opus_decoder_destroy(c->dec); free(c);
}
