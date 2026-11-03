package vn.tlu.sosmesh.coremap.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object MeshPermissionHelper {
    const val MESH_PERMISSION_REQUEST_CODE = 2026

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    fun hasAllMeshPermissions(context: Context): Boolean {
        return requiredPermissions().all { perm ->
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestMissingPermissions(activity: Activity) {
        val missing = requiredPermissions().filter { perm ->
            activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), MESH_PERMISSION_REQUEST_CODE)
        }
    }
}
