# ViPER Field Surround Reverse Engineering Notes

This document describes Field Surround (`ColorfulMusic`) as implemented in this workspace, with enough detail to reimplement it in RootlessJamesDSP or another DSP engine.

> Publication status: Ready for engineering publication
> Last updated: 2026-02-22
> Intended audience: DSP and audio-engine developers implementing compatibility behavior

## How to use this document

- Treat all equations and state transitions as implementation requirements for behavior parity.
- Treat all UI/plugin ranges as ecosystem compatibility guidance unless explicitly stated as core DSP behavior.
- Use the reference index at the end for direct source-file lookup.

## 1) Scope and Confidence

- Primary implementation source: reverse-engineered core in `ViPERFX_RE`.
- Cross-check sources:
  - Linux wrapper/UI ranges and presets in `Viper4LinuxV2` and `Viper4LinuxV2-GUI`.
  - Legacy Linux GStreamer plugin behavior in `Viper4LinuxV1/libgstviperfx.so` (runtime `gst-inspect-1.0` in amd64 Docker, plus symbols/disassembly).
- Note: this is reverse engineering, not original vendor source.

## 2) External Parameter Contract

### Parameter IDs

- `PARAM_FIELD_SURROUND_ENABLE = 65553` (`0x10011`)
- `PARAM_FIELD_SURROUND_WIDENING = 65554` (`0x10012`)
- `PARAM_FIELD_SURROUND_MID_IMAGE = 65555` (`0x10013`)
- `PARAM_FIELD_SURROUND_DEPTH = 65556` (`0x10014`)
- Source: `ViPERFX_RE/src/ViPER4Android.h:47`

### Runtime Mapping in DSP Core

In `ViPER::DispatchCommand`:

- `65553` -> `colorfulMusic.SetEnable(val1 != 0)`
- `65554` -> `colorfulMusic.SetWidenValue(val1 / 100.0f)`
- `65555` -> `colorfulMusic.SetMidImageValue(val1 / 100.0f)`
- `65556` -> `colorfulMusic.SetDepthValue((short)val1)`
- Source: `ViPERFX_RE/src/viper/ViPER.cpp:254`

So the core contract is:

- `widen_float = widening_int / 100`
- `mid_float = midimage_int / 100`
- `depth_short = depth_int` (no scaling in core dispatch)

## 3) Processing Context and Chain Position

- Buffer format: interleaved stereo `float`.
- `size` is frame count (array length is `size * 2`).
- Field Surround runs in main chain before Differential Surround:
  - `... -> iirFilter -> colorfulMusic -> diffSurround -> ...`
- Source: `ViPERFX_RE/src/viper/ViPER.cpp:145`

## 4) Top-Level Effect Structure (`ColorfulMusic`)

`ColorfulMusic` is a two-stage effect:

1. `DepthSurround`
2. `Stereo3DSurround`

Order is fixed and in-place:

```cpp
if (enabled) {
  depthSurround.Process(samples, size);
  stereo3dSurround.Process(samples, size);
}
```

Source: `ViPERFX_RE/src/viper/effects/ColorfulMusic.cpp:12`

### Core defaults

At construction time:

- `enabled = false`
- `samplingRate = 44100`
- `stereoWiden = 0.0` (identity when `midImage=1.0`)
- `depth strength = 0` (depth stage disabled)

Sources:

- `ViPERFX_RE/src/viper/effects/ColorfulMusic.cpp:4`
- `ViPERFX_RE/src/viper/utils/Stereo3DSurround.cpp:3`
- `ViPERFX_RE/src/viper/constants.h:28`

### Lifecycle behavior

- Enable toggled `false -> true` calls `Reset()` (depth state reset).
- Enable toggled `true -> false` does not reset.
- Sampling-rate changes reconfigure depth stage only.
- Source: `ViPERFX_RE/src/viper/effects/ColorfulMusic.cpp:27`

## 5) Stage A: `DepthSurround` (Depth parameter)

### Internal state

