#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>

namespace {
constexpr uint32_t kCapacity = 1u << 20; // 43.7 s @ 24 kHz; power of two.
constexpr uint32_t kMask = kCapacity - 1;
constexpr uint32_t kPrimeSamples = 5760; // 240 ms of Gemini's 24 kHz PCM.
class OutputEngine : public oboe::AudioStreamDataCallback {
public:
    std::atomic<uint32_t> read{0}, write{0};
    std::atomic<uint32_t> fifoUnderruns{0};
    std::atomic<uint32_t> fifoDroppedSamples{0};
    std::atomic<bool> primed{false};
    int16_t fifo[kCapacity]{};
    std::shared_ptr<oboe::AudioStream> stream;
    bool pop(int16_t &sample) {
        uint32_t r = read.load(std::memory_order_relaxed);
        uint32_t w = write.load(std::memory_order_acquire);
        if (r == w) return false;
        sample = fifo[r & kMask];
        read.store(r + 1, std::memory_order_release);
        return true;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void *data, int32_t frames) override {
        auto *out = static_cast<int16_t*>(data);
        if (!primed.load(std::memory_order_acquire)) {
            uint32_t buffered = write.load(std::memory_order_acquire) - read.load(std::memory_order_relaxed);
            if (buffered < kPrimeSamples) {
                std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t));
                return oboe::DataCallbackResult::Continue;
            }
            primed.store(true, std::memory_order_release);
        }
        // Oboe's FilterAudioStream invokes this callback at the source rate
        // (Gemini PCM16 mono, 24 kHz) and performs the device-rate conversion.
        for (int32_t i = 0; i < frames; ++i) {
            if (!pop(out[i])) {
                fifoUnderruns.fetch_add(1, std::memory_order_relaxed);
                primed.store(false, std::memory_order_release);
                std::memset(out + i, 0, static_cast<size_t>(frames - i) * sizeof(int16_t));
                break;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }
    bool open(oboe::SharingMode sharingMode) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
         ->setSharingMode(sharingMode)->setUsage(oboe::Usage::VoiceCommunication)
         ->setFormat(oboe::AudioFormat::I16)->setChannelCount(oboe::ChannelCount::Mono)
         ->setSampleRate(24000)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
         ->setFormatConversionAllowed(true)->setChannelConversionAllowed(true)->setDataCallback(this);
        return b.openStream(stream) == oboe::Result::OK && stream;
    }
    bool start() {
        stop(); read.store(0); write.store(0); fifoUnderruns.store(0); fifoDroppedSamples.store(0); primed.store(false);
        // Exclusive provides the fastest path. Some devices or active routes
        // deny it, so retry Shared rather than failing the voice assistant.
        if (!open(oboe::SharingMode::Exclusive)) { stop(); if (!open(oboe::SharingMode::Shared)) { stop(); return false; } }
        int32_t burst = stream->getFramesPerBurst();
        if (burst > 0) stream->setBufferSizeInFrames(burst * 2);
        if (stream->requestStart() != oboe::Result::OK) { stop(); return false; }
        return true;
    }
    void stop() { if (stream) { stream->requestStop(); stream->close(); stream.reset(); } }
    int xRuns() const {
        if (!stream || !stream->isXRunCountSupported()) return -1;
        auto result = stream->getXRunCount();
        return result ? result.value() : -1;
    }
    bool exclusive() const { return stream && stream->getSharingMode() == oboe::SharingMode::Exclusive; }
    int underruns() const { return static_cast<int>(fifoUnderruns.load(std::memory_order_relaxed)); }
    int droppedSamples() const { return static_cast<int>(fifoDroppedSamples.load(std::memory_order_relaxed)); }
    int bufferedMilliseconds() const {
        return static_cast<int>((write.load(std::memory_order_acquire) - read.load(std::memory_order_acquire)) * 1000u / 24000u);
    }
    void push(const int16_t *in, uint32_t count) {
        uint32_t r = read.load(std::memory_order_acquire), w = write.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) {
            if (w - r >= kCapacity - 1) {
                // Preserve already queued speech. Skipping it creates audible
                // word jumps; a rare overlong reply may instead lose its tail.
                fifoDroppedSamples.fetch_add(count - i, std::memory_order_relaxed);
                break;
            }
            fifo[(w++) & kMask] = in[i];
        }
        write.store(w, std::memory_order_release);
    }
} engine;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_crewpocket_helper_NativeOboeOutput_nativeStart(JNIEnv*, jclass) { return engine.start() ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL Java_com_crewpocket_helper_NativeOboeOutput_nativeStop(JNIEnv*, jclass) { engine.stop(); }
extern "C" JNIEXPORT void JNICALL Java_com_crewpocket_helper_NativeOboeOutput_nativeFlush(JNIEnv*, jclass) { uint32_t w = engine.write.load(); engine.read.store(w); engine.primed.store(false); }
extern "C" JNIEXPORT void JNICALL Java_com_crewpocket_helper_NativeOboeOutput_nativeWrite(JNIEnv *env, jclass, jbyteArray pcm, jint length) {
    if (!pcm || length < 2) return; jsize n = env->GetArrayLength(pcm); length = length > n ? n : length;
    jbyte *p = env->GetByteArrayElements(pcm, nullptr); if (!p) return;
    engine.push(reinterpret_cast<int16_t*>(p), static_cast<uint32_t>(length / 2)); env->ReleaseByteArrayElements(pcm, p, JNI_ABORT);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_crewpocket_helper_NativeOboeOutput_nativeGetInfo(JNIEnv *env, jclass) {
    char info[144]; std::snprintf(info, sizeof(info), "Oboe SRC 24k · %s · xRun %d · buf %dms · drop %d", engine.exclusive() ? "Exclusive" : "Shared", engine.xRuns(), engine.bufferedMilliseconds(), engine.droppedSamples());
    return env->NewStringUTF(info);
}
