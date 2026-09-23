package com.ridechat.app

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun android31And32RequireLegacyLocationForNearbyDiscovery() {
        val required31 = PermissionPolicy.required(31)
        val required32 = PermissionPolicy.required(32)

        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in required31)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in required32)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in required31)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in required32)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in required31)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in required32)
    }

    @Test
    fun android33RequiresNearbyWifiAndTreatsNotificationsAsOptional() {
        val required = PermissionPolicy.required(33)
        val optional = PermissionPolicy.optional(33)

        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in required)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in required)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in optional)
    }

    @Test
    fun olderAndroidUsesFineLocationWithoutBluetoothRuntimePermissions() {
        val required = PermissionPolicy.required(30)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in required)
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in required)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in required)
    }

    @Test
    fun android12RequestIncludesCoarseWhenApproximateWasAlreadyGranted() {
        val request = PermissionPolicy.requestableForRequest(
            granted = setOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            sdk = 32,
        )

        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in request)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in request)
    }
}
