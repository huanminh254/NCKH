package vn.tlu.sosmesh.citizen.data.location

interface LocationRepository {
    fun start(
        onLocation: (LocationSnapshot) -> Unit,
        onError: (LocationError) -> Unit,
        useLastKnown: Boolean
    )

    fun stop()
}

sealed interface LocationError {
    data object PermissionDenied : LocationError
    data object GpsDisabled : LocationError
    data object Timeout : LocationError
    data class Failed(val reason: String) : LocationError
}
