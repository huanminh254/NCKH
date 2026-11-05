package vn.tlu.sosmesh.coremap.mesh

object SosLocationPolicy {
    fun isUsable(
        latitude: Double, longitude: Double, accuracyMeters: Float,
        timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()
    ): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters in 0f..100f &&
        nowMillis - timestampMillis in 0L..120_000L
}