- `strength` (short)
- `enabled` (`strength != 0`)
- `strengthAtLeast500` (branch selector)
- `gain` (derived from strength)
- `prev[2]` (cross-feedback states)
- `timeConstDelay[2]`
- `highpass` (`Biquad`)
- Source: `ViPERFX_RE/src/viper/utils/DepthSurround.cpp:5`

### Strength -> gain mapping

For `strength != 0`:

- `gain = min(1.0, 10^(((strength/1000)*10 - 15)/20))`
- equivalent form: `gain = min(1.0, 10^((strength - 1500)/2000))`

For `strength == 0`:

- `enabled = false`
- `gain = 0`

Threshold behavior:

- if `strength < 500`: first branch
- if `strength >= 500`: second branch (sign inversion in one feedback leg)

Source: `ViPERFX_RE/src/viper/utils/DepthSurround.cpp:57`

### Delay/filter setup

On `SetSamplingRate(sr)`:

- delay L: `0.020 s`
- delay R: `0.014 s`
- side high-pass: `SetHighPassParameter(800 Hz, sr, -11 dB, Q=0.72, 0.0)`
- resets `prev[]` to zero
- Source: `ViPERFX_RE/src/viper/utils/DepthSurround.cpp:71`

### Side high-pass coefficient math (exact RE implementation)

`DepthSurround` uses `Biquad::SetHighPassParameter` (not a textbook RBJ callsite). For exact parity, use this implementation:

- `omega = 2*pi*f/fs`
- `A = 10^(dbGain/40)` with `dbGain = -11`
- `sqrtA = sqrt(A)`
- `z = sin(omega)/2 * sqrt((1/A + A) * (1/Q - 1) + 2)` with `Q = 0.72`
- `a0 = (A+1) - (A-1)*cos(omega) + 2*sqrtA*z`
- `a1 = 2*((A-1) - (A+1)*cos(omega))`
- `a2 = (A+1) - (A-1)*cos(omega) - 2*sqrtA*z`
- `b0 = ((A+1) + (A-1)*cos(omega) + 2*sqrtA*z) * A * omega`
- `b1 = -2*A*((A-1) + (A+1)*cos(omega)) * omega`
- `b2 = ((A+1) + (A-1)*cos(omega) - 2*sqrtA*z) * A * omega`
- internal storage normalizes by `a0` and applies feedback sign inversion as in `SetCoeffs`.

Source: `ViPERFX_RE/src/viper/utils/Biquad.cpp:70`

### Per-frame processing equations

Given input frame `(xL, xR)` and current states `p0=prev[0]`, `p1=prev[1]`:

If `strength < 500`:

- `p0 = gain * D0(xL + p1)`
- `p1 = gain * D1(xR + p0)`

If `strength >= 500`:

- `p0 = gain * D0(xL + p1)`
- `p1 = -gain * D1(xR + p0)`

Then:

- `l = xL + p0`
- `r = xR + p1`
- `diff = 0.5 * (l - r)`
- `avg  = 0.5 * (l + r)`
- `hp = highpass(diff)`
- `yL = avg + (diff - hp)`
- `yR = avg - (diff - hp)`

Source: `ViPERFX_RE/src/viper/utils/DepthSurround.cpp:17`

Notes:

- `highpass` is mono on the side signal (`diff`), shared for both channels.
- The `>=500` branch flips the sign of one feedback path and audibly changes spatial character.

### Important RE caveat (`TimeConstDelay`)

`TimeConstDelay::ProcessSample()` in the current RE source uses:

- `offset = (uint32_t)modff(offset + 1, (float*)&sampleCount);`

with a `TODO` comment about correctness.

For reimplementation:

- treat the current `modff` path as the code-accurate RE behavior.
- `offset = (offset + 1) % sampleCount` is a robust cleanup option, but not confirmed as original-vendor canonical behavior.
- keep the configured delay lengths (`sr * 0.02`, `sr * 0.014`) either way.

Source: `ViPERFX_RE/src/viper/utils/TimeConstDelay.cpp:9`

## 6) Stage B: `Stereo3DSurround` (Widening + Mid Image)

This stage is a mid/side matrix with coefficients derived from `widen` and `midImage`.

