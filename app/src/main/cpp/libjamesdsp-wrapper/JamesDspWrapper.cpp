#include <android/log.h>
#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdint>
#include <mutex>

#define TAG "JamesDspWrapper_JNI"
#include <Log.h>

#include <string>
#include <vector>
#include <jni.h>

#include "JamesDspWrapper.h"
#include "JArrayList.h"
#include "EelVmVariable.h"
#include "fieldsurround/FieldSurroundProcessor.h"

extern "C" {
#include "../EELStdOutExtension.h"
#include <jdsp_header.h>
}

// C interop
inline JamesDSPLib* cast(void* raw){
    if(raw == nullptr)
    {
        LOGE("JamesDspWrapper::cast: JamesDSPLib pointer is NULL")
    }
    return static_cast<JamesDSPLib*>(raw);
}

inline JamesDspWrapper* castWrapper(jlong raw){
    if(raw == 0)
    {
        LOGE("JamesDspWrapper::castWrapper: JamesDspWrapper pointer is NULL")
    }
    return reinterpret_cast<JamesDspWrapper*>(raw);
}

inline float* getTempBuffer(JamesDspWrapper* wrapper, size_t sampleCount) {
    if (wrapper == nullptr || sampleCount == 0) {
        return nullptr;
    }
    if (wrapper->tempBuffer.size() < sampleCount) {
        wrapper->tempBuffer.resize(sampleCount);
    }
    return wrapper->tempBuffer.data();
}

inline int32_t clamp24FromFloat(float sample) {
    static constexpr float kScale = static_cast<float>(1 << 23);
    static constexpr float kLimPos = 0x7fffff / kScale;
    static constexpr float kLimNeg = -0x800000 / kScale;
    if (sample <= kLimNeg) {
        return -0x800000;
    }
    if (sample >= kLimPos) {
        return 0x7fffff;
    }
    const float scaled = sample * kScale;
    return static_cast<int32_t>(scaled > 0.0f ? scaled + 0.5f : scaled - 0.5f);
}

#define RETURN_IF_NULL(name, retval) \
    if(name == nullptr)      \
        return retval;

#define DECLARE_WRAPPER(retval) \
     if(self == 0L) \
        return retval; \
     auto* wrapper = castWrapper(self); \
     RETURN_IF_NULL(wrapper, retval)

#define DECLARE_DSP(retval) \
    DECLARE_WRAPPER(retval) \
    auto* dsp = cast(wrapper->dsp); \
    RETURN_IF_NULL(dsp, retval)

#define DECLARE_WRAPPER_V DECLARE_WRAPPER()
#define DECLARE_DSP_V DECLARE_DSP()
#define DECLARE_WRAPPER_B DECLARE_WRAPPER(false)
#define DECLARE_DSP_B DECLARE_DSP(false)

inline int32_t arySearch(int32_t *array, int32_t N, int32_t x)
{
    for (int32_t i = 0; i < N; i++)
    {
        if (array[i] == x)
            return i;
    }
    return -1;
}

#define FLOIDX 20000
/*inline void* GetStringForIndex(eel_string_context_state *st, float val, int32_t write)
{
    auto castedValue = (int32_t)(val + 0.5f);
    if (castedValue < FLOIDX)
        return nullptr;
    int32_t idx = arySearch(st->map, st->slot, castedValue);
    if (idx < 0)
        return nullptr;
    if (!write)
    {
        s_str *tmp = &st->m_literal_strings[idx];
        const char *s = s_str_c_str(tmp);
        return (void*)s;
    }
    else
        return (void*)&st->m_literal_strings[idx];
}*/

