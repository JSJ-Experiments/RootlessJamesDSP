# ViPER Clarity Reverse Engineering Notes

This document describes ViPER Clarity as implemented in this repo's reverse-engineered core (`ViPERFX_RE`), with enough detail to reimplement it in another engine (VST/AU/LV2, offline DSP, native mobile, etc.).

> Publication status: Ready for engineering publication
> Last updated: 2026-02-22
> Intended audience: DSP and audio-engine developers implementing compatibility behavior

## How to use this document

- Treat sections 2 through 8 as the normative implementation contract.
- Treat compatibility and converter sections as integration guidance, not core DSP math.
- Use section 11 as the direct file lookup index.

## 1) Scope and Confidence

- Primary implementation source: `ViPERFX_RE` (reverse-engineered C++).
- Cross-check sources:
  - Viper4Linux UI/config ranges (`Viper4LinuxV2-GUI`, `Viper4LinuxV2`).
  - GStreamer wrapper runtime behavior (`gst-inspect-1.0`) in amd64 Docker against `Viper4LinuxV1/libgstviperfx.so`.
- Note: this is reverse engineering, not original vendor source.

## 2) External Parameter Contract

### Parameter IDs

- `PARAM_FIDELITY_CLARITY_ENABLE = 65578`
- `PARAM_FIDELITY_CLARITY_MODE = 65579`
- `PARAM_FIDELITY_CLARITY_GAIN = 65580`
- Source: `ViPERFX_RE/src/ViPER4Android.h:78`

### Runtime mapping in DSP core

- `enable`: nonzero -> `SetEnable(true)`
- `mode`: raw integer cast to enum
- `gain`: mapped as:
  - `g = gain_int / 100.0f`
  - Source: `ViPERFX_RE/src/viper/ViPER.cpp:354`

### Mode enum mapping

- `0 = NATURAL`
- `1 = OZONE` (UI label: `OZone+`)
- `2 = XHIFI`
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.h:12`, `Viper4LinuxV2-GUI/viper_window.ui:360`

### Observed/control ranges

- Linux plugin/UI expose:
  - `vc_mode: 0..2`
  - `vc_level: 0..800`
  - Source: `Viper4LinuxV2-GUI/viper_window.ui:391`, `docker_gst_inspect_viperfx.txt`
- `audio.conf.template` provides defaults (`vc_mode=0`, `vc_level=0`), not runtime bounds.
- Converter `toAndroid(...)` pre-processing clamps:
  - `vc_level` to `<= 450`
  - Source: `Viper4LinuxV2-GUI/misc/converter.cpp:313`
  - This clamp applies before mode-specific export branching, so it affects both `officialV4A` and `teamDeWittV4A`.
- So effective internal `g` range depends on path:
  - UI/plugin path `0.0..8.0`
  - export-compatibility path commonly `0.0..4.5`
  - core dispatch itself is unclamped (`g = val1 / 100.0f`), so direct callers can send out-of-range values.

## 3) Processing Context / Buffer Contract

- Clarity `Process(float* samples, uint32_t size)` receives **interleaved stereo float** data.
- `size` is frame count; sample array length is `size * 2`.
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:20`

## 4) Position in Full ViPER Chain

Clarity runs after ViPER Bass and before Cure:

- `... -> dynamicSystem -> viperBass -> viperClarity -> cure -> ...`
- Source: `ViPERFX_RE/src/viper/ViPER.cpp:157`

This matters if you are trying to recreate "preset-identical" behavior in another host.

## 5) Internal State and Lifecycle

### Internal state

`ViPERClarity` contains:

- `NoiseSharpening noiseSharpening`
- `HighShelf highShelf[2]` (L/R)
- `HiFi hifi`
- `bool enable`
- `ClarityMode processMode`
- `uint32_t samplingRate`
- `float clarityGainPercent` (this doc calls it `g`)
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.h:28`

### Defaults

- `enable = false`
- `mode = NATURAL`
- `g = 0.0`
- `samplingRate = 44100`
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:13`, `ViPERFX_RE/src/viper/constants.h:28`

### Reset behavior (important for audible parity)

- Enabling effect triggers `Reset()`.
- Changing mode triggers `Reset()`.
- Changing sample rate triggers `Reset()`.
- Changing gain:
  - If mode is `OZONE`: calls `Reset()`
  - Else: updates gains without full reset.
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:54`

## 6) Shared Gain Mapping

When `g` is set:

- Natural block gain = `g`
- OZone+ shelf linear gain factor = `g + 1.0`
- XHiFi clarity gain factor = `g + 1.0`
- Source: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:63`

## 7) Mode Algorithms

### 7.1 Natural Mode (`NATURAL`)

Implementation is in `NoiseSharpening`.

Per frame/channel:

1. Compute first difference:
   - `diff = (x[n] - x[n-1]) * g`
2. Add back:
   - `x_in = x[n] + diff`
