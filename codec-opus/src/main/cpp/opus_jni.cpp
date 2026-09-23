#include <jni.h>

#include <opus.h>

#include <cstdint>
#include <limits>
#include <vector>

namespace {

constexpr int kFrameDurationMs = 20;
constexpr int kMaxPacketBytes = 4000;

void throwException(JNIEnv* env, const char* className, const char* message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

bool validAudioConfig(jint sampleRate, jint channels) {
    return (sampleRate == 8000 || sampleRate == 12000 || sampleRate == 16000 ||
            sampleRate == 24000 || sampleRate == 48000) &&
           (channels == 1 || channels == 2);
}

int frameSamples(jint sampleRate) {
    return sampleRate * kFrameDurationMs / 1000;
}

template <typename T>
T* handleToPointer(jlong handle) {
    return reinterpret_cast<T*>(static_cast<uintptr_t>(handle));
}

template <typename T>
jlong pointerToHandle(T* pointer) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(pointer));
}

bool requireFrame(JNIEnv* env, jshortArray pcm, int expectedSamples, jint channels) {
    if (pcm == nullptr) {
        throwException(env, "java/lang/NullPointerException", "pcm must not be null");
        return false;
    }
    const jsize expectedLength = expectedSamples * channels;
    if (env->GetArrayLength(pcm) != expectedLength) {
        throwException(env, "java/lang/IllegalArgumentException", "pcm must contain exactly one 20 ms frame");
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ridechat_codec_OpusEncoder_nativeCreate(
    JNIEnv* env,
    jobject,
    jint sampleRate,
    jint channels,
    jint bitrate) {
    if (!validAudioConfig(sampleRate, channels)) {
        throwException(env, "java/lang/IllegalArgumentException", "unsupported Opus audio configuration");
        return 0;
    }
    if (bitrate < 6000 || bitrate > 512000) {
        throwException(env, "java/lang/IllegalArgumentException", "bitrate must be between 6000 and 512000 bps");
        return 0;
    }

    int error = OPUS_OK;
    ::OpusEncoder* encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (encoder == nullptr || error != OPUS_OK) {
        throwException(env, "java/lang/IllegalStateException", "could not create Opus encoder");
        return 0;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        throwException(env, "java/lang/IllegalStateException", "could not configure Opus encoder");
        return 0;
    }
    return pointerToHandle(encoder);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ridechat_codec_OpusEncoder_nativeEncode(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray pcm,
    jint sampleRate,
    jint channels) {
    ::OpusEncoder* encoder = handleToPointer<::OpusEncoder>(handle);
    if (encoder == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "Opus encoder is closed");
        return nullptr;
    }
    const int samples = frameSamples(sampleRate);
    if (!requireFrame(env, pcm, samples, channels)) {
        return nullptr;
    }

    jshort* pcmElements = env->GetShortArrayElements(pcm, nullptr);
    if (pcmElements == nullptr) {
        return nullptr;
    }
    unsigned char packet[kMaxPacketBytes];
    const int encodedBytes = opus_encode(encoder, pcmElements, samples, packet, kMaxPacketBytes);
    env->ReleaseShortArrayElements(pcm, pcmElements, JNI_ABORT);

    if (encodedBytes < 0) {
        throwException(env, "java/lang/IllegalStateException", "Opus encoding failed");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(encodedBytes);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, encodedBytes, reinterpret_cast<const jbyte*>(packet));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ridechat_codec_OpusEncoder_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    ::OpusEncoder* encoder = handleToPointer<::OpusEncoder>(handle);
    if (encoder != nullptr) {
        opus_encoder_destroy(encoder);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ridechat_codec_OpusDecoder_nativeCreate(
    JNIEnv* env,
    jobject,
    jint sampleRate,
    jint channels) {
    if (!validAudioConfig(sampleRate, channels)) {
        throwException(env, "java/lang/IllegalArgumentException", "unsupported Opus audio configuration");
        return 0;
    }

    int error = OPUS_OK;
    ::OpusDecoder* decoder = opus_decoder_create(sampleRate, channels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        throwException(env, "java/lang/IllegalStateException", "could not create Opus decoder");
        return 0;
    }
    return pointerToHandle(decoder);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_ridechat_codec_OpusDecoder_nativeDecode(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray encoded,
    jint sampleRate,
    jint channels) {
    ::OpusDecoder* decoder = handleToPointer<::OpusDecoder>(handle);
    if (decoder == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "Opus decoder is closed");
        return nullptr;
    }

    jbyte* encodedElements = nullptr;
    jsize encodedLength = 0;
    if (encoded != nullptr) {
        encodedLength = env->GetArrayLength(encoded);
        if (encodedLength > kMaxPacketBytes) {
            throwException(env, "java/lang/IllegalArgumentException", "encoded packet is too large");
            return nullptr;
        }
        encodedElements = env->GetByteArrayElements(encoded, nullptr);
        if (encodedElements == nullptr) {
            return nullptr;
        }
    }

    const int maxSamples = frameSamples(sampleRate);
    if (encodedLength > 0) {
        const int packetSamples = opus_packet_get_nb_samples(
            reinterpret_cast<const unsigned char*>(encodedElements),
            encodedLength,
            sampleRate
        );
        if (packetSamples != maxSamples) {
            env->ReleaseByteArrayElements(encoded, encodedElements, JNI_ABORT);
            throwException(env, "java/lang/IllegalArgumentException", "Opus packet must contain exactly one 20 ms frame");
            return nullptr;
        }
    }

    std::vector<jshort> decodedPcm(static_cast<size_t>(maxSamples) * channels);
    const int decodedSamples = opus_decode(
        decoder,
        reinterpret_cast<const unsigned char*>(encodedElements),
        encodedLength,
        decodedPcm.data(),
        maxSamples,
        0
    );
    if (encodedElements != nullptr) {
        env->ReleaseByteArrayElements(encoded, encodedElements, JNI_ABORT);
    }

    if (decodedSamples < 0) {
        throwException(env, "java/lang/IllegalArgumentException", "Opus decoding failed");
        return nullptr;
    }
    if (decodedSamples != maxSamples) {
        throwException(env, "java/lang/IllegalArgumentException", "decoded Opus frame is not exactly 20 ms");
        return nullptr;
    }
    const int decodedLength = decodedSamples * channels;
    if (decodedLength > std::numeric_limits<jsize>::max()) {
        throwException(env, "java/lang/IllegalStateException", "decoded frame is too large");
        return nullptr;
    }
    jshortArray result = env->NewShortArray(decodedLength);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetShortArrayRegion(result, 0, decodedLength, decodedPcm.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ridechat_codec_OpusDecoder_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    ::OpusDecoder* decoder = handleToPointer<::OpusDecoder>(handle);
    if (decoder != nullptr) {
        opus_decoder_destroy(decoder);
    }
}
