package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import androidx.core.content.edit
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInt
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameter
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.crc
import me.timschneeberger.rootlessjamesdsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    var effect: AudioEffectHidden? = createEffect()
    private var isSpectrumExtensionSupported: Boolean? = null
    private var isSpectrumExtensionAdvancedSupported: Boolean? = null
    private var isClaritySupported: Boolean? = null
    private var isClarityAdvancedSupported: Boolean? = null
    private var isFieldSurroundSupported: Boolean? = null
    private var isFieldSurroundAdvancedSupported: Boolean? = null
    private var isEqViperOriginalModeSupported: Boolean? = null
    private var hasShownSpectrumExtensionUnsupportedToast = false
    private var hasShownSpectrumExtensionAdvancedUnsupportedToast = false
    private var hasShownClarityUnsupportedToast = false
    private var hasShownClarityAdvancedUnsupportedToast = false
    private var hasShownFieldSurroundUnsupportedToast = false
    private var hasShownFieldSurroundAdvancedUnsupportedToast = false

    override var enabled: Boolean
        set(value) { effect?.enabled = value }
        get() = effect?.enabled ?: false

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.e("Failed to create JamesDSP effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun checkEngine() {
        if (!isPidValid) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine probably crashed or detached.")
            context.toast("Engine crashed. Rebooting JamesDSP.", false)
            rebootEngine()
        }

        if (isSampleRateAbnormal) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine crashed.")
            context.toast("Abnormal sampling rate. Rebooting JamesDSP.", false)
            rebootEngine()
        }
    }

    private fun rebootEngine() {
        try {
            effect?.release()
            effect = createEffect()
            isSpectrumExtensionSupported = null
            isSpectrumExtensionAdvancedSupported = null
            isClaritySupported = null
            isClarityAdvancedSupported = null
            isFieldSurroundSupported = null
            isFieldSurroundAdvancedSupported = null
            isEqViperOriginalModeSupported = null
            hasShownSpectrumExtensionUnsupportedToast = false
            hasShownSpectrumExtensionAdvancedUnsupportedToast = false
            hasShownClarityUnsupportedToast = false
            hasShownClarityAdvancedUnsupportedToast = false
            hasShownFieldSurroundUnsupportedToast = false
            hasShownFieldSurroundAdvancedUnsupportedToast = false
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
            return
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if(effect == null) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        effect?.release()
        effect = null
        super.close()
    }

    private fun markFeatureUnsupported(
        featureName: String,
        errorCode: Int,
        enableRequested: Boolean,
        toastMessage: String,
        setSupported: (Boolean) -> Unit,
        hasShownToast: () -> Boolean,
        setHasShownToast: (Boolean) -> Unit,
    ): Boolean {
        setSupported(false)
        Timber.w("Remote plugin rejected $featureName command (error=$errorCode)")
        if (enableRequested && !hasShownToast()) {
            setHasShownToast(true)
            context.toast(toastMessage, false)
        }
        return false
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return (effect.setParameterFloatArray(
            115,
            floatArrayOf(timeConstant, granularity.toFloat(), tfTransforms.toFloat()) + bands.map { it.toFloat() }
        ) == AudioEffect.SUCCESS) and (effect.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(128, preset.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1203, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(188, mode.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1208, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(112, maxGain.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
    }

    protected override fun setFieldSurroundInternal(
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
        stereoFallback: Float
    ): Boolean {
        val markUnsupported = { errorCode: Int, enableRequested: Boolean ->
            markFeatureUnsupported(
                featureName = "Field Surround",
                errorCode = errorCode,
                enableRequested = enableRequested,
                toastMessage = "Field Surround requires a ViPER-compatible plugin version.",
                setSupported = { isFieldSurroundSupported = it },
                hasShownToast = { hasShownFieldSurroundUnsupportedToast },
                setHasShownToast = { hasShownFieldSurroundUnsupportedToast = it },
            )
        }

        if (isFieldSurroundSupported == false) {
            return markUnsupported(-1, enable)
        }

        if (enable) {
            val fallbackToLegacy = {
                val widenResult = effect.setParameter(PARAM_FIELD_SURROUND_WIDENING, widening.toShort())
                if (widenResult != AudioEffect.SUCCESS) {
                    markUnsupported(widenResult, true)
                } else {
                    val midResult = effect.setParameter(PARAM_FIELD_SURROUND_MID_IMAGE, midImage.toShort())
                    if (midResult != AudioEffect.SUCCESS) {
                        markUnsupported(midResult, true)
                    } else {
                        val depthResult = effect.setParameter(PARAM_FIELD_SURROUND_DEPTH, depth.toShort())
                        if (depthResult != AudioEffect.SUCCESS) {
                            markUnsupported(depthResult, true)
                        } else {
                            true
                        }
                    }
                }
            }

            if (isFieldSurroundAdvancedSupported != false) {
                val payload = floatArrayOf(
                    outputMode.toFloat(),
                    widening.toFloat(),
                    midImage.toFloat(),
                    depth.toFloat(),
                    phaseOffset.toFloat(),
                    monoSumMix.toFloat(),
                    monoSumPan.toFloat(),
                    delayLeftMs,
                    delayRightMs,
                    hpfFrequencyHz,
                    hpfGainDb,
                    hpfQ,
                    branchThreshold.toFloat(),
                    gainScaleDb,
                    gainOffsetDb,
                    gainCap,
                    stereoFloor,
                    stereoFallback
                )
                val payloadResult = effect.setParameterFloatArray(PARAM_FIELD_SURROUND, payload)
                if (payloadResult == AudioEffect.SUCCESS) {
                    isFieldSurroundAdvancedSupported = true
                } else {
                    Timber.w("Remote plugin rejected Field Surround payload (error=$payloadResult). Falling back to legacy protocol.")
                    isFieldSurroundAdvancedSupported = false
                    if (!hasShownFieldSurroundAdvancedUnsupportedToast) {
                        hasShownFieldSurroundAdvancedUnsupportedToast = true
                        context.toast(
                            "Advanced Field Surround controls are not supported by this plugin. Using basic mode.",
                            false
                        )
                    }
                    if (!fallbackToLegacy()) {
                        return false
                    }
                }
            } else {
                if (!fallbackToLegacy()) {
                    return false
                }
            }
        }

        if (enable || isFieldSurroundSupported == true) {
            val enableResult = effect.setParameter(PARAM_FIELD_SURROUND_ENABLE, enable.toShort())
            if (enableResult != AudioEffect.SUCCESS) {
                return markUnsupported(enableResult, enable)
            }
        }

        if (enable) {
            isFieldSurroundSupported = true
        }

        return true
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(150, (level * 1000).roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1206, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray,
    ): Boolean {
        var ret = true

        val applyMode = { mode: Int, normalizedBands: DoubleArray ->
            val properties = floatArrayOf(
                mode.toFloat(),
                if(interpolationMode == 1) 1.0f else -1.0f
            ) + normalizedBands.map { it.toFloat() }
            effect.setParameterFloatArray(116, properties) == AudioEffect.SUCCESS
        }

        if (enable) {
            val fallbackBands = normalizeMultiEqBands(EQ_FILTER_TYPE_FIR_MINIMUM, bands)
            if (filterType == EQ_FILTER_TYPE_VIPER_ORIGINAL && isEqViperOriginalModeSupported == false) {
                ret = applyMode(EQ_FILTER_TYPE_FIR_MINIMUM, fallbackBands)
                if (ret) {
                    persistEqFallbackMode(fallbackBands)
                }
            } else {
                val requestedBands = normalizeMultiEqBands(filterType, bands)
                ret = applyMode(filterType, requestedBands)
            }

            if (!ret && filterType == EQ_FILTER_TYPE_VIPER_ORIGINAL) {
                Timber.w("Remote plugin rejected EQ filter mode 6. Falling back to mode 0 for compatibility.")
                ret = applyMode(EQ_FILTER_TYPE_FIR_MINIMUM, fallbackBands)
                if (ret) {
                    isEqViperOriginalModeSupported = false
                    persistEqFallbackMode(fallbackBands)
                }
            } else if (ret && filterType == EQ_FILTER_TYPE_VIPER_ORIGINAL) {
                isEqViperOriginalModeSupported = true
            }
        }

        return ret and (effect.setParameter(1202, enable.toShort()) == AudioEffect.SUCCESS)
    }

    private fun normalizeMultiEqBands(filterType: Int, bands: DoubleArray): DoubleArray {
        if (bands.size != EQ_FIELDS) {
            return bands
        }

        val normalized = bands.copyOf()
        when (filterType) {
            EQ_FILTER_TYPE_VIPER_ORIGINAL -> {
                VIPER_SCALE.forEachIndexed { index, freq ->
                    normalized[index] = freq
                }
                VIPER_EXT_SCALE.forEachIndexed { offset, freq ->
                    normalized[VIPER_SCALE.size + offset] = freq
                }
            }
            else -> {
                STANDARD_SCALE.forEachIndexed { index, freq ->
                    normalized[index] = freq
                }
            }
        }

        return normalized
    }

    private fun persistEqFallbackMode(fallbackBands: DoubleArray) {
        val modeKey = context.getString(R.string.key_eq_filter_type)
        val bandsKey = context.getString(R.string.key_eq_bands)
        val fallbackMode = EQ_FILTER_TYPE_FIR_MINIMUM.toString()
        val fallbackBandsSerialized = fallbackBands.joinToString(";")
        val prefs = PreferenceCache.getPreferences(context, Constants.PREF_EQ)

        val currentMode = prefs.getString(modeKey, fallbackMode)
        val currentBands = prefs.getString(bandsKey, null)
        if (currentMode == fallbackMode && currentBands == fallbackBandsSerialized) {
            return
        }

        prefs.edit(commit = true) {
            putString(modeKey, fallbackMode)
            putString(bandsKey, fallbackBandsSerialized)
        }
        Timber.i("Persisted EQ fallback mode 0 for remote compatibility.")
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        val prevCrc = this.ddcHash
        val currentCrc = vdc.crc()

        Timber.i("VDC hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10009, vdc)
            effect.setParameter(25001, currentCrc) // Commit hash
        }

        return effect.setParameter(1212, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {

        val prevCrc = this.convolverHash

        Timber.i("Convolver hash before: $prevCrc, current: $irCrc")
        if (prevCrc != irCrc && enable) {
            effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels)
            effect.setParameter(25003, irCrc) // Commit hash
        }

        return effect.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        val prevCrc = this.graphicEqHash
        val currentCrc = bands.crc()

        Timber.i("GraphicEQ hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10006, bands)
            effect.setParameter(25000, currentCrc) // Commit hash
        }

        return effect.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        val prevCrc = this.liveprogHash
        val currentCrc = script.crc()

        Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
        if (prevCrc != currentCrc && enable) {
            effect.setParameterCharBuffer(12001, 10010, script)
            effect.setParameter(25002, currentCrc) // Commit hash
        }

        return effect.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setSpectrumExtensionInternal(
        enable: Boolean,
        strengthLinear: Float,
        referenceFreq: Int,
        wetMix: Float,
        postGainDb: Float,
        safetyEnabled: Boolean,
        hpQ: Float,
        lpQ: Float,
        lpCutoffOffsetHz: Int,
        harmonics: DoubleArray
    ): Boolean {
        val markUnsupported = { errorCode: Int, enableRequested: Boolean ->
            markFeatureUnsupported(
                featureName = "Spectrum Extension",
                errorCode = errorCode,
                enableRequested = enableRequested,
                toastMessage = "Spectrum Extension requires a ViPER-compatible plugin version.",
                setSupported = { isSpectrumExtensionSupported = it },
                hasShownToast = { hasShownSpectrumExtensionUnsupportedToast },
                setHasShownToast = { hasShownSpectrumExtensionUnsupportedToast = it },
            )
        }

        if (isSpectrumExtensionSupported == false) {
            return markUnsupported(-1, enable)
        }

        if (enable) {
            val fallbackToLegacy = {
                val referenceResult = effect.setParameter(PARAM_SPECTRUM_EXTENSION_BARK, referenceFreq)
                if (referenceResult != AudioEffect.SUCCESS) {
                    markUnsupported(referenceResult, true)
                } else {
                    val reconstructParam = (strengthLinear * 100.0f).roundToInt()
                    val barkReconstructResult = effect.setParameter(PARAM_SPECTRUM_EXTENSION_BARK_RECONSTRUCT, reconstructParam)
                    if (barkReconstructResult != AudioEffect.SUCCESS) {
                        markUnsupported(barkReconstructResult, true)
                    } else {
                        true
                    }
                }
            }

            val safeHarmonics = if (harmonics.size == EXPECTED_SPECTRUM_HARMONICS_COUNT) {
                harmonics
            } else {
                Timber.w("Spectrum Extension harmonics size mismatch: expected $EXPECTED_SPECTRUM_HARMONICS_COUNT, got ${harmonics.size}. Using defaults.")
                DEFAULT_SPECTRUM_HARMONICS
            }
            if (isSpectrumExtensionAdvancedSupported != false) {
                val payload = floatArrayOf(
                    strengthLinear,
                    referenceFreq.toFloat(),
                    wetMix,
                    postGainDb,
                    if (safetyEnabled) 1.0f else 0.0f,
                    hpQ,
                    lpQ,
                    lpCutoffOffsetHz.toFloat()
                ) + safeHarmonics.map { it.toFloat() }
                val payloadResult = effect.setParameterFloatArray(PARAM_SPECTRUM_EXTENSION, payload)

                if (payloadResult == AudioEffect.SUCCESS) {
                    isSpectrumExtensionAdvancedSupported = true
                } else {
                    Timber.w("Remote plugin rejected Spectrum Extension payload (error=$payloadResult). Falling back to legacy protocol.")
                    isSpectrumExtensionAdvancedSupported = false
                    if (!hasShownSpectrumExtensionAdvancedUnsupportedToast) {
                        hasShownSpectrumExtensionAdvancedUnsupportedToast = true
                        context.toast(
                            "Advanced Spectrum controls are not supported by this plugin. Using basic mode.",
                            false
                        )
                    }
                    if (!fallbackToLegacy()) {
                        return false
                    }
                }
            } else {
                if (!fallbackToLegacy()) {
                    return false
                }
            }
        }

        if (enable || isSpectrumExtensionSupported == true) {
            val enableValue = if (enable) 1 else 0
            val enableResult = effect.setParameter(PARAM_SPECTRUM_EXTENSION_ENABLE, enableValue)
            if (enableResult != AudioEffect.SUCCESS) {
                return markUnsupported(enableResult, enable)
            }
        }

        if (enable) {
            isSpectrumExtensionSupported = true
        }

        return true
    }

    override fun setClarityInternal(
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
        xhifiLpDelayDivisor: Int
    ): Boolean {
        val markUnsupported = { errorCode: Int, enableRequested: Boolean ->
            markFeatureUnsupported(
                featureName = "Clarity",
                errorCode = errorCode,
                enableRequested = enableRequested,
                toastMessage = "Clarity requires a ViPER-compatible plugin version.",
                setSupported = { isClaritySupported = it },
                hasShownToast = { hasShownClarityUnsupportedToast },
                setHasShownToast = { hasShownClarityUnsupportedToast = it },
            )
        }

        fun setClarityEnableCompat(value: Boolean): Int {
            val encoded = value.toShort()
            return if (isClarityAdvancedSupported == false) {
                val legacyResult = effect.setParameter(PARAM_CLARITY_ENABLE_VIPER, encoded)
                if (legacyResult == AudioEffect.SUCCESS) {
                    legacyResult
                } else {
                    effect.setParameter(PARAM_CLARITY_ENABLE, encoded)
                }
            } else {
                effect.setParameter(PARAM_CLARITY_ENABLE, encoded)
            }
        }

        if (isClaritySupported == false) {
            return markUnsupported(-1, enable)
        }

        if (enable) {
            val fallbackToLegacy = {
                val modeResult = effect.setParameter(PARAM_CLARITY_MODE, mode.toShort())
                if (modeResult != AudioEffect.SUCCESS) {
                    markUnsupported(modeResult, true)
                } else {
                    val gainParam = (gain * 100.0f).roundToInt()
                    val gainResult = effect.setParameter(PARAM_CLARITY_GAIN, gainParam)
                    if (gainResult != AudioEffect.SUCCESS) {
                        markUnsupported(gainResult, true)
                    } else {
                        true
                    }
                }
            }

            if (isClarityAdvancedSupported != false) {
                val payload = floatArrayOf(
                    mode.toFloat(),
                    gain,
                    postGainDb,
                    if (safetyEnabled) 1.0f else 0.0f,
                    safetyThresholdDb,
                    safetyReleaseMs,
                    naturalLpfOffsetHz.toFloat(),
                    ozoneFreqHz.toFloat(),
                    xhifiLowCutHz.toFloat(),
                    xhifiHighCutHz.toFloat(),
                    xhifiHpMix,
                    xhifiBpMix,
                    xhifiBpDelayDivisor.toFloat(),
                    xhifiLpDelayDivisor.toFloat()
                )
                val configResult = effect.setParameterFloatArray(PARAM_CLARITY, payload)
                if (configResult == AudioEffect.SUCCESS) {
                    isClarityAdvancedSupported = true
                } else {
                    Timber.w("Remote plugin rejected Clarity payload (error=$configResult). Falling back to legacy protocol.")
                    isClarityAdvancedSupported = false
                    if (!hasShownClarityAdvancedUnsupportedToast) {
                        hasShownClarityAdvancedUnsupportedToast = true
                        context.toast(
                            "Advanced Clarity controls are not supported by this plugin. Using basic mode.",
                            false
                        )
                    }
                    if (!fallbackToLegacy()) {
                        return false
                    }
                }
            } else {
                if (!fallbackToLegacy()) {
                    return false
                }
            }
        }

        if (enable || isClaritySupported == true) {
            val enableResult = setClarityEnableCompat(enable)
            if (enableResult != AudioEffect.SUCCESS) {
                return markUnsupported(enableResult, enable)
            }
        }

        if (enable) {
            isClaritySupported = true
        }

        return true
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
    val pid: Int
        get() = effect.getParameterInt(20002) ?: -1
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = effect.getParameterInt(19998) ?: -1
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = effect.getParameterInt(19999) ?: -1
    val allocatedBlockLength: Int
        get() = effect.getParameterInt(20000) ?: -1
    val graphicEqHash: Int
        get() = effect.getParameterInt(30000) ?: -1
    val ddcHash: Int
        get() = effect.getParameterInt(30001) ?: -1
    val liveprogHash: Int
        get() = effect.getParameterInt(30002) ?: -1
    val convolverHash: Int
        get() = effect.getParameterInt(30003) ?: -1

    enum class PluginState {
        Unavailable,
        Available,
        Unsupported
    }

    companion object {
        // ViPER-compatible Spectrum Extension protocol.
        private const val PARAM_SPECTRUM_EXTENSION_BARK = 65549
        private const val PARAM_SPECTRUM_EXTENSION_BARK_RECONSTRUCT = 65550
        private const val PARAM_SPECTRUM_EXTENSION = 117
        private const val PARAM_CLARITY = 118
        private const val PARAM_FIELD_SURROUND = 119
        private const val PARAM_SPECTRUM_EXTENSION_ENABLE = 65548
        private const val PARAM_CLARITY_ENABLE_VIPER = 65578
        private const val PARAM_CLARITY_MODE = 65579
        private const val PARAM_CLARITY_GAIN = 65580
        private const val PARAM_CLARITY_ENABLE = 1209
        private const val PARAM_FIELD_SURROUND_ENABLE = 65553
        private const val PARAM_FIELD_SURROUND_WIDENING = 65554
        private const val PARAM_FIELD_SURROUND_MID_IMAGE = 65555
        private const val PARAM_FIELD_SURROUND_DEPTH = 65556
        private const val EQ_FIELDS = 30
        private const val EQ_FILTER_TYPE_FIR_MINIMUM = 0
        private const val EQ_FILTER_TYPE_VIPER_ORIGINAL = 6
        private const val EXPECTED_SPECTRUM_HARMONICS_COUNT = 10
        private val DEFAULT_SPECTRUM_HARMONICS = doubleArrayOf(0.02, 0.0, 0.02, 0.0, 0.02, 0.0, 0.02, 0.0, 0.02, 0.0)
        private val STANDARD_SCALE = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0
        )
        private val VIPER_SCALE = doubleArrayOf(
            31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )
        private val VIPER_EXT_SCALE = doubleArrayOf(
            17000.0, 18000.0, 19000.0, 20000.0, 22000.0
        )

        private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
        private val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        fun isPluginInstalled(): PluginState {
            return try {
                AudioEffect
                    .queryEffects()
                    .orEmpty()
                    .firstOrNull { it.uuid == EFFECT_JAMESDSP }
                    ?.run {
                        if(name.contains("v3")) PluginState.Unsupported else PluginState.Available
                    } ?: PluginState.Unavailable
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                MainApplication.instance.showAlert(
                    "Error while checking audio effect status",
                    "Unexpected error while checking whether JamesDSP's audio effect library is installed. \n\n" +
                            "Error: $e",
                )
                PluginState.Unavailable
            }
        }
    }
}
