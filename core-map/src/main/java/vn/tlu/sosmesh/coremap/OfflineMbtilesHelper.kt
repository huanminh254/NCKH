package vn.tlu.sosmesh.coremap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object OfflineMbtilesHelper {
    const val ASSET_MBTILES_NAME = "hanoi_test_region.mbtiles"
    private const val OFFLINE_STYLE_FILE_NAME = "hanoi_offline_mbtiles_style.json"

    fun ensureMbtilesInstalled(context: Context): File {
        val outFile = File(context.filesDir, ASSET_MBTILES_NAME)
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(ASSET_MBTILES_NAME).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    fun getOfflineMbtilesStyleUri(context: Context): String {
        val mbtilesFile = ensureMbtilesInstalled(context)
        val styleFile = File(context.filesDir, OFFLINE_STYLE_FILE_NAME)

        val styleJson = JSONObject().apply {
            put("version", 8)
            put("name", "Hanoi Offline MBTiles")
            put(
                "sources",
                JSONObject().put(
                    "hanoi-mbtiles",
                    JSONObject().apply {
                        put("type", "raster")
                        put("url", "mbtiles://${mbtilesFile.absolutePath}")
                        put("tileSize", 256)
                    }
                )
            )
            put(
                "layers",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "hanoi-mbtiles-layer")
                        put("type", "raster")
                        put("source", "hanoi-mbtiles")
                        put("minzoom", 0)
                        put("maxzoom", 22)
                    }
                )
            )
        }

        styleFile.writeText(styleJson.toString(), Charsets.UTF_8)
        return "file://${styleFile.absolutePath}"
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
