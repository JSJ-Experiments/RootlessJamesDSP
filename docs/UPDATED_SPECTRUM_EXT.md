# Spectrum Extension (VSE) Implementation Spec

> Publication status: Ready for engineering publication
> Last updated: 2026-02-22
> Intended audience: DSP and audio-engine developers implementing compatibility behavior

## 0. How to use this document

- Treat sections 4 through 11 as the normative DSP and parameter contract.
- Treat sections 12 and 13 as app/wrapper compatibility behavior.
- Treat section 14 as a mandatory portability safeguard for clean-room implementations.
- Use section 18 for direct source-file lookup.

## 1. Scope
This file consolidates `./SPECTRUM_EXT.md`, `./viper4android/SPECTRUM_EXT.md`, and `./android/SPECTRUM_EXT.md` into one implementation-grade spec.

Goal: allow reimplementation of Spectrum Extension from scratch with behavior-compatible DSP, parameter API, and app mapping.

Important: Spectrum Extension is not ViPER Clarity.

## 2. Canonical sources and precedence
Use this precedence when details conflict:

1. `ViPERFX_RE` C++ source for DSP and command dispatch.
2. Shipped Android module binary (`android/extracted/module/common/files/libv4a_re_*.so`) for toolchain/runtime confirmation.
3. Shipped Android app resources and smali (`android/extracted/apktool/V4A_App_0.6.2`) for UI defaults and app-to-driver mapping.
4. Linux wrappers/GUI (`Viper4LinuxV1`, `Viper4LinuxV2`, `Viper4LinuxV2-GUI`) for compatibility key names and legacy conversion behavior.

## 3. Effect identity and what it is not
Spectrum Extension (`SpectrumExtend`) is a separate effect that does:

1. High-pass isolate upper band.
2. Nonlinear harmonic reconstruction on that band.
3. Low-pass constrain reconstructed content.
4. Add reconstructed band back to dry sample.

It is not ViPER Clarity (`ViPERClarity`), which uses different parameter IDs and different processing modes/algorithms.

## 4. Parameter API (driver/core)
From `ViPERFX_RE/src/ViPER4Android.h` and `ViPERFX_RE/src/viper/ViPER.cpp`:

- `65548` (`PARAM_SPECTRUM_EXTENSION_ENABLE`) -> `SetEnable(val1 != 0)`
- `65549` (`PARAM_SPECTRUM_EXTENSION_BARK`) -> `SetReferenceFrequency(val1)`
- `65550` (`PARAM_SPECTRUM_EXTENSION_BARK_RECONSTRUCT`) -> `SetExciter((float)val1 / 100.0f)`

Separate Clarity IDs for reference:

- `65578` (enable), `65579` (mode), `65580` (gain)

## 5. Defaults and internal state
From `ViPERFX_RE/src/viper/effects/SpectrumExtend.cpp`:

- `samplingRate = 44100`
- `referenceFreq = 7600`
- `enabled = false`
- `exciter = 0.0f`

Per-channel state (2 channels):

- `highpass[2]` (`MultiBiquad`)
- `lowpass[2]` (`MultiBiquad`)
- `harmonics[2]` (`Harmonic`)

Static harmonic seed table:

```text
SPECTRUM_HARMONICS[10] = [0.02, 0, 0.02, 0, 0.02, 0, 0.02, 0, 0.02, 0]
```

## 6. Exact processing algorithm
Input format: interleaved stereo float buffer (`L, R, L, R, ...`).

Function signature concept:

```cpp
void Process(float* samples, uint32_t frames)
```

Runtime:

1. If not enabled, return immediately (hard bypass).
2. For each frame:
   - `hpL = HP_L(xL)`
   - `hL  = Harmonic_L(hpL)`
   - `lpL = LP_L(hL * exciter)`
   - `yL  = xL + lpL`
   - Same for right channel.

Exact loop form from source uses `for (i = 0; i < frames * 2; i += 2)`.

## 7. Filter setup (Reset behavior)
`Reset()` reinitializes both channels to:

