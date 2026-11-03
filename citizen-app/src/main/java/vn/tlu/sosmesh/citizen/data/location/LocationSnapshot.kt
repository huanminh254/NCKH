package vn.tlu.sosmesh.citizen.data.location

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val provider: String,
    val isLastKnown: Boolean,
    val satellitesUsedInFix: Int = 0
)
