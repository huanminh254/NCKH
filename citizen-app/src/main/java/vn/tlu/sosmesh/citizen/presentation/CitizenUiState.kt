package vn.tlu.sosmesh.citizen.presentation

import vn.tlu.sosmesh.citizen.data.location.LocationAddress
import vn.tlu.sosmesh.citizen.data.location.LocationSnapshot

enum class LocationStatus {
    IDLE,
    PERMISSION_REQUIRED,
    GPS_DISABLED,
    ACQUIRING,
    READY,
    STALE,
    ACCURACY_WEAK,
    ERROR
}

enum class AddressStatus {
    IDLE,
    LOADING,
    READY,
    ERROR
}

data class CitizenUiState(
    val status: LocationStatus = LocationStatus.IDLE,
    val message: String = "Định vị: Chưa bắt đầu",
    val location: LocationSnapshot? = null,
    val addressStatus: AddressStatus = AddressStatus.IDLE,
    val address: LocationAddress? = null,
    val addressMessage: String = "Địa chỉ: chưa xác định"
)