- High-pass:
  - type: `HIGH_PASS`
  - gainAmp: `0.0`
  - freq: `referenceFreq`
  - Q: `0.717`
  - `param_7`: `false`
- Low-pass:
  - type: `LOW_PASS`
  - gainAmp: `0.0`
  - freq: `(samplingRate / 2) - 2000`
  - Q: `0.717`
  - `param_7`: `false`
- Harmonic:
  - `SetHarmonics(SPECTRUM_HARMONICS)` per channel

## 8. Biquad math (used by Spectrum)
From `MultiBiquad`:

Difference equation:

```text
out = x*b0 + x1*b1 + x2*b2 + y1*a1 + y2*a2
x2=x1; x1=x; y2=y1; y1=out
```

Common intermediates:

```text
omega = 2*pi*freq/samplingRate
sinOmega = sin(omega)
cosOmega = cos(omega)
y = sinOmega/(2*q)          (for Spectrum paths: param_7=false)
```

Low-pass coefficients before normalization:

```text
a0 = 1 + y
a1 = -2*cosOmega
a2 = 1 - y
b0 = (1 - cosOmega)/2
b1 = 1 - cosOmega
b2 = (1 - cosOmega)/2
```

High-pass coefficients before normalization:

```text
a0 = 1 + y
a1 = -2*cosOmega
a2 = 1 - y
b0 = (1 + cosOmega)/2
b1 = -(1 + cosOmega)
b2 = (1 + cosOmega)/2
```

Normalization:

```text
a1 = -(a1/a0)
a2 = -(a2/a0)
b0 = b0/a0
b1 = b1/a0
b2 = b2/a0
```

## 9. Harmonic block math
For each sample `x`:

1. Evaluate degree-10 polynomial by Horner form:

```text
p = c0 + x*(c1 + x*(c2 + ... + x*c10))
```

2. Temporal shaping:

```text
prevOut = (p + prevOut*0.999) - prevLast
```

3. Warm-up gate:

```text
if (sampleCounter < biggestCoeff) {
    sampleCounter++;
    return 0;
}
return prevOut;
```

Important correction: the gate condition is `< biggestCoeff`. It forces zero returns for the first `biggestCoeff` processed samples; once the gate opens, the function returns `prevOut` (not guaranteed non-zero).

### 9.1 Coefficient update transform
`UpdateCoeffs` behavior:

1. Copy 10 input harmonics to internal 11-length temp with `unkarr1[0]=0`.
2. Compute:
   - `absCoeffSum = sum(abs(h[i]))`
   - `biggestCoeffVal = max(abs(h[i]))`
3. `biggestCoeff = floor(biggestCoeffVal * 10000)`
4. If `absCoeffSum > 1.0`, scale all harmonics by `1/absCoeffSum`.
5. Run iterative transform loops to fill `coeffs[0..10]`.

Reference pseudocode (bit-structure compatible with source logic):

```cpp
void UpdateCoeffs(const float in[10]) {
    float unkarr1[11] = {0};   // unkarr1[0] stays 0
    float unkarr2[11] = {0};

    float biggestCoeffVal = 0.0f;
    float absCoeffSum = 0.0f;
    for (uint32_t i = 0; i < 10; i++) {
        // Must be floating abs, not integer abs.
        float a = fabsf(in[i]);
        absCoeffSum += a;
        if (a > biggestCoeffVal) biggestCoeffVal = a;
    }
    biggestCoeff = (uint32_t)(biggestCoeffVal * 10000.0f); // trunc toward zero

    memcpy(unkarr1 + 1, in, 10 * sizeof(float));

    float scale = 1.0f;
    if (absCoeffSum > 1.0f) scale = 1.0f / absCoeffSum;
    for (uint32_t i = 1; i < 11; i++) {
        unkarr1[i] *= scale;
    }

    memset(coeffs, 0, 11 * sizeof(float));
    memset(unkarr2, 0, 11 * sizeof(float));

    coeffs[10] = unkarr1[10];

    for (uint32_t i = 2; i < 11; i++) {
        for (uint32_t j = 0; j < i; j++) {
            float tmp = unkarr2[i - j];
            unkarr2[i - j] = coeffs[i - j];
            coeffs[i - j] = coeffs[i - j - 1] * 2.0f - tmp;
        }
        float tmp = unkarr1[10 - i + 1] - unkarr2[0];
        unkarr2[0] = coeffs[0];
        coeffs[0] = tmp;
    }

    for (uint32_t i = 1; i < 11; i++) {
        coeffs[10 - i + 1] = coeffs[10 - i] - unkarr2[10 - i + 1];
    }

    coeffs[0] = unkarr1[0] / 2.0f - unkarr2[0];
}
```

