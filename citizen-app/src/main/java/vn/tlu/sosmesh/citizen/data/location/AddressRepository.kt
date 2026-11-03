package vn.tlu.sosmesh.citizen.data.location

interface AddressRepository {
    suspend fun reverseGeocode(location: LocationSnapshot): AddressResult
}

sealed interface AddressResult {
    data class Success(val address: LocationAddress) : AddressResult
    data class Failed(val reason: String) : AddressResult
}
