#include "FieldSurroundProcessor.h"

#include <algorithm>
#include <cmath>

namespace fieldsurround {

static constexpr double PI = 3.14159265358979323846;

void TimeConstDelay::setParameters(uint32_t samplingRate, float delaySeconds) {
    static constexpr float kMaxDelaySeconds = 5.0f;
    const float safeDelay = std::isfinite(delaySeconds) ? delaySeconds : 0.0f;
    const float clampedDelay = std::clamp(safeDelay, 0.0f, kMaxDelaySeconds);

    sampleCount = 1;
    if (samplingRate > 0) {
        sampleCount = static_cast<uint32_t>(static_cast<double>(samplingRate) * static_cast<double>(clampedDelay));
        if (sampleCount == 0) {
            sampleCount = 1;
        }
    }

    samples.assign(sampleCount, 0.0f);
    offset = 0;
}

float TimeConstDelay::processSample(float sample) {
    if (samples.empty()) {
        return sample;
    }

    uint32_t wrapCount = sampleCount;
    if (wrapCount == 0 || wrapCount > samples.size()) {
        wrapCount = static_cast<uint32_t>(samples.size());
    }
    if (wrapCount == 0) {
        return sample;
    }

    if (offset >= wrapCount) {
        offset = 0;
    }

    const float out = samples[offset];
    samples[offset] = sample;
    ++offset;
    if (offset >= wrapCount) {
        offset = 0;
    }
    return out;
}

Biquad::Biquad() {
    setCoeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
}

void Biquad::reset() {
    x1 = 0.0;
    x2 = 0.0;
    y1 = 0.0;
    y2 = 0.0;
}

float Biquad::processSample(float sample) {
    const double out = sample * b0 + x1 * b1 + x2 * b2 + y1 * a1 + y2 * a2;
    x2 = x1;
    x1 = sample;
    y2 = y1;
    y1 = out;
    return static_cast<float>(out);
}

void Biquad::setCoeffs(double a0, double aa1, double aa2, double bb0, double bb1, double bb2) {
    if (std::fabs(a0) < 1.0e-12) {
        x1 = 0.0;
        x2 = 0.0;
        y1 = 0.0;
        y2 = 0.0;
        a1 = 0.0;
        a2 = 0.0;
        b0 = 1.0;
        b1 = 0.0;
        b2 = 0.0;
        return;
    }

    x1 = 0.0;
    x2 = 0.0;
    y1 = 0.0;
    y2 = 0.0;

    a1 = -(aa1 / a0);
    a2 = -(aa2 / a0);
    b0 = bb0 / a0;
    b1 = bb1 / a0;
    b2 = bb2 / a0;
}

void Biquad::setHighPassParameter(float frequency, uint32_t samplingRate, double dbGain, float qFactor) {
    if (samplingRate == 0) {
        // Keep the filter neutral if sampling rate is unavailable.
        setCoeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        return;
    }

    const double omega = (2.0 * PI * static_cast<double>(frequency)) / static_cast<double>(samplingRate);
    const double sinOmega = std::sin(omega);
    const double cosOmega = std::cos(omega);

    const double A = std::pow(10.0, dbGain / 40.0);
    const double sqrtA = std::sqrt(A);
    const double z = sinOmega / 2.0 * std::sqrt((1.0 / A + A) * (1.0 / static_cast<double>(qFactor) - 1.0) + 2.0);

    const double a0 = (A + 1.0) - (A - 1.0) * cosOmega + 2.0 * sqrtA * z;
    const double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosOmega);
    const double a2 = (A + 1.0) - (A - 1.0) * cosOmega - 2.0 * sqrtA * z;
    const double b0 = ((A + 1.0) + (A - 1.0) * cosOmega + 2.0 * sqrtA * z) * A * omega;
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosOmega) * omega;
    const double b2 = ((A + 1.0) + (A - 1.0) * cosOmega - 2.0 * sqrtA * z) * A * omega;

    setCoeffs(a0, a1, a2, b0, b1, b2);
}

void PhaseShifter::setCoefficient(float value) {
    coefficient = std::clamp(value, -0.99f, 0.99f);
}

float PhaseShifter::processSample(float sample) {
    const float out = (-coefficient * sample) + x1 + (coefficient * y1);
    x1 = sample;
    y1 = out;
    return out;
}

void PhaseShifter::reset() {
    x1 = 0.0f;
    y1 = 0.0f;
}

