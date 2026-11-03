package vn.tlu.sosmesh.citizen.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.tlu.sosmesh.citizen.data.location.AddressRepository
import vn.tlu.sosmesh.citizen.data.location.LocationRepository

class CitizenViewModelFactory(
    private val locationRepository: LocationRepository,
    private val addressRepository: AddressRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CitizenViewModel::class.java)) {
            return CitizenViewModel(locationRepository, addressRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
