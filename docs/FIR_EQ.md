# ViPER "FIR Equalizer" RE Notes (for RootlessJamesDSP)

> Publication status: Ready for engineering publication
> Last updated: 2026-02-22
> Intended audience: DSP and audio-engine developers implementing compatibility behavior

## How to use this document

- Treat sections 1 through 4 as the normative behavior contract.
- Treat section 5 as implementation guidance for RootlessJamesDSP integration.
- Use section 7 for direct source-file lookup.

## TL;DR

In this codebase, the feature named **FIR equalizer** is implemented as a **minimum-phase IIR filter bank**, not a linear-phase FIR convolution EQ.

The control API still uses FIR naming:
- `PARAM_FIR_EQUALIZER_ENABLE` = `65551`
- `PARAM_FIR_EQUALIZER_BAND_LEVEL` = `65552`

But those commands are wired to `IIRFilter`:
- `ViPERFX_RE/src/viper/ViPER.cpp:246`
- `ViPERFX_RE/src/viper/ViPER.cpp:250`

---

## 1) Control Path and Value Encoding

### Linux wrapper / GUI side

- Config keys are `eq_enable`, `eq_band1`..`eq_band10` in `audio.conf` style:
  - `Viper4LinuxV2/viper4linux/audio.conf.template:10`
  - `Viper4LinuxV2/viper4linux/audio.conf.template:20`
- GStreamer wrapper passes these as properties (`eq-enable`, `eq-band1`..`eq-band10`):
  - `Viper4LinuxV2/viper:45`
- GUI stores band values as `int(dB * 100)`:
  - `Viper4LinuxV2-GUI/viper_window.cpp:1215`
  - `Viper4LinuxV2-GUI/viper_window.cpp:1224`
- GUI drag/key interaction is bounded to `[-12, +12] dB`:
  - `Viper4LinuxV2-GUI/dialog/liquidequalizerwidget.h:20`
  - `Viper4LinuxV2-GUI/dialog/liquidequalizerwidget.h:21`
  - note: core DSP/import paths do not hard-clamp to this range (`IIRFilter::SetBandLevel` accepts wider values):
    - `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:69`

### Plugin command mapping

From `libgstviperfx.so`:
- `eq-enable` sends command `0x1000F` (`65551`)
- each `eq-bandN` sends command `0x10010` (`65552`) with two values:
  - `val1 = band index (0..9)`
  - `val2 = gain in centi-dB` (e.g. `+600` => `+6.00 dB`)

Binary evidence:
- Command used:
  - `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`
- Runtime `gst-inspect-1.0` (amd64 Docker) shows `eq-band1..10` range `-1200..1200`.
- `objdump` on `gst_viperfx_set_property` shows `viperfx_command_set_px4_vx4x2(..., 0x10010, bandIndex, gain)`.
- Saved runtime output artifact: `docker_gst_inspect_viperfx.txt`.

### Core dispatch

In ViPER core:
- enable command:
  - `iirFilter.SetEnable(val1 != 0)` at `ViPERFX_RE/src/viper/ViPER.cpp:247`
- band command:
  - `iirFilter.SetBandLevel(val1, val2 / 100.0f)` at `ViPERFX_RE/src/viper/ViPER.cpp:251`

So the final DSP input is **dB float** per band.

---

## 2) Band Map

10-band mode center frequencies are:

`[31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000] Hz`

Defined in:
- `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp:7`

`eq_band1` maps to index `0`, `eq_band10` maps to index `9` (from wrapper dispatch behavior).

---

## 3) DSP Core (Actual Algorithm)

