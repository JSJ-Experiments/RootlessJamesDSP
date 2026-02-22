#pragma once

#include <cstdint>
#include <vector>

namespace fieldsurround {

class TimeConstDelay {
public:
    void setParameters(uint32_t samplingRate, float delaySeconds);
    float processSample(float sample);

private:
    std::vector<float> samples;
    uint32_t sampleCount = 0;
    uint32_t offset = 0;
};

class Biquad {
public:
    Biquad();
    void reset();
    float processSample(float sample);
    void setHighPassParameter(float frequency, uint32_t samplingRate, double dbGain, float qFactor);

private:
    void setCoeffs(double a0, double a1, double a2, double b0, double b1, double b2);

    double x1 = 0.0;
    double x2 = 0.0;
    double y1 = 0.0;
    double y2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
    double b0 = 0.0;
    double b1 = 0.0;
    double b2 = 0.0;
};

class PhaseShifter {
public:
    void setCoefficient(float coefficient);
    float processSample(float sample);
    void reset();

private:
    float coefficient = 0.0f;
    float x1 = 0.0f;
    float y1 = 0.0f;
};

class Stereo3DSurround {
public:
    void setStereoWiden(float stereoWiden);
    void setMiddleImage(float middleImage);
    void setNormalization(float floor, float fallback);
    void process(float* samples, uint32_t frames);

private:
    void configureVariables();

    float stereoWiden = 0.0f;
    float middleImage = 1.0f;
    float coeffLeft = 0.5f;
    float coeffRight = 0.5f;
    float normalizeFloor = 2.0f;
    float normalizeFallback = 0.5f;
};

class DepthSurround {
public:
    void setSamplingRate(uint32_t samplingRate);
    void setStrength(int16_t strength);
    void setDelayMs(float leftMs, float rightMs);
    void setHighPass(float frequencyHz, float gainDb, float qFactor);
    void setBranchThreshold(int threshold);
    void setGainModel(float scaleDb, float offsetDb, float gainCap);
    void process(float* samples, uint32_t frames);
    void reset();

private:
    void configureFilters();
    void refreshStrength();

    int16_t strength = 0;
    bool enabled = false;
    bool strengthAtLeastThreshold = false;
    float gain = 0.0f;
    float prev[2] = {0.0f, 0.0f};

    uint32_t samplingRate = 44100;
    float delayLeftMs = 20.0f;
    float delayRightMs = 14.0f;
    float highpassFrequencyHz = 800.0f;
    float highpassGainDb = -11.0f;
    float highpassQ = 0.72f;
    int branchThreshold = 500;
    float gainScaleDb = 10.0f;
    float gainOffsetDb = -15.0f;
    float gainCap = 1.0f;

    TimeConstDelay delay[2];
    Biquad highpass;
};

class FieldSurroundProcessor {
public:
    void setSamplingRate(uint32_t samplingRate);
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled; }
    void setOutputModeFromParamInt(int value);
    void setWidenFromParamInt(int value);
    void setMidFromParamInt(int value);
    void setDepthFromParamInt(int value);
    void setPhaseOffsetFromParamInt(int value);
    void setMonoSumMixFromParamInt(int value);
    void setMonoSumPanFromParamInt(int value);
    void setAdvancedParams(
        float delayLeftMs,
        float delayRightMs,
        float hpfFrequencyHz,
        float hpfGainDb,
        float hpfQ,
        int branchThreshold,
        float gainScaleDb,
        float gainOffsetDb,
        float gainCap,
        float stereoFloor,
        float stereoFallback
    );
    void reset();
    void process(float* samples, uint32_t frames);

private:
    enum class OutputMode : int {
        Normal = 0,
        PureSideMono = 1,
        MidOnlyMono = 2
    };

    void configurePhaseShifters();

    bool enabled = false;
    uint32_t samplingRate = 44100;
    OutputMode outputMode = OutputMode::Normal;
    float phaseOffset = 0.0f;
    float monoSumMix = 0.0f;
    float monoSumPan = 0.0f;
    PhaseShifter phaseShifter[2];
    DepthSurround depthSurround;
    Stereo3DSurround stereo3dSurround;
};

} // namespace fieldsurround
