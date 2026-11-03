package vn.tlu.sosmesh.coremap

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

open class MapActivity : ComponentActivity() {
    protected val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
    protected lateinit var mapView: MapView
    protected lateinit var mapStatusText: TextView
    protected var mapLibreMap: MapLibreMap? = null
    private var usingFallbackMbtiles = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.map_activity)

        val mbtilesFile = OfflineMbtilesHelper.ensureMbtilesInstalled(applicationContext)
        val role = intent.getStringExtra(EXTRA_ROLE) ?: "SOS Mesh"
        findViewById<TextView>(R.id.titleText).text = role
        mapStatusText = findViewById(R.id.mapStatusText)
        mapView = findViewById(R.id.mapView)

        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener {
            if (!usingFallbackMbtiles) {
                usingFallbackMbtiles = true
                val offlineStyleUri = OfflineMbtilesHelper.getOfflineMbtilesStyleUri(applicationContext)
                mapLibreMap?.setStyle(offlineStyleUri) {
                    mapStatusText.text =
                        "Đang dùng bản đồ offline MBTiles (${mbtilesFile.name})"
                    mapLibreMap?.let(::onMapReady)
                }
            }
        }

        loadPreferredStyle()

        findViewById<Button>(R.id.downloadOfflineButton).setOnClickListener {
            downloadHanoiRegion()
        }
        findViewById<Button>(R.id.reloadMapButton).setOnClickListener {
            usingFallbackMbtiles = false
            loadPreferredStyle(isReload = true)
        }
    }

    private fun loadPreferredStyle(isReload: Boolean = false) {
        val mbtilesFile = OfflineMbtilesHelper.ensureMbtilesInstalled(applicationContext)
        val isOnline = OfflineMbtilesHelper.isNetworkAvailable(applicationContext)

        mapView.getMapAsync { map ->
            if (mapLibreMap == null) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(21.0285, 105.8542))
                    .zoom(13.0)
                    .build()
            }
            mapLibreMap = map

            if (isOnline) {
                usingFallbackMbtiles = false
                map.setStyle(styleUrl) {
                    mapStatusText.text = if (isReload) {
                        "Đã tải lại bản đồ online · MBTiles (${mbtilesFile.name}) sẵn sàng"
                    } else {
                        "Bản đồ online sẵn sàng · MBTiles (${mbtilesFile.name}) đã nạp"
                    }
                    onMapReady(map)
                }
            } else {
                OfflineManager.getInstance(this).listOfflineRegions(
                    object : OfflineManager.ListOfflineRegionsCallback {
                        override fun onList(offlineRegions: Array<OfflineRegion>?) {
                            if (!offlineRegions.isNullOrEmpty()) {
                                usingFallbackMbtiles = false
                                map.setStyle(styleUrl) {
                                    mapStatusText.text =
                                        "Chế độ offline · Đang hiển thị vùng Hà Nội đã tải + MBTiles"
                                    onMapReady(map)
                                }
                            } else {
                                usingFallbackMbtiles = true
                                val offlineUri = OfflineMbtilesHelper.getOfflineMbtilesStyleUri(applicationContext)
                                map.setStyle(offlineUri) {
                                    mapStatusText.text =
                                        "Chế độ offline · Đang hiển thị MBTiles khu vực thử nghiệm (${mbtilesFile.name})"
                                    onMapReady(map)
                                }
                            }
                        }

                        override fun onError(error: String) {
                            usingFallbackMbtiles = true
                            val offlineUri = OfflineMbtilesHelper.getOfflineMbtilesStyleUri(applicationContext)
                            map.setStyle(offlineUri) {
                                mapStatusText.text =
                                    "Chế độ offline · Đang hiển thị MBTiles (${mbtilesFile.name})"
                                onMapReady(map)
                            }
                        }
                    }
                )
            }
        }
    }

    protected open fun onMapReady(map: MapLibreMap) = Unit

    private fun downloadHanoiRegion() {
        mapStatusText.text = "Đang chuẩn bị tải bản đồ offline khu vực Hà Nội..."
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            LatLngBounds.from(21.45, 106.05, 20.55, 105.35),
            9.0,
            15.0,
            resources.displayMetrics.density
        )
        val metadata = JSONObject()
            .put("name", "Hanoi test region")
            .put("version", 1)
            .toString()
            .toByteArray()

        OfflineManager.getInstance(this).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val completed = status.completedResourceCount
                            val required = status.requiredResourceCount
                            runOnUiThread {
                                mapStatusText.text =
                                    "Đang tải offline: $completed/$required tài nguyên"
                                if (required > 0L && completed >= required) {
                                    mapStatusText.text =
                                        "Đã tải xong bản đồ offline khu vực Hà Nội (kèm MBTiles)"
                                }
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            runOnUiThread {
                                mapStatusText.text = "Lỗi tải offline: ${error.message}"
                            }
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            runOnUiThread {
                                mapStatusText.text = "Vượt giới hạn số tile offline: $limit"
                            }
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        mapStatusText.text = "Không tạo được vùng offline: $error"
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_ROLE = "extra_role"
    }
}