extern "C" JNIEXPORT jlong JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_alloc(JNIEnv *env, jobject obj, jobject callback)
{
    auto* self = new JamesDspWrapper();
    self->callbackInterface = env->NewGlobalRef(callback);
    self->env = env;

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr)
    {
        LOGE("JamesDspWrapper::ctor: Cannot find callback class");
        delete self;
        return 0;
    }
    else
    {
        self->callbackOnLiveprogOutput = env->GetMethodID(callbackClass, "onLiveprogOutput",
                                                      "(Ljava/lang/String;)V");
        self->callbackOnLiveprogExec = env->GetMethodID(callbackClass, "onLiveprogExec",
                                                    "(Ljava/lang/String;)V");
        self->callbackOnLiveprogResult = env->GetMethodID(callbackClass, "onLiveprogResult",
                                                          "(ILjava/lang/String;Ljava/lang/String;)V");
        self->callbackOnVdcParseError = env->GetMethodID(callbackClass, "onVdcParseError",
                                                          "()V");
        if (self->callbackOnLiveprogOutput == nullptr || self->callbackOnLiveprogExec == nullptr ||
            self->callbackOnLiveprogResult == nullptr || self->callbackOnVdcParseError == nullptr)
        {
            LOGE("JamesDspWrapper::ctor: Cannot find callback method");
            delete self;
            return 0;
        }
    }


    auto* _dsp = (JamesDSPLib*)malloc(sizeof(JamesDSPLib));
    if(!_dsp)
    {
        LOGE("JamesDspWrapper::ctor: Failed to allocate memory for libjamesdsp class object");
        delete self;
        return 1;
    }
    memset(_dsp, 0, sizeof(JamesDSPLib));

    JamesDSPGlobalMemoryAllocation();
    JamesDSPInit(_dsp, 128, 48000);

    if(!JamesDSPGetMutexStatus(_dsp))
    {
        LOGE("JamesDspWrapper::ctor: JamesDSPGetMutexStatus returned false. "
                    "Cannot run safely in multi-threaded environment.");
        JamesDSPFree(_dsp);
        JamesDSPGlobalMemoryDeallocation();
        delete self;
        return 2;
    }

    self->dsp = _dsp;
    self->fieldSurround = new fieldsurround::FieldSurroundProcessor();
    auto* fieldSurround = self->fieldSurround;
    if (fieldSurround != nullptr) {
        fieldSurround->setSamplingRate(static_cast<uint32_t>(_dsp->fs));
    }

    LOGD("JamesDspWrapper::ctor: memory allocated at %lx", (long)self);
    return (long)self;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_free(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_V

    LOGD("JamesDspWrapper::dtor: freeing memory allocated at %lx", (long)self);

    setStdOutHandler(nullptr, nullptr);

    JamesDSPFree(dsp);
    free(dsp);
    wrapper->dsp = nullptr;
    delete wrapper->fieldSurround;
    wrapper->fieldSurround = nullptr;

    JamesDSPGlobalMemoryDeallocation();

    env->DeleteGlobalRef(wrapper->callbackInterface);
    delete wrapper;

    LOGD("JamesDspWrapper::dtor: memory freed");
}