### Coefficients

Given:

- `w = stereoWiden`
- `m = middleImage`
- `tmp = w + 1`
- `x = tmp + 1 = w + 2`
- `y = (x < 2) ? 0.5 : (1/x)`
- `coeffLeft = m * y`
- `coeffRight = tmp * y`

For common UI domain (`w >= 0`), this simplifies to:

- `coeffLeft = m / (w + 2)`
- `coeffRight = (w + 1) / (w + 2)`

Source: `ViPERFX_RE/src/viper/utils/Stereo3DSurround.cpp:43`

### Per-frame transform

Given input `(L, R)`:

- `a = coeffLeft * (L + R)`
- `b = coeffRight * (R - L)`
- `outL = a - b`
- `outR = a + b`

Equivalent matrix form:

- `outL = (coeffLeft + coeffRight)*L + (coeffLeft - coeffRight)*R`
- `outR = (coeffLeft - coeffRight)*L + (coeffLeft + coeffRight)*R`

Source: `ViPERFX_RE/src/viper/utils/Stereo3DSurround.cpp:17`

Identity condition:

- `w = 0` and `m = 1` gives `outL=L`, `outR=R`.

## 7) UI/Wrapper Value Ranges and Presets

### GUI ranges (Viper4LinuxV2-GUI)

- `colmwide`: `0..800`, widget default `100`
- `colmmidimg`: `0..800`, widget default `100`
- `colmdepth`: `0..800`, widget default `100`
- Source: `Viper4LinuxV2-GUI/viper_window.ui:2291`

Given core mapping, these become:

- `widen_float = 0.0 .. 8.0`
- `mid_float = 0.0 .. 8.0`
- `depth_short = 0 .. 800`

Effective startup defaults in V2 are config-driven, not purely UI-widget defaults:

- `colm_widening=100`
- `colm_depth=0`
- `colm_midimage` is not present in `audio.conf.template` (and not passed by the V2 launcher script).
- In GUI load path, missing `colm_midimage` is inserted as `0` by `ConfigContainer::getInt(...)`, so first-load effective mid-image tends to be `0` unless explicitly set in config.

Sources:

- `Viper4LinuxV2/viper4linux/audio.conf.template`
- `Viper4LinuxV2/viper`
- `Viper4LinuxV2-GUI/config/container.cpp`
- `Viper4LinuxV2-GUI/misc/common.h`
- `Viper4LinuxV2-GUI/viper_window.cpp`

### Preset table (`Colm`)

Presets map to `(widening, depth)` pairs:

- `Slight: (120, 200)`
- `Level 1..7: (130..190, 275..725)`
- `Extreme: (200, 800)`

Source: `Viper4LinuxV2-GUI/misc/presetprovider.cpp:128`

### Config keys and caveats

- Template includes `colm_enable`, `colm_widening`, `colm_depth`.
- `colm_midimage` is not present in `audio.conf.template`.
- Source: `Viper4LinuxV2/viper4linux/audio.conf.template:21`

The V2 launcher script also passes only:

- `colm-enable`, `colm-widening`, `colm-depth`

and omits `colm-midimage`.

Source: `Viper4LinuxV2/viper:45`

## 8) Legacy Plugin (`libgstviperfx.so`) Cross-Checks

Runtime verification command used:

- `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`

From Docker runtime `gst-inspect-1.0 viperfx` output:

- `colm-widening`: int `0..800`, default `100`
- `colm-midimage`: int `0..800`, default `100`
- `colm-depth`: int `0..32767`, default `0`
- Saved output artifact: `docker_gst_inspect_viperfx.txt`.

In plugin disassembly (`gst_viperfx_set_property`), the value sent to command `0x10014` is not raw property value. It is mapped:

- `depth_internal = clamp(int((depth_raw / 32767.0) * 600 + 200), 200, 800)`

Observed anchor points:

- `0 -> 200`
- `8192 -> 350`
- `16384 -> 500`
- `24576 -> 650`
- `32767 -> 800`

This aligns with common Field Surround preset depth values.

