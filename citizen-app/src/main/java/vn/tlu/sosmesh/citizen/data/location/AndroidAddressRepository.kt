package vn.tlu.sosmesh.citizen.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AndroidAddressRepository(context: Context) : AddressRepository {
    private val appContext = context.applicationContext

    override suspend fun reverseGeocode(location: LocationSnapshot): AddressResult =
        withContext(Dispatchers.IO) {
            val photonAddress = runCatching {
                fetchPhotonAddress(location.latitude, location.longitude)
            }.getOrNull()
            if (photonAddress != null) {
                return@withContext AddressResult.Success(photonAddress)
            }

            val bigDataCloudAddress = runCatching {
                fetchBigDataCloudAddress(location.latitude, location.longitude)
            }.getOrNull()
            if (bigDataCloudAddress != null) {
                return@withContext AddressResult.Success(bigDataCloudAddress)
            }

            if (!Geocoder.isPresent()) {
                return@withContext AddressResult.Failed("Thiết bị không hỗ trợ Geocoder")
            }

            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(appContext, VIETNAMESE_LOCALE)
                    .getFromLocation(location.latitude, location.longitude, 1)

                val address = addresses?.firstOrNull()
                    ?: return@withContext AddressResult.Failed("Không tìm thấy địa chỉ")

                AddressResult.Success(address.toLocationAddress())
            } catch (exception: Exception) {
                AddressResult.Failed(exception.message ?: "Không thể xác định địa chỉ")
            }
        }

    private fun fetchPhotonAddress(lat: Double, lon: Double): LocationAddress? {
        val url = URL(
            String.format(
                Locale.US,
                "https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f",
                lat,
                lon
            )
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_500
            readTimeout = 3_500
            setRequestProperty("User-Agent", "SosMeshAndroid/1.0")
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(body).optJSONArray("features") ?: return null
            if (features.length() == 0) return null
            val props = features.getJSONObject(0).optJSONObject("properties") ?: return null

            val street = listOfNotNull(
                props.optString("housenumber").takeIf { it.isNotBlank() },
                props.optString("street").takeIf { it.isNotBlank() }
                    ?: props.optString("name").takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { null }
            val area = props.optString("locality").takeIf { it.isNotBlank() }
            val district = props.optString("district").takeIf { it.isNotBlank() }
            val city = props.optString("city").takeIf { it.isNotBlank() }
                ?: props.optString("state").takeIf { it.isNotBlank() }

            val formatted = listOfNotNull(street, area, district, city)
                .distinct()
                .joinToString(", ")
            if (formatted.isBlank()) null else LocationAddress(street, area, district, city, formatted)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchBigDataCloudAddress(lat: Double, lon: Double): LocationAddress? {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.6f&longitude=%.6f&localityLanguage=vi",
                lat,
                lon
            )
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_500
            readTimeout = 3_500
        }
        return try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val area = json.optString("locality").takeIf { it.isNotBlank() }
            val city = json.optString("city").takeIf { it.isNotBlank() }
                ?: json.optString("principalSubdivision").takeIf { it.isNotBlank() }
            val formatted = listOfNotNull(area, city).distinct().joinToString(", ")
            if (formatted.isBlank()) null else LocationAddress(null, area, null, city, formatted)
        } finally {
            conn.disconnect()
        }
    }

    private fun Address.toLocationAddress(): LocationAddress {
        val formatted = getAddressLine(0)
            ?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                thoroughfare,
                subLocality,
                locality,
                adminArea
            ).joinToString(", ")

        return LocationAddress(
            street = thoroughfare?.takeIf { it.isNotBlank() },
            area = subLocality?.takeIf { it.isNotBlank() },
            district = subAdminArea?.takeIf { it.isNotBlank() },
            city = listOfNotNull(locality, adminArea)
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() },
            formatted = formatted
        )
    }

    private companion object {
        val VIETNAMESE_LOCALE: Locale = Locale("vi", "VN")
    }
}