void Stereo3DSurround::setStereoWiden(float value) {
    stereoWiden = value;
    configureVariables();
}

void Stereo3DSurround::setMiddleImage(float value) {
    middleImage = value;
    configureVariables();
}

void Stereo3DSurround::setNormalization(float floor, float fallback) {
    normalizeFloor = floor;
    normalizeFallback = fallback;
    configureVariables();
}

void Stereo3DSurround::configureVariables() {
    const float tmp = stereoWiden + 1.0f;
    const float x = tmp + 1.0f;
    const float y = x < normalizeFloor ? normalizeFallback : (1.0f / x);
    coeffLeft = middleImage * y;
    coeffRight = tmp * y;
}

void Stereo3DSurround::process(float* samples, uint32_t frames) {
    if (samples == nullptr || frames == 0) {
        return;
    }

    for (uint32_t i = 0; i < frames; ++i) {
        const uint32_t index = i * 2;
        const float inL = samples[index];
        const float inR = samples[index + 1];
        const float a = coeffLeft * (inL + inR);
        const float b = coeffRight * (inR - inL);
        samples[index] = a - b;
        samples[index + 1] = a + b;
    }
}

void DepthSurround::setSamplingRate(uint32_t sr) {
    samplingRate = sr;
    configureFilters();
}

void DepthSurround::setStrength(int16_t value) {
    strength = value;
    refreshStrength();
}

void DepthSurround::setDelayMs(float leftMs, float rightMs) {
    if (delayLeftMs == leftMs && delayRightMs == rightMs) {
        return;
    }
    delayLeftMs = leftMs;
    delayRightMs = rightMs;
    configureFilters();
}

void DepthSurround::setHighPass(float frequencyHz, float gainDb, float qFactor) {
    if (highpassFrequencyHz == frequencyHz && highpassGainDb == gainDb && highpassQ == qFactor) {
        return;
    }
    highpassFrequencyHz = frequencyHz;
    highpassGainDb = gainDb;
    highpassQ = qFactor;
    configureFilters();
}

void DepthSurround::setBranchThreshold(int threshold) {
    if (branchThreshold == threshold) {
        return;
    }
    branchThreshold = threshold;
    refreshStrength();
}

void DepthSurround::setGainModel(float scaleDb, float offsetDb, float cap) {
    if (gainScaleDb == scaleDb && gainOffsetDb == offsetDb && gainCap == cap) {
        return;
    }
    gainScaleDb = scaleDb;
    gainOffsetDb = offsetDb;
    gainCap = cap;
    refreshStrength();
}

void DepthSurround::reset() {
    prev[0] = 0.0f;
    prev[1] = 0.0f;
}

void DepthSurround::configureFilters() {
    delay[0].setParameters(samplingRate, delayLeftMs / 1000.0f);
    delay[1].setParameters(samplingRate, delayRightMs / 1000.0f);
    highpass.setHighPassParameter(highpassFrequencyHz, samplingRate, highpassGainDb, highpassQ);
    reset();
}

void DepthSurround::refreshStrength() {
    strengthAtLeastThreshold = strength >= branchThreshold;
    enabled = strength != 0;

    if (!enabled) {
        gain = 0.0f;
        return;
    }

    float computedGain = std::pow(10.0f, (((static_cast<float>(strength) / 1000.0f) * gainScaleDb) + gainOffsetDb) / 20.0f);
    computedGain = std::max(0.0f, computedGain);
    gain = std::max(0.0f, std::min(gainCap, computedGain));
}

void DepthSurround::process(float* samples, uint32_t frames) {
    if (!enabled || samples == nullptr || frames == 0) {
        return;
    }

    for (uint32_t i = 0; i < frames; ++i) {
        const uint32_t index = i * 2;
        const float sampleLeft = samples[index];
        const float sampleRight = samples[index + 1];

        prev[0] = gain * delay[0].processSample(sampleLeft + prev[1]);
        if (strengthAtLeastThreshold) {
            prev[1] = -gain * delay[1].processSample(sampleRight + prev[0]);
        } else {
            prev[1] = gain * delay[1].processSample(sampleRight + prev[0]);
        }

        const float l = prev[0] + sampleLeft;
        const float r = prev[1] + sampleRight;

        const float diff = (l - r) * 0.5f;
        const float avg = (l + r) * 0.5f;
        const float hp = highpass.processSample(diff);
        const float side = diff - hp;

        samples[index] = avg + side;
        samples[index + 1] = avg - side;
    }
}