For `SPECTRUM_HARMONICS`, rounded decimal polynomial coefficients are:

```text
c0=0
c1=0.1
c2=0
c3=-1.6
c4=0
c5=6.72
c6=0
c7=-10.24
c8=0
c9=5.12
c10=0
```

Equivalent rounded polynomial form:

```text
P(x) = 0.1x - 1.6x^3 + 6.72x^5 - 10.24x^7 + 5.12x^9
```

## 10. State-change semantics
From `SpectrumExtend`:

- `SetEnable(enable)`:
  - resets only on state change from disabled to enabled
  - does nothing if value unchanged
- `SetReferenceFrequency(freq)`:
  - clamps only upper bound: `freq <= samplingRate/2 - 100`
  - no lower-bound clamp
  - always calls `Reset()`
- `SetSamplingRate(sr)`:
  - if changed, updates sample rate
  - reclamps `referenceFreq` to `sr/2 - 100` if needed
  - calls `Reset()`
- `SetExciter(v)`:
  - raw assignment
  - no clamp in core

## 11. Global chain order
From `ViPER::process` in `ViPERFX_RE/src/viper/ViPER.cpp`:

`DDC -> SpectrumExtend -> IIR EQ -> ... -> ViPERClarity -> ...`

Implementation detail in current source:

- `viperDdc.Process` and `spectrumExtend.Process` are called with `size`
- most later effects are called with `tmpBufSize`

If reproducing engine-level behavior exactly, keep this detail.

## 12. Android app mapping (0.6.2 app -> 0.6.1 module)
From app resources and smali:

- `key_vse_enable = "65548"`
- `key_vse_value = "65549;65550"`
- UI defaults:
  - enable default: `false`
  - strength default: `10`
  - no explicit max set on this preference entry, so `SeekBarPreference` default max is `100` and min `0`

When strength changes (`key_vse_value` path in `o1/t.smali`):

1. Send `0x1000D` with constant `0x1db0` (`7600`)
2. Compute `int(strength * 5.6)` (double math then `double-to-int`, trunc toward zero)
3. Send that value as `0x1000E`

Driver then applies `/100`, so:

```text
param65550 = trunc(strength * 5.6)
exciter = param65550 / 100.0
```

Examples:

- `strength=10` -> `param65550=56` -> `exciter=0.56`
- `strength=100` -> `param65550=560` -> `exciter=5.6`

Practical result: stock app path effectively pins `referenceFreq` to 7600 during value updates.

## 13. Linux wrapper/GUI compatibility mapping
Defaults and keys:

- `vse_enable=false`
- `vse_ref_bark=7600`
- `vse_bark_cons=10`

Launcher properties sent to plugin:

- `vse-enable`
- `vse-ref-bark`
- `vse-bark-cons`

Runtime plugin property ranges (Docker `gst-inspect-1.0 viperfx`) confirm:

- `vse-ref-bark`: `800..20000`, default `7600`
- `vse-bark-cons`: `10..100`, default `10`
- Saved output artifact: `docker_gst_inspect_viperfx.txt`

Legacy converter caveat (`Viper4LinuxV2-GUI/misc/converter.cpp`):

- Android import path: `vse_bark_cons = round(vse.value * 5.6 * 100)`
- Export path for Android/teamDeWitt uses:
  - `translate(vse_bark_cons, 0.01, 0.1, 0.1, 1) / 1000`