3. Run first-order BW LPF:
   - cutoff `fc = sr/2 - 1000`
4. Output filtered sample.

Source:

- Main loop: `ViPERFX_RE/src/viper/utils/NoiseSharpening.cpp:10`
- LPF setup: `ViPERFX_RE/src/viper/utils/NoiseSharpening.cpp:37`

### Exact first-order filter form used

The 1st-order BW LPF coefficient setup:

- `omega2 = pi * fc / sr`
- `t = tan(omega2)`
- `a1 = (1 - t) / (1 + t)`
- `b0 = t / (1 + t)`
- `b1 = b0`

Per-sample recursion implementation (as used inline in `NoiseSharpening`):

- `hist = x * b1`
- `y = prev + x * b0`
- `prev = x * a1 + hist`

Important note:

- `NoiseSharpening` uses this inline recurrence directly.
- It is **not** identical to `do_filter(IIR_1st*, ...)` in `IIR_1st.h`, which updates with `prev = y * a1 + hist`.

Source:

- LPF coeffs: `ViPERFX_RE/src/viper/utils/IIR_1st.cpp:76`
- Recurrence used by Clarity Natural path: `ViPERFX_RE/src/viper/utils/NoiseSharpening.cpp:24`
- Generic helper recurrence for comparison: `ViPERFX_RE/src/viper/utils/IIR_1st.h:31`

### 7.2 OZone+ Mode (`OZONE`)

Runs a biquad high-shelf independently on each channel:

- Shelf corner frequency: `8250 Hz`
- Gain factor passed in as `g + 1.0` then converted to dB in setter:
  - `gain_dB = 20 * log10(g + 1.0)`

Source:

- Mode process: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:28`
- Frequency set on reset: `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:46`
- Gain conversion: `ViPERFX_RE/src/viper/utils/HighShelf.cpp:17`

### Exact biquad coefficient math

Given `fs`, `f0`, and shelf gain:

- `x = 2*pi*f0/fs`
- `sinX = sin(x)`
- `cosX = cos(x)`
- `y = exp((gain_dB * ln(10))/40)`
- `z = sqrt(2*y) * sinX`
- `a = (y - 1)*cosX`
- `b = (y + 1) - a`
- `c = z + b`
- `d = (y + 1)*cosX`
- `e = (y + 1) + a`
- `f = (y - 1) - d`

Store:

- `A0 = 1/c`
- `A1 = 2*f`
- `A2 = b - z`
- `B0 = (e + z) * y`
- `B1 = -2*y*((y - 1) + d)`
- `B2 = (e - z) * y`

Processing:

- `out = ((x1*B1 + x0*B0 + B2*x2) - y1*A1 - A2*y2) * A0`

Source: `ViPERFX_RE/src/viper/utils/HighShelf.cpp:21`

### 7.3 XHiFi Mode (`XHIFI`)

Runs a 3-way split with weighted recombination plus band delays.

### Filters per channel

- Lowpass: order 1, cutoff 120 Hz
- Highpass: order 3, cutoff 1200 Hz
- Bandpass: order 3 made as LP(1200) then HP(120)

Source:

- Topology/alloc: `ViPERFX_RE/src/viper/utils/HiFi.cpp:7`
- Setup: `ViPERFX_RE/src/viper/utils/HiFi.cpp:55`
- BP implementation: `ViPERFX_RE/src/viper/utils/IIR_NOrder_BW_BP.cpp:21`
- N-order LH: `ViPERFX_RE/src/viper/utils/IIR_NOrder_BW_LH.cpp:18`

### Delay lines

Two stereo `WaveBuffer`s are used:

- BP path prefills with `sr/400` zeros (about 2.5 ms)
- LP path prefills with `sr/200` zeros (about 5 ms)

Source:

- Prefill: `ViPERFX_RE/src/viper/utils/HiFi.cpp:64`
- Buffer semantics: `ViPERFX_RE/src/viper/utils/WaveBuffer.cpp:90`

### Mix equation

For each sample after split:

- `hp = HP(x) * (g + 1) * 1.2`
- `bp = delayedBP(x) * (g + 1)`
- `lp = delayedLP(x)` (unity)
- `out = hp + bp + lp`

Source: `ViPERFX_RE/src/viper/utils/HiFi.cpp:45`

## 8) Reimplementation Pseudocode

```cpp
enum ClarityMode { NATURAL = 0, OZONE = 1, XHIFI = 2 };

struct ViPERClarity {
  bool enabled = false;
  ClarityMode mode = NATURAL;
  uint32_t fs = 44100;
  float g = 0.0f; // PARAM 65580 / 100

  NoiseSharpening nat;
  HighShelf shelfL, shelfR;
  HiFi xhifi;

  void reset() {
    nat.setSamplingRate(fs);
    nat.reset();

    setClarityToFilters(); // uses g

    shelfL.setFrequency(8250.0f);
    shelfR.setFrequency(8250.0f);
    shelfL.setSamplingRate(fs);
    shelfR.setSamplingRate(fs);

    xhifi.setSamplingRate(fs);
    xhifi.reset();
  }

