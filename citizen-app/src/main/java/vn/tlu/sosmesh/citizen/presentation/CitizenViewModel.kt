package vn.tlu.sosmesh.citizen.presentation

import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vn.tlu.sosmesh.citizen.data.location.AddressRepository
import vn.tlu.sosmesh.citizen.data.location.AddressResult
import vn.tlu.sosmesh.citizen.data.location.LocationError
import vn.tlu.sosmesh.citizen.data.location.LocationRepository
import vn.tlu.sosmesh.citizen.data.location.LocationSnapshot

class CitizenViewModel(
    private val locationRepository: LocationRepository,
    private val addressRepository: AddressRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CitizenUiState())
    val uiState: StateFlow<CitizenUiState> = _uiState.asStateFlow()
    private var addressJob: Job? = null
    private var precisionWindowJob: Job? = null
    private var lastAddressLocation: LocationSnapshot? = null
    private var bestLocation: LocationSnapshot? = null
    private var precisionWindowActive = false

    fun startLocation(useLastKnown: Boolean = true) {
        addressJob?.cancel()
        precisionWindowJob?.cancel()
        lastAddressLocation = null
        bestLocation = null
        precisionWindowActive = true
        val oldState = _uiState.value
        _uiState.value = oldState.copy(
            status = LocationStatus.ACQUIRING,
            message = if (useLastKnown) {
                "Đang lấy tọa độ GPS (Fused Location)..."
            } else {
                "Đang làm mới tọa độ GPS (Fused Location)..."
            },
            location = if (useLastKnown) oldState.location else null,
            addressStatus = if (useLastKnown) oldState.addressStatus else AddressStatus.IDLE,
            address = if (useLastKnown) oldState.address else null,
            addressMessage = if (useLastKnown) {
                oldState.addressMessage
            } else {
                "Địa chỉ: đang chờ tọa độ mới"
            }
        )

        locationRepository.start(
            onLocation = ::onLocation,
            onError = ::onError,
            useLastKnown = useLastKnown
        )

        precisionWindowJob = viewModelScope.launch {
            delay(PRECISION_WINDOW_MILLIS)
            precisionWindowActive = false
            val location = bestLocation ?: return@launch
            if (!isAccuracyGood(location)) {
                _uiState.value = _uiState.value.copy(
                    status = LocationStatus.ACCURACY_WEAK,
                    message = accuracyMessage(location)
                )
            }
        }
    }

    fun stopLocation() {
        locationRepository.stop()
    }

    private fun onLocation(snapshot: LocationSnapshot) {
        val selectedSnapshot = selectBestSnapshot(snapshot)
        val shouldLookup = shouldLookupAddress(selectedSnapshot)
        val currentState = _uiState.value

        if (selectedSnapshot.isLastKnown) {
            _uiState.value = currentState.copy(
                status = LocationStatus.STALE,
                message = "Đã khóa vị trí Fused/GPS gần nhất · đang cập nhật...",
                location = selectedSnapshot
            )
            logLocation(selectedSnapshot)
            if (shouldLookup) {
                lookupAddress(selectedSnapshot)
            }
            return
        }

        _uiState.value = currentState.copy(
            status = accuracyStatus(selectedSnapshot),
            message = accuracyMessage(selectedSnapshot),
            location = selectedSnapshot,
            address = if (shouldLookup) null else currentState.address,
            addressStatus = if (shouldLookup) AddressStatus.IDLE else currentState.addressStatus,
            addressMessage = if (shouldLookup) {
                "Địa chỉ: đang chờ xác định"
            } else {
                currentState.addressMessage
            }
        )

        logLocation(selectedSnapshot)

        if (shouldLookup) {
            lookupAddress(selectedSnapshot)
        }
    }

    private fun selectBestSnapshot(snapshot: LocationSnapshot): LocationSnapshot {
        val previous = bestLocation
        if (previous == null) {
            bestLocation = snapshot
            return snapshot
        }

        val prevAcc = if (previous.accuracyMeters.isNaN()) Float.MAX_VALUE else previous.accuracyMeters
        val candAcc = if (snapshot.accuracyMeters.isNaN()) Float.MAX_VALUE else snapshot.accuracyMeters

        // Nếu điểm trước đó là lastKnown và điểm mới là fresh (sai số hợp lý <= 35m), chuyển sang điểm fresh ngay
        if (previous.isLastKnown && !snapshot.isLastKnown && candAcc <= 35f) {
            bestLocation = snapshot
            return snapshot
        }

        // Nếu điểm mới chính xác hơn hoặc tương đương (chênh lệch <= 3m), cập nhật ngay
        if (candAcc <= prevAcc + 3f) {
            bestLocation = snapshot
            return snapshot
        }

        // Bỏ qua các điểm nhảy bất thường có sai số lớn (> 35m) khi đã có điểm chính xác hơn
        if (candAcc > 35f && prevAcc <= 30f) {
            return previous
        }

        val movedMeters = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            snapshot.latitude,
            snapshot.longitude,
            movedMeters
        )
        if (movedMeters[0] > (prevAcc + candAcc)) {
            bestLocation = snapshot
        }
        return bestLocation ?: snapshot
    }

    private fun accuracyStatus(snapshot: LocationSnapshot): LocationStatus = when {
        isAccuracyGood(snapshot) -> LocationStatus.READY
        precisionWindowActive -> LocationStatus.ACQUIRING
        else -> LocationStatus.ACCURACY_WEAK
    }

    private fun accuracyMessage(snapshot: LocationSnapshot): String {
        val accuracy = snapshot.accuracyMeters
        if (accuracy.isNaN()) {
            return "Đã có tọa độ (${snapshot.provider}) nhưng chưa rõ sai số"
        }
        val accuracyText = String.format(java.util.Locale.US, "±%.1f m", accuracy)
        return when {
            accuracy <= VERY_GOOD_ACCURACY_METERS ->
                "Định vị ${snapshot.provider} chính xác · sai số $accuracyText"

            accuracy <= ACCEPTABLE_ACCURACY_METERS ->
                "Định vị ${snapshot.provider} ổn định · sai số $accuracyText"

            precisionWindowActive ->
                "Đang tinh chỉnh ${snapshot.provider} · hiện tại $accuracyText"

            else ->
                "Định vị ${snapshot.provider} sai số cao · $accuracyText"
        }
    }

    private fun isAccuracyGood(snapshot: LocationSnapshot): Boolean =
        !snapshot.accuracyMeters.isNaN() &&
            snapshot.accuracyMeters <= ACCEPTABLE_ACCURACY_METERS

    private fun logLocation(snapshot: LocationSnapshot) {
        Log.i(
            LOCATION_LOG_TAG,
            "GPS_LOCATION lat=${snapshot.latitude}, lon=${snapshot.longitude}, " +
                "accuracy=${snapshot.accuracyMeters}m, " +
                "satellites=${snapshot.satellitesUsedInFix}, " +
                "provider=${snapshot.provider}, " +
                "lastKnown=${snapshot.isLastKnown}"
        )
    }

    private fun shouldLookupAddress(snapshot: LocationSnapshot): Boolean {
        val previous = lastAddressLocation ?: return true
        val distance = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            snapshot.latitude,
            snapshot.longitude,
            distance
        )
        return distance[0] >= ADDRESS_REFRESH_DISTANCE_METERS
    }

    private fun lookupAddress(snapshot: LocationSnapshot) {
        lastAddressLocation = snapshot
        addressJob?.cancel()
        _uiState.value = _uiState.value.copy(
            addressStatus = AddressStatus.LOADING,
            addressMessage = "Đang xác định địa chỉ online..."
        )

        addressJob = viewModelScope.launch {
            when (val result = addressRepository.reverseGeocode(snapshot)) {
                is AddressResult.Success -> {
                    Log.i(
                        LOCATION_LOG_TAG,
                        "ADDRESS lat=${snapshot.latitude}, lon=${snapshot.longitude}, " +
                            "address=${result.address.formatted}"
                    )
                    _uiState.value = _uiState.value.copy(
                        addressStatus = AddressStatus.READY,
                        address = result.address,
                        addressMessage = "Đã xác định địa chỉ online"
                    )
                }

                is AddressResult.Failed -> {
                    Log.w(LOCATION_LOG_TAG, "ADDRESS_LOOKUP_FAILED: ${result.reason}")
                    _uiState.value = _uiState.value.copy(
                        addressStatus = AddressStatus.ERROR,
                        addressMessage = "Địa chỉ (chế độ offline): tạm chưa có mạng để tra cứu"
                    )
                }
            }
        }
    }

    private fun onError(error: LocationError) {
        val (status, message) = when (error) {
            LocationError.PermissionDenied ->
                LocationStatus.PERMISSION_REQUIRED to "Cần quyền vị trí chính xác để dùng Fused Location / GPS"

            LocationError.GpsDisabled ->
                LocationStatus.GPS_DISABLED to "Dịch vụ vị trí đang tắt · hãy bật GPS"

            LocationError.Timeout ->
                LocationStatus.ERROR to
                    "Chưa nhận được tọa độ mới · hãy kiểm tra GPS và thử lại"

            is LocationError.Failed ->
                LocationStatus.ERROR to "Không lấy được tọa độ: ${error.reason}"
        }

        _uiState.value = _uiState.value.copy(status = status, message = message)
    }

    override fun onCleared() {
        addressJob?.cancel()
        precisionWindowJob?.cancel()
        locationRepository.stop()
        super.onCleared()
    }

    private companion object {
        const val ADDRESS_REFRESH_DISTANCE_METERS = 30f
        const val VERY_GOOD_ACCURACY_METERS = 12f
        const val ACCEPTABLE_ACCURACY_METERS = 30f
        const val PRECISION_WINDOW_MILLIS = 8_000L
        const val LOCATION_LOG_TAG = "CitizenLocation"
    }
}