## 9) V4A XML Conversion Behavior

`converter.cpp` conversion rules:

- official format:
  - `colorfulmusic.coeffs` -> `colm_widening;colm_depth` (1:1)
  - `colorfulmusic.midimage` multiplies by `4` when importing to Linux config
- export does inverse (`colm_midimage / 4`)

Source:

- import: `Viper4LinuxV2-GUI/misc/converter.cpp:93`
- export: `Viper4LinuxV2-GUI/misc/converter.cpp:342`

So if you import/export presets between ecosystems, mind the `/4` mid-image convention.

## 10) Reimplementation Pseudocode (Core-Accurate)

```cpp
struct FieldSurround {
  bool enabled = false;
  uint32_t fs = 44100;

  // Parameters after core mapping:
  // widen = widening_int / 100
  // mid   = midimage_int / 100
  // depth = depth_int
  float widen = 0.0f;
  float mid = 1.0f;
  int depth = 0;

  DepthSurround depthStage;
  Stereo3DSurround stereoStage;

  void setWidenFromParamInt(int v) { widen = v / 100.0f; stereoStage.setStereoWiden(widen); }
  void setMidFromParamInt(int v)   { mid = v / 100.0f; stereoStage.setMiddleImage(mid); }
  void setDepthFromParamInt(int v) { depth = v; depthStage.setStrength((short)v); }

  // Optional plugin-compatible wrapper mapping:
  // depth_internal = clamp(int((raw/32767.0f)*600 + 200), 200, 800)

  void setSamplingRate(uint32_t sr) {
    fs = sr;
    depthStage.setSamplingRate(sr);
  }

  void setEnabled(bool en) {
    if (enabled != en) {
      if (!enabled) depthStage.setSamplingRate(fs); // matches ColorfulMusic::Reset path
      enabled = en;
    }
  }

  void process(float* interleavedLR, uint32_t frames) {
    if (!enabled) return;
    depthStage.process(interleavedLR, frames);
    stereoStage.process(interleavedLR, frames);
  }
};
```

## 11) Practical Guidance for RootlessJamesDSP

- If you are reusing ViPER command IDs and want parity with `ViPERFX_RE` core:
  - feed depth as the raw `short` parameter (`65556`), with no wrapper remap/clamp.
  - common control domains like `0..800` are ecosystem/UI conventions, not a core dispatch requirement.
- If you are emulating the old Linux GStreamer property interface:
  - accept depth `0..32767`, convert to `200..800` before stage update.
- Keep stage order exactly: `DepthSurround` then `Stereo3DSurround`.
- Preserve the `strength >= 500` sign-flip behavior; it is not cosmetic.

## 12) Reference Index

Primary DSP implementation sources:

- `ViPERFX_RE/src/ViPER4Android.h`
- `ViPERFX_RE/src/viper/ViPER.cpp`
- `ViPERFX_RE/src/viper/effects/ColorfulMusic.cpp`
- `ViPERFX_RE/src/viper/utils/DepthSurround.cpp`
- `ViPERFX_RE/src/viper/utils/Stereo3DSurround.cpp`
- `ViPERFX_RE/src/viper/utils/Biquad.cpp`
- `ViPERFX_RE/src/viper/utils/TimeConstDelay.cpp`
- `ViPERFX_RE/src/viper/constants.h`

UI/config/preset compatibility sources:

- `Viper4LinuxV2/viper4linux/audio.conf.template`
- `Viper4LinuxV2/viper`
- `Viper4LinuxV2-GUI/viper_window.ui`
- `Viper4LinuxV2-GUI/viper_window.cpp`
- `Viper4LinuxV2-GUI/config/container.cpp`
- `Viper4LinuxV2-GUI/misc/common.h`
- `Viper4LinuxV2-GUI/misc/converter.cpp`
- `Viper4LinuxV2-GUI/misc/presetprovider.cpp`

Binary/runtime validation artifacts:

- `Viper4LinuxV1/libgstviperfx.so`
- `docker_gst_inspect_viperfx.txt`

Runtime validation command used:

- `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`
