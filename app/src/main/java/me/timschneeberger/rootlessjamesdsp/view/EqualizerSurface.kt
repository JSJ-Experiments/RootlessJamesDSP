package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.util.AttributeSet
import me.timschneeberger.rootlessjamesdsp.interop.JdspImpResToolbox

class EqualizerSurface(context: Context?, attrs: AttributeSet?) : BaseEqualizerSurface(context, attrs, 15, 20.0, 22000.0, -12.0, 12.0, 3.0f) {
    enum class Mode {
        Fir,
        Iir
    }

    var mode: Mode = Mode.Fir
        set(value) {
            field = value
            invalidate()
        }
    var iirOrder: Int = 4
        set(value) {
            field = value
            invalidate()
        }
    var interpolationMode: Int = 0
        set(value) {
            field = if (value == 1) 1 else 0
            invalidate()
        }
    var sampleRate: Int = 48000
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }
    var isViperOriginalMode: Boolean = false
        set(value) {
            field = value
            visibleBands = if (value) VIPER_ORIGINAL_FULL_SCALE.size else SCALE.size
            invalidate()
        }

    private val cplxRe = DoubleArray(nPts)
    private val cplxIm = DoubleArray(nPts)

    override fun computeCurve(
        freqs: DoubleArray,
        gains: DoubleArray,
        resolution: Int,
        dispFreq: DoubleArray,
        response: FloatArray
    ) {
        when(mode) {
            Mode.Fir -> JdspImpResToolbox.ComputeEqResponse(freqs.size, freqs, gains, interpolationMode, resolution, dispFreq, response)
            Mode.Iir -> {
                if (isViperOriginalMode) {
                    JdspImpResToolbox.ComputeViperOriginalEqResponse(sampleRate, interpolationMode, freqs, gains, resolution, dispFreq, response)
                } else {
                    JdspImpResToolbox.ComputeIIREqualizerCplx(sampleRate, iirOrder, freqs, gains, resolution, dispFreq, cplxRe, cplxIm)
                    JdspImpResToolbox.ComputeIIREqualizerResponse(nPts, cplxRe, cplxIm, response)
                }
            }
        }
    }

    override val frequencyScale: DoubleArray
        get() = if (isViperOriginalMode) VIPER_ORIGINAL_FULL_SCALE else SCALE

    companion object {
        val SCALE = doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0)
        val VIPER_ORIGINAL_SCALE = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val VIPER_ORIGINAL_EXTRA_SCALE = doubleArrayOf(17000.0, 18000.0, 19000.0, 20000.0, 22000.0)
        val VIPER_ORIGINAL_FULL_SCALE = VIPER_ORIGINAL_SCALE + VIPER_ORIGINAL_EXTRA_SCALE
    }
}
