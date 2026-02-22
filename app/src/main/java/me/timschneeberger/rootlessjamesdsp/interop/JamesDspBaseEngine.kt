package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import kotlin.math.roundToInt
import kotlin.math.pow

abstract class JamesDspBaseEngine(val context: Context, val callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : AutoCloseable {
    companion object {
        private const val CROSSFEED_MODE_DEFAULT = 5
        private const val CROSSFEED_MODE_CUSTOM = 99
        private const val CROSSFEED_FCUT_MIN = 300
        private const val CROSSFEED_FCUT_MAX = 2000
        private const val CROSSFEED_FEED_MIN = 10
        private const val CROSSFEED_FEED_MAX = 150
        private const val SPECTRUM_STRENGTH_UNIT_PERCENT = "percent"
        private const val SPECTRUM_STRENGTH_UNIT_DB = "db"
        private const val SPECTRUM_STRENGTH_DB_MIN = -40.0f
        private const val SPECTRUM_STRENGTH_DB_MAX = 0.0f
        private const val SPECTRUM_STRENGTH_PERCENT_MIN = 0.0f
        private const val SPECTRUM_STRENGTH_PERCENT_MAX = 100.0f
        private const val SPECTRUM_HARMONICS_DEFAULT_RAW = "0.02;0;0.02;0;0.02;0;0.02;0;0.02;0"
        private const val MAX_EQ_INTERPOLATION_MODE = 1
        private val DEFAULT_SPECTRUM_HARMONICS = doubleArrayOf(0.02, 0.0, 0.02, 0.0, 0.02, 0.0, 0.02, 0.0, 0.02, 0.0)
    }

    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    protected val cache = PreferenceCache(context)

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        syncMutex.withLock {
            fun parseIntPref(raw: String, fallback: Int, label: String): Int {
                return raw.toIntOrNull() ?: run {
                    Timber.w("Invalid integer preference for %s: '%s'. Falling back to %d.", label, raw, fallback)
                    fallback
                }
            }

            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, 0f)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)

            cache.select(Constants.PREF_COMPANDER)
            val compEnabled = cache.get(R.string.key_compander_enable, false)
            val compTimeConst = cache.get(R.string.key_compander_timeconstant, 0.22f)
            val compGranularity = cache.get(R.string.key_compander_granularity, 2f).toInt()
            val compTfTransforms = parseIntPref(
                cache.get(R.string.key_compander_tftransforms, "0"),
                0,
                "compander_tftransforms"
            )
            val compResponse = cache.get(R.string.key_compander_response, "95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0")

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "6").toIntOrNull() ?: 6
            val eqInterpolationMode = (cache.get(R.string.key_eq_interpolation, "0").toIntOrNull() ?: 0)
                .coerceIn(0, MAX_EQ_INTERPOLATION_MODE)
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
            val geqEnabled = cache.get(R.string.key_geq_enable, false)
            val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = parseIntPref(
                cache.get(R.string.key_reverb_preset, "15"),
                15,
                "reverb_preset"
            )

            cache.select(Constants.PREF_SPECTRUM_EXT)
            val spectrumEnabled = cache.get(R.string.key_spectrum_ext_enable, false)
            val spectrumStrengthUnit = cache.get(R.string.key_spectrum_ext_strength_unit, SPECTRUM_STRENGTH_UNIT_PERCENT)
            val spectrumStrengthPercent = cache.get(R.string.key_spectrum_ext_strength_percent, 10f)
            val spectrumStrengthDb = cache.get(R.string.key_spectrum_ext_strength_db, -20f)
            val spectrumRefFreq = cache.get(R.string.key_spectrum_ext_ref_freq, 7600f).toInt()
            val spectrumWetMix = cache.get(R.string.key_spectrum_ext_wet_mix, 100f)
            val spectrumPostGain = cache.get(R.string.key_spectrum_ext_post_gain, 0f)
            val spectrumSafety = cache.get(R.string.key_spectrum_ext_safety, false)
            val spectrumHpQ = cache.get(R.string.key_spectrum_ext_hp_q, 0.717f)
            val spectrumLpQ = cache.get(R.string.key_spectrum_ext_lp_q, 0.717f)
            val spectrumLpOffset = cache.get(R.string.key_spectrum_ext_lp_offset, 2000f).toInt()
            val spectrumHarmonics = cache.get(R.string.key_spectrum_ext_harmonics, SPECTRUM_HARMONICS_DEFAULT_RAW)

            cache.select(Constants.PREF_CLARITY)
            val clarityEnabled = cache.get(R.string.key_clarity_enable, false)
            val clarityMode = parseIntPref(
                cache.get(R.string.key_clarity_mode, "0"),
                0,
                "clarity_mode"
            ).coerceIn(0, 2)
            val clarityStrengthUnit = cache.get(R.string.key_clarity_strength_unit, "percent")
            val clarityStrengthPercent = cache.get(R.string.key_clarity_strength_percent, 0f)
            val clarityStrengthDb = cache.get(R.string.key_clarity_strength_db, -40f)
            val clarityPostGain = cache.get(R.string.key_clarity_post_gain, 0f)
            val claritySafety = cache.get(R.string.key_clarity_safety, false)
            val claritySafetyThreshold = cache.get(R.string.key_clarity_safety_threshold, -0.8f)
            val claritySafetyRelease = cache.get(R.string.key_clarity_safety_release, 60f)
            val clarityNaturalLpfOffset = cache.get(R.string.key_clarity_natural_lpf_offset, 1000f).toInt()
            val clarityOzoneFreq = cache.get(R.string.key_clarity_ozone_frequency, 8250f).toInt()
            val clarityXhifiLowCut = cache.get(R.string.key_clarity_xhifi_low_cut, 120f).toInt()
            val clarityXhifiHighCut = cache.get(R.string.key_clarity_xhifi_high_cut, 1200f).toInt()
            val clarityXhifiHpMix = cache.get(R.string.key_clarity_xhifi_hp_mix, 1.2f)
            val clarityXhifiBpMix = cache.get(R.string.key_clarity_xhifi_bp_mix, 1.0f)
            val clarityXhifiBpDelayDivisor = cache.get(R.string.key_clarity_xhifi_bp_delay_divisor, 400f).toInt()
            val clarityXhifiLpDelayDivisor = cache.get(R.string.key_clarity_xhifi_lp_delay_divisor, 200f).toInt()

            cache.select(Constants.PREF_FIELD_SURROUND)
            val fieldSurroundEnabled = cache.get(R.string.key_field_surround_enable, false)
            val fieldSurroundOutputMode = parseIntPref(
                cache.get(R.string.key_field_surround_output_mode, "0"),
                0,
                "field_surround_output_mode"
            ).coerceIn(0, 2)
            val fieldSurroundWidening = cache.get(
                R.string.key_field_surround_widening,
                FieldSurroundDepthMapping.CONFIG_DEFAULT_WIDENING.toFloat()
            ).roundToInt()
            val fieldSurroundMidImage = cache.get(
                R.string.key_field_surround_mid_image,
                FieldSurroundDepthMapping.CONFIG_DEFAULT_MID_IMAGE.toFloat()
            ).roundToInt()
            val fieldSurroundDepth = cache.get(
                R.string.key_field_surround_depth,
                FieldSurroundDepthMapping.CONFIG_DEFAULT_DEPTH.toFloat()
            ).roundToInt()
            val fieldSurroundPhaseOffset = cache.get(R.string.key_field_surround_phase_offset, 0f).roundToInt()
            val fieldSurroundMonoSumMix = cache.get(R.string.key_field_surround_mono_sum_mix, 0f).roundToInt()
            val fieldSurroundMonoSumPan = cache.get(R.string.key_field_surround_mono_sum_pan, 0f).roundToInt()
            val fieldSurroundDelayLeftMs = cache.get(R.string.key_field_surround_delay_left_ms, 20f)
            val fieldSurroundDelayRightMs = cache.get(R.string.key_field_surround_delay_right_ms, 14f)
            val fieldSurroundHpfFrequencyHz = cache.get(R.string.key_field_surround_hpf_frequency_hz, 800f)
            val fieldSurroundHpfGainDb = cache.get(R.string.key_field_surround_hpf_gain_db, -11f)
            val fieldSurroundHpfQ = cache.get(R.string.key_field_surround_hpf_q, 0.72f)
            val fieldSurroundBranchThreshold = cache.get(R.string.key_field_surround_branch_threshold, 500f).roundToInt()
            val fieldSurroundGainScaleDb = cache.get(R.string.key_field_surround_gain_scale_db, 10f)
            val fieldSurroundGainOffsetDb = cache.get(R.string.key_field_surround_gain_offset_db, -15f)
            val fieldSurroundGainCap = cache.get(R.string.key_field_surround_gain_cap, 1.0f)
            val fieldSurroundStereoFloor = cache.get(R.string.key_field_surround_stereo_floor, 2.0f)
            val fieldSurroundStereoFallback = cache.get(R.string.key_field_surround_stereo_fallback, 0.5f)

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = parseIntPref(
                cache.get(R.string.key_crossfeed_mode, CROSSFEED_MODE_DEFAULT.toString()),
                CROSSFEED_MODE_DEFAULT,
                "crossfeed_mode"
            )
            val crossfeedCustomFcut = cache.get(R.string.key_crossfeed_custom_fcut, 700f).roundToInt()
            val crossfeedCustomFeed = cache.get(R.string.key_crossfeed_custom_feed, 45f).roundToInt()

            cache.select(Constants.PREF_TUBE)
            val tubeEnabled = cache.get(R.string.key_tube_enable, false)
            val tubeDrive = cache.get(R.string.key_tube_drive, 2f)

            cache.select(Constants.PREF_DDC)
            val ddcEnabled = cache.get(R.string.key_ddc_enable, false)
            val ddcFile = cache.get(R.string.key_ddc_file, "")

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = parseIntPref(
                cache.get(R.string.key_convolver_mode, "0"),
                0,
                "convolver_mode"
            )

            val targets = cache.changedNamespaces.toTypedArray() + (forceUpdateNamespaces ?: arrayOf())
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain)
                    Constants.PREF_COMPANDER -> setCompander(compEnabled, compTimeConst, compGranularity, compTfTransforms, compResponse)
                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)
                    Constants.PREF_EQ -> setMultiEqualizer(eqEnabled, eqFilterType, eqInterpolationMode, eqBands)
                    Constants.PREF_GEQ -> setGraphicEq(geqEnabled, geqBands)
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_SPECTRUM_EXT -> setSpectrumExtension(
                        spectrumEnabled,
                        spectrumStrengthUnit,
                        spectrumStrengthPercent,
                        spectrumStrengthDb,
                        spectrumRefFreq,
                        spectrumWetMix,
                        spectrumPostGain,
                        spectrumSafety,
                        spectrumHpQ,
                        spectrumLpQ,
                        spectrumLpOffset,
                        spectrumHarmonics
                    )
                    Constants.PREF_CLARITY -> setClarity(
                        clarityEnabled,
                        clarityMode,
                        clarityStrengthUnit,
                        clarityStrengthPercent,
                        clarityStrengthDb,
                        clarityPostGain,
                        claritySafety,
                        claritySafetyThreshold,
                        claritySafetyRelease,
                        clarityNaturalLpfOffset,
                        clarityOzoneFreq,
                        clarityXhifiLowCut,
                        clarityXhifiHighCut,
                        clarityXhifiHpMix,
                        clarityXhifiBpMix,
                        clarityXhifiBpDelayDivisor,
                        clarityXhifiLpDelayDivisor
                    )
                    Constants.PREF_FIELD_SURROUND -> setFieldSurround(
                        fieldSurroundEnabled,
                        fieldSurroundOutputMode,
                        fieldSurroundWidening,
                        fieldSurroundMidImage,
                        fieldSurroundDepth,
                        fieldSurroundPhaseOffset,
                        fieldSurroundMonoSumMix,
                        fieldSurroundMonoSumPan,
                        fieldSurroundDelayLeftMs,
                        fieldSurroundDelayRightMs,
                        fieldSurroundHpfFrequencyHz,
                        fieldSurroundHpfGainDb,
                        fieldSurroundHpfQ,
                        fieldSurroundBranchThreshold,
                        fieldSurroundGainScaleDb,
                        fieldSurroundGainOffsetDb,
                        fieldSurroundGainCap,
                        fieldSurroundStereoFloor,
                        fieldSurroundStereoFallback
                    )
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> {
                        if (crossfeedMode == CROSSFEED_MODE_CUSTOM && supportsCustomCrossfeed()) {
                            setCrossfeedCustom(
                                crossfeedEnabled,
                                crossfeedCustomFcut.coerceIn(CROSSFEED_FCUT_MIN, CROSSFEED_FCUT_MAX),
                                crossfeedCustomFeed.coerceIn(CROSSFEED_FEED_MIN, CROSSFEED_FEED_MAX)
                            )
                        } else {
                            val safeMode = if (crossfeedMode == CROSSFEED_MODE_CUSTOM) {
                                CROSSFEED_MODE_DEFAULT
                            } else {
                                crossfeedMode
                            }
                            setCrossfeed(crossfeedEnabled, safeMode)
                        }
                    }
                    Constants.PREF_TUBE -> setVacuumTube(tubeEnabled, tubeDrive)
                    Constants.PREF_DDC -> setVdc(ddcEnabled, ddcFile)
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile)
                    Constants.PREF_CONVOLVER -> setConvolver(convolverEnabled, convolverFile, convolverMode, convolverAdvImp)
                    else -> true
                }

                if(!result) {
                    Timber.e("Failed to apply $it")
                }
            }

            cache.markChangesAsCommitted()
            Timber.i("Preferences synchronized")
        }
    }

    fun setMultiEqualizer(enable: Boolean, filterType: Int, interpolationMode: Int, bands: String): Boolean
    {
        // Transport divergence note:
        // ViPER-compatible remotes may expose 65551/65552 discrete writes, while the local path
        // applies the equivalent EQ state via a single JNI payload call.
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        if (array.size != doubleArray.size)
        {
            Timber.e("setMultiEqualizer: malformed EQ string, expected ${doubleArray.size} fields but got ${array.size}")
            return false
        }
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setMultiEqualizer: malformed EQ string")
                return false
            }
            if (!number.isFinite()) {
                Timber.e("setMultiEqualizer: non-finite value in EQ string at index $i")
                return false
            }
            doubleArray[i] = number
        }

        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(14)
        val array = bands.split(";")
        if (array.size != doubleArray.size)
        {
            Timber.e("setCompander: malformed string, expected ${doubleArray.size} fields but got ${array.size}")
            return false
        }
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setCompander: malformed string")
                return false
            }
            doubleArray[i] = number
        }

        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, vdcPath)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setVdc: file does not exist")
            setVdcInternal(false, "")
            return true /* non-critical */
        }

        return safeFileReader(fullPath)?.use {
            setVdcInternal(enable, it.readText())
        } ?: false
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean
    {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)

        // Handle disabled state before everything else
        if(!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            return true
        }

        val advConv = waveEditStr.split(";")
        val advSetting = IntArray(6)
        advSetting.fill(0)
        advSetting[0] = -80
        advSetting[1] = -100
        try
        {
            if (advConv.size == 6)
            {
                for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
            }
            else {
                Timber.w("setConvolver: AdvImp setting has the wrong size (${advConv.size})")
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            }
        }
        catch(ex: NumberFormatException) {
            Timber.e("setConvolver: NumberFormatException while parsing AdvImp setting. Using defaults.")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        val info = IntArray(4)
        val imp = JdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            sampleRate.toInt(),
            info,
            optimizationMode,
            advSetting
        )

        if(imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if(info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if(info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2])
    }

    fun setGraphicEq(enable: Boolean, bands: String): Boolean
    {
        // Sanity check
        if(!bands.contains("GraphicEQ:", true)) {
            Timber.e("setGraphicEq: malformed string")
            setGraphicEqInternal(false, "")
            return false
        }

        return setGraphicEqInternal(enable, bands)
    }

    fun setLiveprog(enable: Boolean, path: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            setLiveprogInternal(enable, name, it.readText())
        } ?: false
    }

    fun setSpectrumExtension(
        enable: Boolean,
        strengthUnit: String,
        strengthPercent: Float,
        strengthDb: Float,
        referenceFreq: Int,
        wetMixPercent: Float,
        postGainDb: Float,
        safetyEnabled: Boolean,
        hpQ: Float,
        lpQ: Float,
        lpCutoffOffsetHz: Int,
        harmonicsRaw: String,
    ): Boolean {
        val uiStrength = if (strengthUnit == SPECTRUM_STRENGTH_UNIT_DB) {
            val clampedDb = strengthDb.coerceIn(SPECTRUM_STRENGTH_DB_MIN, SPECTRUM_STRENGTH_DB_MAX)
            // Map the minimum db value to full-off for stable round-tripping with percent mode.
            if (clampedDb <= SPECTRUM_STRENGTH_DB_MIN) {
                0.0f
            } else {
                10.0.pow((clampedDb / 20.0f).toDouble()).toFloat() * SPECTRUM_STRENGTH_PERCENT_MAX
            }
        } else {
            strengthPercent
        }.coerceIn(SPECTRUM_STRENGTH_PERCENT_MIN, SPECTRUM_STRENGTH_PERCENT_MAX)

        // Stock ViPER mapping: param65550 = trunc(strength * 5.6), core uses /100.
        // The local JNI path applies Spectrum params as a single native call instead of discrete
        // 65549/65550 transport commands, so we preserve the same strength math here for parity.
        val barkReconstruct = (uiStrength * 5.6f).toInt()
        val exciter = barkReconstruct / 100.0f
        val harmonics = parseSpectrumHarmonics(harmonicsRaw)

        return setSpectrumExtensionInternal(
            enable,
            exciter,
            referenceFreq,
            wetMixPercent.coerceIn(0f, 100f) / 100.0f,
            postGainDb.coerceIn(-24f, 24f),
            safetyEnabled,
            hpQ.coerceIn(0.1f, 3.0f),
            lpQ.coerceIn(0.1f, 3.0f),
            lpCutoffOffsetHz.coerceAtLeast(0),
            harmonics
        )
    }

    fun setClarity(
        enable: Boolean,
        mode: Int,
        strengthUnit: String,
        strengthPercent: Float,
        strengthDb: Float,
        postGainDb: Float,
        safetyEnabled: Boolean,
        safetyThresholdDb: Float,
        safetyReleaseMs: Float,
        naturalLpfOffsetHz: Int,
        ozoneFreqHz: Int,
        xhifiLowCutHz: Int,
        xhifiHighCutHz: Int,
        xhifiHpMix: Float,
        xhifiBpMix: Float,
        xhifiBpDelayDivisor: Int,
        xhifiLpDelayDivisor: Int,
    ): Boolean {
        val linearStrength = if (strengthUnit == "db") {
            if (strengthDb.isFinite()) {
                10.0.pow((strengthDb / 20.0f).toDouble()).toFloat()
            } else {
                0.0f
            }
        } else {
            if (strengthPercent.isFinite()) {
                strengthPercent / 100.0f
            } else {
                0.0f
            }
        }
        val safeLinearStrength = if (linearStrength.isFinite()) linearStrength else 0.0f

        return setClarityInternal(
            enable,
            mode,
            safeLinearStrength,
            postGainDb.coerceIn(-24f, 16f),
            safetyEnabled,
            safetyThresholdDb.coerceIn(-12f, 0f),
            safetyReleaseMs.coerceIn(1.5f, 500f),
            naturalLpfOffsetHz.coerceIn(200, 5000),
            ozoneFreqHz.coerceIn(2000, 16000),
            xhifiLowCutHz.coerceIn(40, 400),
            xhifiHighCutHz.coerceIn(400, 6000),
            xhifiHpMix.coerceIn(0f, 2.5f),
            xhifiBpMix.coerceIn(0f, 2.5f),
            xhifiBpDelayDivisor.coerceIn(80, 1200),
            xhifiLpDelayDivisor.coerceIn(80, 1200)
        )
    }

    fun setFieldSurround(
        enable: Boolean,
        outputMode: Int,
        widening: Int,
        midImage: Int,
        depth: Int,
        phaseOffset: Int,
        monoSumMix: Int,
        monoSumPan: Int,
        delayLeftMs: Float,
        delayRightMs: Float,
        hpfFrequencyHz: Float,
        hpfGainDb: Float,
        hpfQ: Float,
        branchThreshold: Int,
        gainScaleDb: Float,
        gainOffsetDb: Float,
        gainCap: Float,
        stereoFloor: Float,
        stereoFallback: Float,
    ): Boolean {
        val depthStrength = FieldSurroundDepthMapping.toDirectDepthStrength(depth)
        return setFieldSurroundMappedDepth(
            enable,
            outputMode,
            widening,
            midImage,
            depthStrength,
            phaseOffset,
            monoSumMix,
            monoSumPan,
            delayLeftMs,
            delayRightMs,
            hpfFrequencyHz,
            hpfGainDb,
            hpfQ,
            branchThreshold,
            gainScaleDb,
            gainOffsetDb,
            gainCap,
            stereoFloor,
            stereoFallback
        )
    }

    fun setFieldSurroundWrapperCompat(
        enable: Boolean,
        outputMode: Int,
        widening: Int,
        midImage: Int,
        depthRaw: Int,
        phaseOffset: Int,
        monoSumMix: Int,
        monoSumPan: Int,
        delayLeftMs: Float,
        delayRightMs: Float,
        hpfFrequencyHz: Float,
        hpfGainDb: Float,
        hpfQ: Float,
        branchThreshold: Int,
        gainScaleDb: Float,
        gainOffsetDb: Float,
        gainCap: Float,
        stereoFloor: Float,
        stereoFallback: Float,
    ): Boolean {
        val depthStrength = FieldSurroundDepthMapping.toWrapperCompatDepthStrength(depthRaw)
        return setFieldSurroundMappedDepth(
            enable,
            outputMode,
            widening,
            midImage,
            depthStrength,
            phaseOffset,
            monoSumMix,
            monoSumPan,
            delayLeftMs,
            delayRightMs,
            hpfFrequencyHz,
            hpfGainDb,
            hpfQ,
            branchThreshold,
            gainScaleDb,
            gainOffsetDb,
            gainCap,
            stereoFloor,
            stereoFallback
        )
    }

    private fun setFieldSurroundMappedDepth(
        enable: Boolean,
        outputMode: Int,
        widening: Int,
        midImage: Int,
        depthStrength: Int,
        phaseOffset: Int,
        monoSumMix: Int,
        monoSumPan: Int,
        delayLeftMs: Float,
        delayRightMs: Float,
        hpfFrequencyHz: Float,
        hpfGainDb: Float,
        hpfQ: Float,
        branchThreshold: Int,
        gainScaleDb: Float,
        gainOffsetDb: Float,
        gainCap: Float,
        stereoFloor: Float,
        stereoFallback: Float,
    ): Boolean {
        val clampedDepthStrength = depthStrength.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return setFieldSurroundInternal(
            enable,
            outputMode.coerceIn(0, 2),
            widening.coerceIn(0, 800),
            midImage.coerceIn(0, 800),
            clampedDepthStrength,
            phaseOffset.coerceIn(-100, 100),
            monoSumMix.coerceIn(0, 100),
            monoSumPan.coerceIn(-100, 100),
            delayLeftMs.coerceIn(1.0f, 100.0f),
            delayRightMs.coerceIn(1.0f, 100.0f),
            hpfFrequencyHz.coerceIn(20.0f, 4000.0f),
            hpfGainDb.coerceIn(-30.0f, 12.0f),
            hpfQ.coerceIn(0.1f, 3.0f),
            branchThreshold.coerceIn(0, 2000),
            gainScaleDb.coerceIn(0.0f, 30.0f),
            gainOffsetDb.coerceIn(-40.0f, 20.0f),
            gainCap.coerceIn(0.1f, 2.0f),
            stereoFloor.coerceIn(0.1f, 5.0f),
            stereoFallback.coerceIn(0.1f, 2.0f)
        )
    }

    private fun parseSpectrumHarmonics(raw: String): DoubleArray {
        val parts = raw.split(";")
        if (parts.size != DEFAULT_SPECTRUM_HARMONICS.size) {
            Timber.w("setSpectrumExtension: malformed harmonics string, expected ${DEFAULT_SPECTRUM_HARMONICS.size} entries")
            return DEFAULT_SPECTRUM_HARMONICS.copyOf()
        }

        val parsed = DoubleArray(DEFAULT_SPECTRUM_HARMONICS.size)
        for (i in parts.indices) {
            val value = parts[i].trim().toDoubleOrNull()
            if (value == null || !value.isFinite()) {
                Timber.w("setSpectrumExtension: malformed harmonics string, using defaults")
                return DEFAULT_SPECTRUM_HARMONICS.copyOf()
            }
            parsed[i] = value
        }

        return parsed
    }

    private fun safeFileReader(path: String) =
        try { FileReader(path) }
        catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // Effect config
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    protected abstract fun setFieldSurroundInternal(
        enable: Boolean,
        outputMode: Int,
        widening: Int,
        midImage: Int,
        depth: Int,
        phaseOffset: Int,
        monoSumMix: Int,
        monoSumPan: Int,
        delayLeftMs: Float,
        delayRightMs: Float,
        hpfFrequencyHz: Float,
        hpfGainDb: Float,
        hpfQ: Float,
        branchThreshold: Int,
        gainScaleDb: Float,
        gainOffsetDb: Float,
        gainCap: Float,
        stereoFloor: Float,
        stereoFallback: Float,
    ): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Float): Boolean

    protected abstract fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    protected abstract fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean
    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean
    protected abstract fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int): Boolean
    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean
    protected abstract fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean
    protected abstract fun setSpectrumExtensionInternal(
        enable: Boolean,
        strengthLinear: Float,
        referenceFreq: Int,
        wetMix: Float,
        postGainDb: Float,
        safetyEnabled: Boolean,
        hpQ: Float,
        lpQ: Float,
        lpCutoffOffsetHz: Int,
        harmonics: DoubleArray,
    ): Boolean
    protected abstract fun setClarityInternal(
        enable: Boolean,
        mode: Int,
        gain: Float,
        postGainDb: Float,
        safetyEnabled: Boolean,
        safetyThresholdDb: Float,
        safetyReleaseMs: Float,
        naturalLpfOffsetHz: Int,
        ozoneFreqHz: Int,
        xhifiLowCutHz: Int,
        xhifiHighCutHz: Int,
        xhifiHpMix: Float,
        xhifiBpMix: Float,
        xhifiBpDelayDivisor: Int,
        xhifiLpDelayDivisor: Int,
    ): Boolean

    // Feature support
    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    // EEL VM utilities
    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
}
