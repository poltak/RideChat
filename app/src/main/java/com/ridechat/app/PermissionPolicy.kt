package com.ridechat.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions used by the selected Android and Nearby SDK versions. */
object PermissionPolicy {
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdk >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (sdk <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    /** Notification controls remain useful but notification denial is non-fatal. */
    fun optional(sdk: Int = Build.VERSION.SDK_INT): List<String> = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

    fun requestable(sdk: Int = Build.VERSION.SDK_INT): List<String> = required(sdk) + optional(sdk)

    /** Keeps coarse location in an Android 12 request when fine is still missing. */
    fun requestableForRequest(
        granted: Set<String>,
        sdk: Int = Build.VERSION.SDK_INT,
    ): List<String> {
        val missing = requestable(sdk).filterNot(granted::contains).toMutableList()
        val android12Location = sdk >= Build.VERSION_CODES.S && sdk <= Build.VERSION_CODES.S_V2
        if (android12Location &&
            Manifest.permission.ACCESS_FINE_LOCATION in missing &&
            Manifest.permission.ACCESS_COARSE_LOCATION !in missing
        ) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return missing
    }

    fun missing(context: Context): List<String> = required().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun missingOptional(context: Context): List<String> = optional().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun label(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.BLUETOOTH_SCAN -> "Nearby Bluetooth scan"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Nearby Bluetooth advertising"
        Manifest.permission.BLUETOOTH_CONNECT -> "Nearby Bluetooth connection"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Nearby discovery (approximate)"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Nearby discovery (precise)"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
        Manifest.permission.POST_NOTIFICATIONS -> "Ride notification"
        else -> permission
    }
}