Implementation files:
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp`
- `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp`

### 3.1 Gain mapping per band

`SetBandLevel()`:

```cpp
linear = pow(10.0, dB / 20.0);
bandGain = linear * 0.636;
```

Source:
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:72`
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:73`

Note: `0.636` is approximately `2/pi` (inference).

### 3.2 Coefficient generation

`MinPhaseIIRCoeffs::UpdateCoeffs()` computes 3 usable coeffs per band (stored in a stride of 4).

For 10-band mode:
- bandwidth parameter is `tmp = 1.0` octave
  - `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp:170`

Core math:
1. `f1 = fc / 2^(tmp/2)`
2. normalized angles with `fs`:
   - `x = 2*pi*fc/fs`
   - `y = 2*pi*f1/fs`
3. derive intermediate terms, solve a quadratic root (`SolveRoot`), then:
   - `c0 = 2*r`
   - `c1 = 0.5 - r`
   - `c2 = 2*(r + 0.5)*cos(x)`

Sources:
- `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp:154`
- `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp:207`

### 3.3 Per-sample processing equation

For each sample frame and each channel, output is a sum of band outputs:

`out[n] = sum_k (g_k * y_k[n])`

with per-band recurrence equivalent to:

`y_k[n] = c2_k*y_k[n-1] + c1_k*(x[n] - x[n-2]) - c0_k*y_k[n-2]`

where:
- `x[n]` is the current channel input sample (same input fed to every band)
- `g_k` is `bandLevelsWithQ[k]`
- `c0,c1,c2` come from `MinPhaseIIRCoeffs`

Relevant code:
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:31`
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:36`
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:44`
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:54`

### 3.4 State/reset behavior

- Enable transition to `true` resets filter state:
  - `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:76`
- Sampling-rate change regenerates coeffs and resets state:
  - `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:85`
- Default band gains start at flat-ish multiplier `0.636`:
  - `ViPERFX_RE/src/viper/effects/IIRFilter.cpp:18`

---

## 4) Processing Order in ViPER Chain

In `ViPER::process`, this EQ runs after DDC + Spectrum Extend, before ColorfulMusic:

- `viperDdc.Process(...)`
- `spectrumExtend.Process(...)`
- `iirFilter.Process(...)`

Source:
- `ViPERFX_RE/src/viper/ViPER.cpp:146`
- `ViPERFX_RE/src/viper/ViPER.cpp:149`

---

## 5) Reimplementation Guidance for RootlessJamesDSP

If your target is behavior compatibility, implement it as the same 10-band IIR bank:

1. Expose 10 gains in dB; for V2-UI parity use `[-12, +12]`, but accept centi-dB transport wider than that if needed.
2. Map to linear band gains with:
   - `g_k = 0.636 * 10^(dB_k/20)`
3. Use center freqs:
   - `31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k`
4. Recompute coeffs on sample-rate changes using `MinPhaseIIRCoeffs` math.
5. Process each channel independently with the recurrence above and sum bands.
6. Reset all band states on enable `false -> true` and on SR changes.

Suggested transport compatibility:
- `65551`: bool enable
- `65552`: `(bandIndex, gainCentiDb)`

---

## 6) Important Caveats

1. Name mismatch is real: API says FIR, DSP path here is IIR.
2. `ViPERFX_RE` is reverse-engineered; keep this as "best current model", not legal/source-of-truth vendor code.
3. Linux wrapper plugin `libgstviperfx.so` dynamically loads an external core via `dlopen`/`dlsym`; this artifact does not expose a clean literal `libviperfx.so` string (it includes `HClibviperfx.so` in rodata), and the core library is not included in this repo.

---

## 7) Reference Index

Primary DSP implementation sources:

- `ViPERFX_RE/src/ViPER4Android.h`
- `ViPERFX_RE/src/viper/ViPER.cpp`
- `ViPERFX_RE/src/viper/effects/IIRFilter.cpp`
- `ViPERFX_RE/src/viper/utils/MinPhaseIIRCoeffs.cpp`

Wrapper/UI compatibility sources:

- `Viper4LinuxV2/viper4linux/audio.conf.template`
- `Viper4LinuxV2/viper`
- `Viper4LinuxV2-GUI/viper_window.cpp`
- `Viper4LinuxV2-GUI/dialog/liquidequalizerwidget.h`

Binary/runtime validation artifacts:

- `Viper4LinuxV1/libgstviperfx.so`
- `docker_gst_inspect_viperfx.txt`

Runtime validation command used:

- `sudo docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work debian:bookworm bash -lc 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-plugins-base && GST_PLUGIN_PATH=/work/Viper4LinuxV1 gst-inspect-1.0 viperfx'`
