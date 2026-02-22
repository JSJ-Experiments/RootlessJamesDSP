package me.timschneeberger.rootlessjamesdsp.interop

internal object EqNormalization {
    private const val EQ_FIELDS = 30
    private val STANDARD_SCALE = doubleArrayOf(
        25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0
    )
    private val VIPER_SCALE = doubleArrayOf(
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )
    private val VIPER_EXT_SCALE = doubleArrayOf(
        17000.0, 18000.0, 19000.0, 20000.0, 22000.0
    )

    fun normalizeMultiEqBands(filterType: Int, bands: DoubleArray, viperOriginalFilterType: Int): DoubleArray {
        if (bands.size != EQ_FIELDS) {
            return bands
        }

        val normalized = bands.copyOf()
        when (filterType) {
            viperOriginalFilterType -> {
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
}
