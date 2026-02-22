# RootlessJamesDSP DSP Reverse-Engineering Docs

This directory contains publish-ready implementation docs for ViPERFX behavior reconstruction and compatibility work in RootlessJamesDSP.

## Document Index

| Document | Scope | Status | Last Updated |
| --- | --- | --- | --- |
| [`FIELD_SURROUND.md`](FIELD_SURROUND.md) | Field Surround (`ColorfulMusic`) algorithm, parameter contract, wrapper compatibility | Ready for engineering publication | 2026-02-22 |
| [`CLARITY_RE.md`](CLARITY_RE.md) | ViPER Clarity modes (`NATURAL`, `OZONE`, `XHIFI`), math, lifecycle behavior | Ready for engineering publication | 2026-02-22 |
| [`UPDATED_SPECTRUM_EXT.md`](UPDATED_SPECTRUM_EXT.md) | Spectrum Extension (`VSE`) DSP contract, app mapping, portability caveats | Ready for engineering publication | 2026-02-22 |
| [`FIR_EQ.md`](FIR_EQ.md) | FIR-named API mapped to minimum-phase IIR EQ implementation and transport behavior | Ready for engineering publication | 2026-02-22 |

## Recommended Reading Order

1. `UPDATED_SPECTRUM_EXT.md`
2. `FIR_EQ.md`
3. `FIELD_SURROUND.md`
4. `CLARITY_RE.md`

This order follows the main signal-chain progression and minimizes cross-reference jumps.

## Source Precedence

When details conflict, use this precedence:

1. `ViPERFX_RE` C++ implementation and command dispatch.
2. Runtime/binary validation artifacts (`Viper4LinuxV1/libgstviperfx.so`, `docker_gst_inspect_viperfx.txt`, extracted module binaries).
3. UI/config compatibility layers (`Viper4LinuxV2`, `Viper4LinuxV2-GUI`, Android resource/smali mappings).

## Documentation Standards Used

- Every document includes publication metadata, implementation guidance, and a dedicated reference index.
- Claims are traceable to concrete file paths and, where relevant, line-level references.
- Behavior marked as compatibility guidance is separated from normative DSP behavior.

## Maintenance Notes

- Keep `Last updated` dates in ISO format (`YYYY-MM-DD`).
- Add new references directly in each document's reference index section.
- Preserve backward-compatibility caveats and portability notes unless replaced by stronger primary evidence.