void FieldSurroundProcessor::setSamplingRate(uint32_t sr) {
    if (samplingRate != sr) {
        samplingRate = sr;
        depthSurround.setSamplingRate(samplingRate);
    }
}

void FieldSurroundProcessor::configurePhaseShifters() {
    // Use mirrored all-pass coefficients to introduce a controllable relative phase offset.
    const float coeff = std::clamp(phaseOffset * 0.95f, -0.95f, 0.95f);
    phaseShifter[0].setCoefficient(coeff);
    phaseShifter[1].setCoefficient(-coeff);
}

void FieldSurroundProcessor::setEnabled(bool value) {
    if (enabled != value) {
        if (!enabled) {
            reset();
        }
        enabled = value;
    }
}

void FieldSurroundProcessor::setOutputModeFromParamInt(int value) {
    const int mode = std::clamp(value, 0, 2);
    outputMode = static_cast<OutputMode>(mode);
}

void FieldSurroundProcessor::setWidenFromParamInt(int value) {
    stereo3dSurround.setStereoWiden(static_cast<float>(value) / 100.0f);
}

void FieldSurroundProcessor::setMidFromParamInt(int value) {
    stereo3dSurround.setMiddleImage(static_cast<float>(value) / 100.0f);
}

void FieldSurroundProcessor::setDepthFromParamInt(int value) {
    depthSurround.setStrength(static_cast<int16_t>(value));
}

void FieldSurroundProcessor::setPhaseOffsetFromParamInt(int value) {
    phaseOffset = static_cast<float>(std::clamp(value, -100, 100)) / 100.0f;
    configurePhaseShifters();
}

void FieldSurroundProcessor::setMonoSumMixFromParamInt(int value) {
    monoSumMix = static_cast<float>(std::clamp(value, 0, 100)) / 100.0f;
}

void FieldSurroundProcessor::setMonoSumPanFromParamInt(int value) {
    monoSumPan = static_cast<float>(std::clamp(value, -100, 100)) / 100.0f;
}

void FieldSurroundProcessor::setAdvancedParams(
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
) {
    depthSurround.setDelayMs(delayLeftMs, delayRightMs);
    depthSurround.setHighPass(hpfFrequencyHz, hpfGainDb, hpfQ);
    depthSurround.setBranchThreshold(branchThreshold);
    depthSurround.setGainModel(gainScaleDb, gainOffsetDb, gainCap);
    stereo3dSurround.setNormalization(stereoFloor, stereoFallback);
}

void FieldSurroundProcessor::reset() {
    depthSurround.setSamplingRate(samplingRate);
    phaseShifter[0].reset();
    phaseShifter[1].reset();
    configurePhaseShifters();
}

void FieldSurroundProcessor::process(float* samples, uint32_t frames) {
    if (!enabled || samples == nullptr || frames == 0) {
        return;
    }

    depthSurround.process(samples, frames);
    stereo3dSurround.process(samples, frames);

    if (phaseOffset != 0.0f) {
        for (uint32_t i = 0; i < frames; ++i) {
            const uint32_t index = i * 2;
            samples[index] = phaseShifter[0].processSample(samples[index]);
            samples[index + 1] = phaseShifter[1].processSample(samples[index + 1]);
        }
    }

    if (outputMode != OutputMode::Normal) {
        for (uint32_t i = 0; i < frames; ++i) {
            const uint32_t index = i * 2;
            const float inL = samples[index];
            const float inR = samples[index + 1];
            const float mono = outputMode == OutputMode::PureSideMono
                ? ((inR - inL) * 0.5f)
                : ((inL + inR) * 0.5f);
            samples[index] = mono;
            samples[index + 1] = mono;
        }
    }

    if (monoSumMix > 0.0f) {
        const float mixDry = 1.0f - monoSumMix;
        const float panLeftWeight = 1.0f - std::max(0.0f, monoSumPan);
        const float panRightWeight = 1.0f + std::min(0.0f, monoSumPan);

        for (uint32_t i = 0; i < frames; ++i) {
            const uint32_t index = i * 2;
            const float inL = samples[index];
            const float inR = samples[index + 1];
            const float mono = (inL + inR) * 0.5f;
            samples[index] = (mixDry * inL) + (monoSumMix * mono * panLeftWeight);
            samples[index + 1] = (mixDry * inR) + (monoSumMix * mono * panRightWeight);
        }
    }
}

} // namespace fieldsurround