This is not a clean inverse of the runtime app mapping and can produce out-of-domain values.
Also, `translate` takes integer `leftMin/leftMax`, so literals `0.01` and `0.1` are truncated to `0`, making `leftSpan = 0` in that call path.
Treat converter scaling as legacy behavior, not canonical DSP semantics.

## 14. Portability finding that should be handled explicitly
There is a critical portability edge in `Harmonic::UpdateCoeffs`:

- Source uses unqualified `abs(coefficients[i])`.
- In the shipped arm64 module binary, this compiles to floating absolute (`fabs`), which yields expected `biggestCoeff` behavior (for `0.02f`, `biggestCoeff = trunc(0.02f * 10000) = 199` because of float quantization).
- On some host toolchains (for example, a straightforward local g++ build), it can resolve to integer `abs(int)`, causing truncation of `0.02` to `0` and thus `biggestCoeff=0`.

Reimplementation requirement:

- Use explicit floating abs (`fabsf` or `std::abs(float)`) for harmonic magnitude computations.
- Do not use integer abs for this path.

## 15. Reimplementation checklist
1. Implement `SpectrumExtend` with stereo interleaved float I/O and independent per-channel state.
2. Match defaults exactly: `enabled=false`, `referenceFreq=7600`, `exciter=0`, `samplingRate=44100`.
3. Implement `Reset()` exactly (HP, LP, harmonic load, Q=0.717, LP cutoff `sr/2 - 2000`).
4. Implement `Harmonic::UpdateCoeffs` and `Harmonic::Process` exactly, including `0.999` shaping and warm-up gate condition.
5. Preserve parameter IDs and scaling:
   - `65548` enable
   - `65549` reference frequency
   - `65550` exciter via `/100`
6. Preserve state-change/reset semantics and clamp rules (`referenceFreq <= sr/2 - 100` upper clamp only).
7. Keep internal filter/harmonic math in double precision and cast to float at final sample writeback.
8. Keep Spectrum position in chain before EQ and before Clarity.
9. If matching stock app behavior, force `65549=7600` when applying `key_vse_value` updates and use `trunc(strength*5.6)` for `65550`.

## 16. Validation vectors
Minimum validation set for a fresh implementation:

1. Parameter protocol:
   - send `65550=56` and verify internal `exciter=0.56`.
2. Bypass:
   - with `enabled=false`, output equals input exactly.
3. Warm-up gate:
   - with Spectrum harmonics and floating abs, verify `biggestCoeff=199`; first ungated harmonic-stage sample is sample index 200 (1-based), but value may still be `0` depending on signal/state.
4. Reset semantics:
   - toggling enable `false->true` resets states; repeated `true->true` does not.
5. Clamp behavior:
   - `SetReferenceFrequency` clamps only upper bound.
6. App mapping:
   - UI strength `10` and `100` produce `exciter 0.56` and `5.6`.

## 17. Known edge cases
- No lower bound clamp on `referenceFreq` in core DSP.
- LP cutoff uses `sr/2 - 2000` without explicit positive-frequency guard.
- At very low sample rates, cutoff can become non-positive.
- Core `SetExciter` is unclamped.

## 18. Reference index

Primary DSP implementation sources:

- `ViPERFX_RE/src/ViPER4Android.h`
- `ViPERFX_RE/src/viper/ViPER.cpp`
- `ViPERFX_RE/src/viper/effects/SpectrumExtend.cpp`
- `ViPERFX_RE/src/viper/utils/MultiBiquad.cpp`
- `ViPERFX_RE/src/viper/utils/Harmonic.cpp`

Android app/module compatibility sources:

- `android/extracted/apktool/V4A_App_0.6.2/res/values/strings.xml`
- `android/extracted/apktool/V4A_App_0.6.2/smali/o1/t.smali`
- `android/extracted/module/common/files/libv4a_re_*.so`

Linux wrapper/GUI compatibility sources:

- `Viper4LinuxV1/libgstviperfx.so`
- `Viper4LinuxV2/viper4linux/audio.conf.template`
- `Viper4LinuxV2/viper`
- `Viper4LinuxV2-GUI/misc/converter.cpp`
- `docker_gst_inspect_viperfx.txt`

Runtime validation command used:

- `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`