  void setClarity(float newG) {
    g = newG;
    if (mode == OZONE) reset();
    else setClarityToFilters();
  }

  void setClarityToFilters() {
    nat.setGain(g);
    shelfL.setGain(g + 1.0f);
    shelfR.setGain(g + 1.0f);
    xhifi.setClarity(g + 1.0f);
  }

  void process(float* interleavedStereo, uint32_t frames) {
    if (!enabled) return;
    switch (mode) {
      case NATURAL: nat.process(interleavedStereo, frames); break;
      case OZONE:
        for (uint32_t i = 0; i < frames * 2; i += 2) {
          interleavedStereo[i]     = (float)shelfL.process(interleavedStereo[i]);
          interleavedStereo[i + 1] = (float)shelfR.process(interleavedStereo[i + 1]);
        }
        break;
      case XHIFI: xhifi.process(interleavedStereo, frames); break;
    }
  }
};
```

## 9) Practical Notes for Porting

- Input/output expected stereo interleaved; if your host is planar, adapt indexing.
- There is no explicit clipper inside Clarity; clipping can occur at high `g` (global limiter is a later stage in full chain).
- Core command mapping does not clamp `mode` or `g`; for OZone+, `g + 1.0` must remain `> 0` because gain uses `log10(g + 1.0)`.
- `ViPERClarity::Process` has no `default` branch in its mode switch; invalid mode values can effectively bypass processing.
- OZone+ path intentionally resets filter history on gain update; if you skip this, automation behavior will differ.
- XHiFi delays are sample-rate-dependent integer sample delays (`sr/400`, `sr/200`), not fixed milliseconds.
- Converter caveat: `teamDeWittV4A` export currently writes key `66580` for clarity level, while import mapping expects `65580`.

## 10) Validation Checklist

To verify a reimplementation:

1. Set `mode=Natural`, `g=0`: output should be close to near-Nyquist LPF of input.
2. Increase `g` in Natural: attack/transient emphasis should increase, but audibility is content-dependent.
3. Set `mode=OZone+`, compare low vs high-frequency sine gain; highs should rise with `g`.
4. Set `mode=XHiFi`, feed impulse: observe an immediate HP component plus delayed components around 2.5 ms and 5 ms.
5. Confirm mode switch and SR switch both clear state (history).

## 11) Reference Index

- Clarity class and mode switch:
  - `ViPERFX_RE/src/viper/effects/ViPERClarity.h:12`
  - `ViPERFX_RE/src/viper/effects/ViPERClarity.cpp:20`
- Gain/mode/enable command mapping:
  - `ViPERFX_RE/src/viper/ViPER.cpp:354`
  - `ViPERFX_RE/src/ViPER4Android.h:78`
- Processing order in full chain:
  - `ViPERFX_RE/src/viper/ViPER.cpp:157`
- Natural mode (`NoiseSharpening`) and LPF details:
  - `ViPERFX_RE/src/viper/utils/NoiseSharpening.cpp:10`
  - `ViPERFX_RE/src/viper/utils/IIR_1st.cpp:76`
  - `ViPERFX_RE/src/viper/utils/NoiseSharpening.cpp:24`
  - `ViPERFX_RE/src/viper/utils/IIR_1st.h:31`
- OZone+ high-shelf:
  - `ViPERFX_RE/src/viper/utils/HighShelf.cpp:4`
- XHiFi topology:
  - `ViPERFX_RE/src/viper/utils/HiFi.cpp:25`
  - `ViPERFX_RE/src/viper/utils/IIR_NOrder_BW_LH.cpp:18`
  - `ViPERFX_RE/src/viper/utils/IIR_NOrder_BW_BP.cpp:21`
  - `ViPERFX_RE/src/viper/utils/WaveBuffer.cpp:90`
- Linux UI/config defaults/ranges and labels:
  - `Viper4LinuxV2-GUI/viper_window.ui:360`
  - `Viper4LinuxV2-GUI/viper_window.ui:391`
  - `Viper4LinuxV2/viper4linux/audio.conf.template:41`
  - `Viper4LinuxV2-GUI/viper_window.cpp:1358`
  - `Viper4LinuxV2-GUI/misc/common.h:29`
  - `Viper4LinuxV2-GUI/misc/converter.cpp:313`
  - `Viper4LinuxV2-GUI/misc/converter.cpp:252`
  - `Viper4LinuxV2-GUI/misc/converter.cpp:432`
  - `docker_gst_inspect_viperfx.txt`

### Runtime validation command used

- `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`
  - Runtime output confirms `vc-level` range `0..800`, default `0`.
  - The same runtime output shows `vc-mode` range `0..2`, default `0`.
  - Saved output artifact: `docker_gst_inspect_viperfx.txt`.