extern "C" JNIEXPORT jint JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_getBenchmarkSize(JNIEnv *env, jobject obj) {
    return MAX_BENCHMARK;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_runBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::runBenchmark: started");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Start_benchmark();
    JamesDSP_Save_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, 0);
    env->ReleaseDoubleArrayElements(jc1, c1, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_loadBenchmark(JNIEnv *env, jobject obj, jdoubleArray jc0, jdoubleArray jc1)
{
    LOGD("JamesDspWrapper::loadBenchmark: loading data");

    auto c0 = env->GetDoubleArrayElements(jc0, nullptr);
    auto c1 = env->GetDoubleArrayElements(jc1, nullptr);

    JamesDSP_Load_benchmark(c0, c1);

    env->ReleaseDoubleArrayElements(jc0, c0, JNI_ABORT);
    env->ReleaseDoubleArrayElements(jc1, c1, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSamplingRate(JNIEnv *env,
                                                                                 jobject obj,
                                                                                 jlong self,
                                                                                 jfloat sample_rate,
                                                                                 jboolean force_refresh)
{
    DECLARE_DSP_V
    JamesDSPSetSampleRate(dsp, sample_rate, force_refresh);
    auto* fieldSurround = wrapper->fieldSurround;
    if (fieldSurround != nullptr) {
        fieldSurround->setSamplingRate((uint32_t)sample_rate);
    }
}


extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_isHandleValid(JNIEnv *env, jobject obj, jlong self)
{
    DECLARE_DSP_B // This macro returns false if the DSP object can't be accessed
    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt16(JNIEnv *env, jobject obj, jlong self, jshortArray inputObj, jshortArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetShortArrayElements(inputObj, nullptr);
    auto output = env->GetShortArrayElements(outputObj, nullptr);

    auto* fieldSurround = wrapper->fieldSurround;
    const bool applyFieldSurround = fieldSurround != nullptr && fieldSurround->isEnabled();
    const uint32_t frames = static_cast<uint32_t>(inputLength / 2);

    if (applyFieldSurround) {
        std::lock_guard<std::mutex> lock(wrapper->tempBufferMutex);
        auto* temp = getTempBuffer(wrapper, static_cast<size_t>(inputLength));
        if (temp == nullptr) {
            env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
            env->ReleaseShortArrayElements(outputObj, output, 0);
            return;
        }
        for (int i = 0; i < inputLength; ++i) {
            temp[i] = static_cast<float>(input[offset + i]) / 32768.0f;
        }
        fieldSurround->process(temp, frames);
        dsp->processFloatMultiplexd(dsp, temp, temp, frames);

        constexpr float kScale = 32768.0f;
        for (int i = 0; i < inputLength; ++i) {
            const float sample = temp[i];
            if (sample <= -1.0f) {
                output[i] = static_cast<jshort>(INT16_MIN);
            } else if (sample >= 1.0f) {
                output[i] = static_cast<jshort>(INT16_MAX);
            } else {
                const float scaled = sample * kScale;
                output[i] = static_cast<jshort>(scaled > 0.0f ? scaled + 0.5f : scaled - 0.5f);
            }
        }
    } else {
        dsp->processInt16Multiplexd(dsp, input + offset, output, frames);
    }
    env->ReleaseShortArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseShortArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt32(JNIEnv *env, jobject obj, jlong self, jintArray inputObj, jintArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);

    auto* fieldSurround = wrapper->fieldSurround;
    const bool applyFieldSurround = fieldSurround != nullptr && fieldSurround->isEnabled();
    const uint32_t frames = static_cast<uint32_t>(inputLength / 2);

    if (applyFieldSurround) {
        std::lock_guard<std::mutex> lock(wrapper->tempBufferMutex);
        auto* temp = getTempBuffer(wrapper, static_cast<size_t>(inputLength));
        if (temp == nullptr) {
            env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
            env->ReleaseIntArrayElements(outputObj, output, 0);
            return;
        }
        constexpr float kInputScaleInv = 1.0f / 2147483648.0f;
        for (int i = 0; i < inputLength; ++i) {
            temp[i] = static_cast<float>(static_cast<double>(input[offset + i]) * kInputScaleInv);
        }
        fieldSurround->process(temp, frames);
        dsp->processFloatMultiplexd(dsp, temp, temp, frames);

        constexpr double kScale = 2147483648.0;
        for (int i = 0; i < inputLength; ++i) {
            const float sample = temp[i];
            if (sample <= -1.0f) {
                output[i] = INT32_MIN;
            } else if (sample >= 1.0f) {
                output[i] = INT32_MAX;
            } else {
                const double scaled = static_cast<double>(sample) * kScale;
                output[i] = static_cast<jint>(scaled > 0.0 ? scaled + 0.5 : scaled - 0.5);
            }
        }
    } else {
        dsp->processInt32Multiplexd(dsp, input + offset, output, frames);
    }
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
}

extern "C"
JNIEXPORT jbooleanArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt24Packed(JNIEnv *env, jobject obj, jlong self, jbooleanArray inputObj)
{
    /* We need to use jbooleanArray (= unsigned 8-bit) instead of jbyteArray (= signed 8-bit) here! */

    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewBooleanArray(inputLength);

    auto input = env->GetBooleanArrayElements(inputObj, nullptr);
    auto output = env->GetBooleanArrayElements(outputObj, nullptr);

    auto* fieldSurround = wrapper->fieldSurround;
    const bool applyFieldSurround = fieldSurround != nullptr && fieldSurround->isEnabled();
    if (applyFieldSurround) {
        std::lock_guard<std::mutex> lock(wrapper->tempBufferMutex);
        const int sampleCount = static_cast<int>(inputLength / 3);
        const uint32_t frames = static_cast<uint32_t>(sampleCount / 2);
        auto* temp = getTempBuffer(wrapper, static_cast<size_t>(sampleCount));
        if (temp == nullptr) {
            env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
            env->ReleaseBooleanArrayElements(outputObj, output, 0);
            return outputObj;
        }
        auto* inputBytes = reinterpret_cast<uint8_t*>(input);
        auto* outputBytes = reinterpret_cast<uint8_t*>(output);
        constexpr float kInputScaleInv = 1.0f / 2147483648.0f;
        for (int i = 0; i < sampleCount; ++i) {
            temp[i] = static_cast<float>(dsp->i32_from_p24(
                inputBytes + static_cast<size_t>(i) * 3u
            )) * kInputScaleInv;
        }
        fieldSurround->process(temp, frames);
        dsp->processFloatMultiplexd(dsp, temp, temp, frames);

        for (int i = 0; i < sampleCount; ++i) {
            dsp->p24_from_i32(clamp24FromFloat(temp[i]), outputBytes + static_cast<size_t>(i) * 3u);
        }
    } else {
        dsp->processInt24PackedMultiplexd(
            dsp,
            reinterpret_cast<uint8_t*>(input),
            reinterpret_cast<uint8_t*>(output),
            static_cast<size_t>(inputLength / 6)
        );
    }
    env->ReleaseBooleanArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseBooleanArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processInt8U24(JNIEnv *env, jobject obj, jlong self, jintArray inputObj)
{
    // Return inputObj if DECLARE failed
    DECLARE_DSP(inputObj)

    auto inputLength = env->GetArrayLength(inputObj);
    auto outputObj = env->NewIntArray(inputLength);

    auto input = env->GetIntArrayElements(inputObj, nullptr);
    auto output = env->GetIntArrayElements(outputObj, nullptr);

    auto* fieldSurround = wrapper->fieldSurround;
    const bool applyFieldSurround = fieldSurround != nullptr && fieldSurround->isEnabled();
    if (applyFieldSurround) {
        std::lock_guard<std::mutex> lock(wrapper->tempBufferMutex);
        constexpr float kInt24ScaleInv = 1.0f / 8388608.0f;
        constexpr float kInt24Scale = 8388608.0f;
        constexpr float kInt24Max = 8388607.0f;
        constexpr float kInt24Min = -8388608.0f;
        const uint32_t frames = static_cast<uint32_t>(inputLength / 2);
        auto* temp = getTempBuffer(wrapper, static_cast<size_t>(inputLength));
        if (temp == nullptr) {
            env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
            env->ReleaseIntArrayElements(outputObj, output, 0);
            return outputObj;
        }
        for (int i = 0; i < inputLength; ++i) {
            temp[i] = static_cast<float>(input[i]) * kInt24ScaleInv;
        }
        fieldSurround->process(temp, frames);
        dsp->processFloatMultiplexd(dsp, temp, temp, frames);

        for (int i = 0; i < inputLength; ++i) {
            float scaled = temp[i] * kInt24Scale;
            if (scaled > kInt24Max) {
                scaled = kInt24Max;
            } else if (scaled < kInt24Min) {
                scaled = kInt24Min;
            }
            output[i] = static_cast<jint>(scaled > 0.0f ? scaled + 0.5f : scaled - 0.5f);
        }
    } else {
        dsp->processInt8_24Multiplexd(dsp, input, output, static_cast<size_t>(inputLength / 2));
    }
    env->ReleaseIntArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseIntArrayElements(outputObj, output, 0);
    return outputObj;
}

extern "C"
JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_processFloat(JNIEnv *env, jobject obj, jlong self, jfloatArray inputObj, jfloatArray outputObj, jint offset, jint size)
{
    DECLARE_DSP_V

    jsize inputLength;
    if(size < 0)
        inputLength = env->GetArrayLength(inputObj);
    else
        inputLength = size;
    if(offset < 0)
        offset = 0;

    auto input = env->GetFloatArrayElements(inputObj, nullptr);
    auto output = env->GetFloatArrayElements(outputObj, nullptr);
    auto* fieldSurround = wrapper->fieldSurround;
    const bool applyFieldSurround = fieldSurround != nullptr && fieldSurround->isEnabled();
    const uint32_t frames = static_cast<uint32_t>(inputLength / 2);
    if (applyFieldSurround) {
        std::lock_guard<std::mutex> lock(wrapper->tempBufferMutex);
        auto* temp = getTempBuffer(wrapper, static_cast<size_t>(inputLength));
        if (temp == nullptr) {
            env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
            env->ReleaseFloatArrayElements(outputObj, output, 0);
            return;
        }
        for (int i = 0; i < inputLength; ++i) {
            temp[i] = input[offset + i];
        }
        fieldSurround->process(temp, frames);
        dsp->processFloatMultiplexd(dsp, temp, output, frames);
    } else {
        dsp->processFloatMultiplexd(dsp, input + offset, output, frames);
    }

    env->ReleaseFloatArrayElements(inputObj, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputObj, output, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLimiter(JNIEnv *env, jobject obj, jlong self, jfloat threshold, jfloat release)
{
    DECLARE_DSP_B
    JLimiterSetCoefficients(dsp, threshold, release);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setClarity(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jboolean enable,
    jint mode,
    jfloat gain,
    jfloat postGainDb,
    jboolean safetyEnabled,
    jfloat safetyThresholdDb,
    jfloat safetyReleaseMs,
    jint naturalLpfOffsetHz,
    jint ozoneFreqHz,
    jint xhifiLowCutHz,
    jint xhifiHighCutHz,
    jfloat xhifiHpMix,
    jfloat xhifiBpMix,
    jint xhifiBpDelayDivisor,
    jint xhifiLpDelayDivisor)
{
    DECLARE_DSP_B

    auto sanitize = [](float value, float fallback) {
        return std::isfinite(value) ? value : fallback;
    };

    // Keep the local bridge behavior aligned with ViPER core dispatch:
    // mode and gain are forwarded as-is (except non-finite sanitization).
    const int safeMode = static_cast<int>(mode);
    const float safeGain = sanitize(gain, 0.0f);
    const float safePostGainDb = sanitize(postGainDb, 0.0f);
    const float safeSafetyThresholdDb = sanitize(safetyThresholdDb, -0.8f);
    const float safeSafetyReleaseMs = sanitize(safetyReleaseMs, 60.0f);
    const float safeXhifiHpMix = sanitize(xhifiHpMix, 1.2f);
    const float safeXhifiBpMix = sanitize(xhifiBpMix, 1.0f);

    ClaritySetParam(
        dsp,
        safeMode,
        safeGain,
        safePostGainDb,
        safetyEnabled ? 1 : 0,
        safeSafetyThresholdDb,
        safeSafetyReleaseMs,
        naturalLpfOffsetHz,
        ozoneFreqHz,
        xhifiLowCutHz,
        xhifiHighCutHz,
        safeXhifiHpMix,
        safeXhifiBpMix,
        xhifiBpDelayDivisor,
        xhifiLpDelayDivisor
    );
    if (enable) {
        ClarityEnable(dsp);
    } else {
        ClarityDisable(dsp);
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setPostGain(JNIEnv *env, jobject obj, jlong self, jfloat gain)
{
    DECLARE_DSP_B
    JamesDSPSetPostGain(dsp, gain);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setMultiEqualizer(JNIEnv *env, jobject obj, jlong self,
                                                                                   jboolean enable, jint filterType, jint interpolationMode,
                                                                                   jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 30)
    {
        LOGE("JamesDspWrapper::setMultiEqualizer: Invalid EQ data. 30 semicolon-separated fields expected, "
                      "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setMultiEqualizer: EQ band pointer is NULL. Disabling EQ");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        MultimodalEqualizerAxisInterpolation(dsp, interpolationMode, filterType, nativeBands, nativeBands + 15);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        MultimodalEqualizerEnable(dsp, 1);
    }
    else
    {
        MultimodalEqualizerDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVdc(JNIEnv *env, jobject obj, jlong self,
                                                                       jboolean enable, jstring vdcContents)
{
    DECLARE_DSP_B
    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(vdcContents, nullptr);
        DDCStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(vdcContents, nativeString);

        int ret = DDCEnable(dsp, 1);
        if (ret <= 0)
        {
            LOGE("JamesDspWrapper::setVdc: Call to DDCEnable(wrapper->dsp) failed. Invalid DDC parameter?");
            LOGE("JamesDspWrapper::setVdc: Disabling DDC engine");
            env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnVdcParseError);

            DDCDisable(dsp);
            return false;
        }
    }
    else
    {
        DDCDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCompander(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat timeConstant, jint granularity, jint tfresolution, jdoubleArray bands)
{
    DECLARE_DSP_B

    if(env->GetArrayLength(bands) != 14)
    {
        LOGE("JamesDspWrapper::setCompander: Invalid compander data. 14 semicolon-separated fields expected, "
             "found %d fields instead.", env->GetArrayLength(bands));
        return false;
    }

    if(bands == nullptr)
    {
        LOGW("JamesDspWrapper::setCompander: Compander band pointer is NULL. Disabling compander");
        MultimodalEqualizerDisable(dsp);
        return true;
    }

    if(enable)
    {
        CompressorSetParam(dsp, timeConstant, granularity, tfresolution, 0);
        auto* nativeBands = (env->GetDoubleArrayElements(bands, nullptr));
        CompressorSetGain(dsp, nativeBands, nativeBands + 7, 1);
        env->ReleaseDoubleArrayElements(bands, nativeBands, JNI_ABORT);
        CompressorEnable(dsp, 1);
    }
    else
    {
        CompressorDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setReverb(JNIEnv *env, jobject obj, jlong self,
                                                                          jboolean enable, jint preset)
{
    DECLARE_DSP_B
    if(enable)
    {
        Reverb_SetParam(dsp, preset);
        ReverbEnable(dsp);
    }
    else
    {
        ReverbDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setConvolver(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloatArray impulseResponse,
                                                                             jint irChannels, jint irFrames)
{
    DECLARE_DSP_B

    int success = 1;
    if(env->GetArrayLength(impulseResponse) <= 0)
    {
        LOGW("JamesDspWrapper::setConvolver: Impulse response array is empty. Disabling convolver");
        enable = false;
    }

    if(enable)
    {
        if(irFrames <= 0)
        {
            LOGW("JamesDspWrapper::setConvolver: Impulse response has zero frames");
        }

        LOGD("JamesDspWrapper::setConvolver: Impulse response loaded: channels=%d, frames=%d", irChannels, irFrames);

        Convolver1DDisable(dsp);

        auto* nativeImpulse = (env->GetFloatArrayElements(impulseResponse, nullptr));
        success = Convolver1DLoadImpulseResponse(dsp, nativeImpulse, irChannels, irFrames, 1);
        env->ReleaseFloatArrayElements(impulseResponse, nativeImpulse, JNI_ABORT);
    }

    if(enable)
        Convolver1DEnable(dsp);
    else
        Convolver1DDisable(dsp);

    if(success <= 0)
    {
        LOGD("JamesDspWrapper::setConvolver: Failed to update convolver. Convolver1DLoadImpulseResponse returned an error.");
        return false;
    }

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setGraphicEq(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jstring graphicEq)
{
    DECLARE_DSP_B
    if(graphicEq == nullptr || env->GetStringUTFLength(graphicEq) <= 0)
    {
        LOGE("JamesDspWrapper::setGraphicEq: graphicEq is empty or NULL. Disabling graphic eq.");
        enable = false;
    }

    if(enable)
    {
        const char *nativeString = env->GetStringUTFChars(graphicEq, nullptr);
        ArbitraryResponseEqualizerStringParser(dsp, (char*)nativeString);
        env->ReleaseStringUTFChars(graphicEq, nativeString);

        ArbitraryResponseEqualizerEnable(dsp, 1);
    }
    else
        ArbitraryResponseEqualizerDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setCrossfeed(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jint mode, jint customFcut, jint customFeed)
{
    DECLARE_DSP_B
    if(mode == 99)
    {
        memset(&dsp->advXF.bs2b, 0, sizeof(dsp->advXF.bs2b));
        BS2BInit(&dsp->advXF.bs2b[1], (unsigned int)dsp->fs, ((unsigned int)customFcut | ((unsigned int)customFeed << 16)));
        dsp->advXF.mode = 1;
    }
    else
    {
       CrossfeedChangeMode(dsp, mode);
    }

    if(enable)
        CrossfeedEnable(dsp, 1);
    else
        CrossfeedDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setBassBoost(JNIEnv *env, jobject obj, jlong self,
                                                                             jboolean enable, jfloat maxGain)
{
    DECLARE_DSP_B
    if(enable)
    {
        BassBoostSetParam(dsp, maxGain);
        BassBoostEnable(dsp);
    }
    else
    {
        BassBoostDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setStereoEnhancement(JNIEnv *env, jobject obj, jlong self,
                                                                                     jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    StereoEnhancementDisable(dsp);
    StereoEnhancementSetParam(dsp, level / 100.0f);
    if(enable)
    {
        StereoEnhancementEnable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setFieldSurround(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jboolean enable,
    jint outputMode,
    jint widening,
    jint midImage,
    jint depth,
    jint phaseOffset,
    jint monoSumMix,
    jint monoSumPan,
    jfloat delayLeftMs,
    jfloat delayRightMs,
    jfloat hpfFrequencyHz,
    jfloat hpfGainDb,
    jfloat hpfQ,
    jint branchThreshold,
    jfloat gainScaleDb,
    jfloat gainOffsetDb,
    jfloat gainCap,
    jfloat stereoFloor,
    jfloat stereoFallback)
{
    DECLARE_WRAPPER_B
    auto* fieldSurround = wrapper->fieldSurround;
    RETURN_IF_NULL(fieldSurround, false)

    auto sanitize = [](float value, float fallback) {
        return std::isfinite(value) ? value : fallback;
    };

    fieldSurround->setOutputModeFromParamInt(outputMode);
    fieldSurround->setWidenFromParamInt(widening);
    fieldSurround->setMidFromParamInt(midImage);
    fieldSurround->setDepthFromParamInt(static_cast<short>(depth));
    fieldSurround->setPhaseOffsetFromParamInt(phaseOffset);
    fieldSurround->setMonoSumMixFromParamInt(monoSumMix);
    fieldSurround->setMonoSumPanFromParamInt(monoSumPan);
    fieldSurround->setAdvancedParams(
        sanitize(delayLeftMs, 20.0f),
        sanitize(delayRightMs, 14.0f),
        sanitize(hpfFrequencyHz, 800.0f),
        sanitize(hpfGainDb, -11.0f),
        sanitize(hpfQ, 0.72f),
        branchThreshold,
        sanitize(gainScaleDb, 10.0f),
        sanitize(gainOffsetDb, -15.0f),
        sanitize(gainCap, 1.0f),
        sanitize(stereoFloor, 2.0f),
        sanitize(stereoFallback, 0.5f)
    );
    fieldSurround->setEnabled(enable);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setVacuumTube(JNIEnv *env, jobject obj, jlong self,
                                                                              jboolean enable, jfloat level)
{
    DECLARE_DSP_B
    if(enable)
    {
        VacuumTubeSetGain(dsp, level / 100.0f);
        VacuumTubeEnable(dsp);
    }
    else
    {
        VacuumTubeDisable(dsp);
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setSpectrumExtension(
    JNIEnv *env,
    jobject obj,
    jlong self,
    jboolean enable,
    jfloat strengthLinear,
    jint referenceFreq,
    jfloat wetMix,
    jfloat postGainDb,
    jboolean safetyEnabled,
    jfloat hpQ,
    jfloat lpQ,
    jint lpCutoffOffsetHz,
    jdoubleArray harmonics)
{
    DECLARE_DSP_B

    if (harmonics == nullptr || env->GetArrayLength(harmonics) != 10)
    {
        LOGE("JamesDspWrapper::setSpectrumExtension: Invalid harmonic coefficient data. 10 fields expected.");
        return false;
    }

    jdouble *harmonicsData = env->GetDoubleArrayElements(harmonics, nullptr);
    if (harmonicsData == nullptr)
    {
        LOGE("JamesDspWrapper::setSpectrumExtension: Failed to access harmonic coefficient data.");
        return false;
    }

    auto sanitize = [](float value, float fallback) {
        return std::isfinite(value) ? value : fallback;
    };
    static const double kSpectrumDefaultHarmonics[10] = {
        0.02, 0.0, 0.02, 0.0, 0.02,
        0.0, 0.02, 0.0, 0.02, 0.0
    };

    double safeHarmonics[10];
    for (int i = 0; i < 10; i++) {
        safeHarmonics[i] = std::isfinite(harmonicsData[i]) ? harmonicsData[i] : kSpectrumDefaultHarmonics[i];
    }

    float safeStrength = sanitize(strengthLinear, 0.0f);
    int safeReferenceFreq = referenceFreq;
    float safeWetMix = sanitize(wetMix, 1.0f);
    float safePostGainDb = sanitize(postGainDb, 0.0f);
    int safeLpCutoffOffsetHz = lpCutoffOffsetHz;
    float safeHpQ = sanitize(hpQ, 0.717f);
    float safeLpQ = sanitize(lpQ, 0.717f);
    if (safeHpQ <= 0.0f)
        safeHpQ = 0.717f;
    if (safeLpQ <= 0.0f)
        safeLpQ = 0.717f;

    SpectrumExtensionSetParam(
        dsp,
        safeStrength,
        safeReferenceFreq,
        safeWetMix,
        safePostGainDb,
        safetyEnabled ? 1 : 0,
        safeHpQ,
        safeLpQ,
        safeLpCutoffOffsetHz,
        safeHarmonics
    );

    env->ReleaseDoubleArrayElements(harmonics, harmonicsData, JNI_ABORT);

    if (enable)
        SpectrumExtensionEnable(dsp);
    else
        SpectrumExtensionDisable(dsp);

    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_setLiveprog(JNIEnv *env, jobject obj, jlong self,
                                                                            jboolean enable, jstring id, jstring liveprogContent)
{
    DECLARE_DSP_B

    // Attach log listener
    setStdOutHandler(receiveLiveprogStdOut, wrapper);

    LiveProgDisable(dsp);

    const char *nativeString = env->GetStringUTFChars(liveprogContent, nullptr);
    if(strlen(nativeString) < 1) {
        LOGD("JamesDspWrapper::setLiveprog: empty file")
        env->ReleaseStringUTFChars(liveprogContent, nativeString);
        return true;
    }

    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogExec, id);

    int ret = LiveProgStringParser(dsp, (char*)nativeString); // Ignore constness, libjamesdsp does not modify it
    env->ReleaseStringUTFChars(liveprogContent, nativeString);

    // Workaround due to library bug
    jdsp_unlock(dsp);

    const char* errorString = NSEEL_code_getcodeerror(dsp->eel.vm);
    if(errorString != nullptr)
    {
        LOGW("JamesDspWrapper::setLiveprog: NSEEL_code_getcodeerror: Syntax error in script file, cannot load. Reason: %s", errorString);
    }
    if(ret <= 0)
    {
        LOGW("JamesDspWrapper::setLiveprog: %s", checkErrorCode(ret));
    }

    jstring errorStringJni = env->NewStringUTF(errorString);
    env->CallVoidMethod(wrapper->callbackInterface, wrapper->callbackOnLiveprogResult, ret, id, errorStringJni);
    env->DeleteLocalRef(errorStringJni);

    if(enable)
        LiveProgEnable(dsp);
    else
        LiveProgDisable(dsp);
    return true;
}


extern "C" JNIEXPORT jobject JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_enumerateEelVariables(JNIEnv *env, jobject obj, jlong self)
{
    auto array = JArrayList(env);

    // Return empty array if DECLARE failed
    DECLARE_DSP(array.getJavaReference())

    auto *ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            // TODO fix string handling (broke after last libjamesdsp update)
            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            bool isString = valid;

            if (ctx->varTable_Names[i][j])
            {
                const char* name = ctx->varTable_Names[i][j];
                const char* value;

                if(isString)
                    value = valid;
                else
                    value = std::to_string(ctx->varTable_Values[i][j]).c_str();

                auto var = EelVmVariable(env, name, value, isString);
                array.add(var.getJavaReference());
            }
        }
    }

    return array.getJavaReference();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_manipulateEelVariable(JNIEnv *env, jobject obj, jlong self,
                                                                                      jstring name, jfloat value)
{
    DECLARE_DSP_B
    auto* ctx = (compileContext*)dsp->eel.vm;
    for (int i = 0; i < ctx->varTable_numBlocks; i++)
    {
        for (int j = 0; j < NSEEL_VARS_PER_BLOCK; j++)
        {
            const char *nativeName = env->GetStringUTFChars(name, nullptr);
            if(!ctx->varTable_Names[i][j] || std::strcmp(ctx->varTable_Names[i][j], nativeName) != 0)
            {
                env->ReleaseStringUTFChars(name, nativeName);
                continue;
            }

            const char *valid = nullptr;//(char*)GetStringForIndex(ctx->region_context, ctx->varTable_Values[i][j], 1);
            if(valid)
            {
                LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' is a string; currently only numerical variables can be manipulated", nativeName);
                env->ReleaseStringUTFChars(name, nativeName);
                return false;
            }

            ctx->varTable_Values[i][j] = value;

            env->ReleaseStringUTFChars(name, nativeName);
            return true;
        }
    }

    const char *nativeName = env->GetStringUTFChars(name, nullptr);
    LOGE("JamesDspWrapper::manipulateEelVariable: variable '%s' not found", nativeName);
    env->ReleaseStringUTFChars(name, nativeName);
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_freezeLiveprogExecution(JNIEnv *env, jobject obj, jlong self,
                                                                                        jboolean freeze)
{
    DECLARE_DSP_V
    dsp->eel.active = !freeze;
    LOGD("JamesDspWrapper::freezeLiveprogExecution: Liveprog execution has been %s", (freeze ? "frozen" : "resumed"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_me_timschneeberger_rootlessjamesdsp_interop_JamesDspWrapper_eelErrorCodeToString(JNIEnv *env,
                                                                                     jobject obj,
                                                                                     jint error_code)
{
    return env->NewStringUTF(checkErrorCode(error_code));
}

void receiveLiveprogStdOut(const char *buffer, void* userData)
{
    auto* self = static_cast<JamesDspWrapper*>(userData);
    if(self == nullptr)
    {
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Self reference is NULL");
        LOGE("JamesDspWrapper::receiveLiveprogStdOut: Unhandled output: %s", buffer);
        return;
    }

    self->env->CallVoidMethod(self->callbackInterface, self->callbackOnLiveprogOutput, self->env->NewStringUTF(buffer));
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
#ifndef NO_CRASHLYTICS
    firebase::crashlytics::Initialize();
#endif
    LOGD("JNI_OnLoad called")
    return JNI_VERSION_1_6;
}
