package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            JamesDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        val oldHandle = handle
        handle = 0

        // Make sure ongoing async calls to native have enough time to finish
        Timer().schedule(100) {
            JamesDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return JamesDspWrapper.setLimiter(handle, threshold, release) and JamesDspWrapper.setPostGain(handle, postGain)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return JamesDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return JamesDspWrapper.setBassBoost(handle, enable, maxGain)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
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
        return JamesDspWrapper.setFieldSurround(
            handle,
            enable,
            outputMode,
            widening,
            midImage,
            depth,
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

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setVacuumTube(handle, enable, level)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return JamesDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return JamesDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return JamesDspWrapper.setGraphicEq(handle, enable, bands)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprog(handle, enable, name, script)
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
        return JamesDspWrapper.setSpectrumExtension(
            handle,
            enable,
            strengthLinear,
            referenceFreq,
            wetMix,
            postGainDb,
            safetyEnabled,
            hpQ,
            lpQ,
            lpCutoffOffsetHz,
            harmonics
        )
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
        return JamesDspWrapper.setClarity(
            handle,
            enable,
            mode,
            gain,
            postGainDb,
            safetyEnabled,
            safetyThresholdDb,
            safetyReleaseMs,
            naturalLpfOffsetHz,
            ozoneFreqHz,
            xhifiLowCutHz,
            xhifiHighCutHz,
            xhifiHpMix,
            xhifiBpMix,
            xhifiBpDelayDivisor,
            xhifiLpDelayDivisor
        )
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return JamesDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return JamesDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        JamesDspWrapper.freezeLiveprogExecution(handle, freeze)
    }
}
