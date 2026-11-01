package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun homeDeviceTypeFromDeviceType() {
        assertEquals(HomeDeviceType.Laundry, HomeDeviceType.fromDeviceType(10))
        assertEquals(HomeDeviceType.HairDrying, HomeDeviceType.fromDeviceType(20))
        assertEquals(HomeDeviceType.Drinking, HomeDeviceType.fromDeviceType(8))
        assertEquals(HomeDeviceType.Shower, HomeDeviceType.fromDeviceType(6))
        assertEquals(HomeDeviceType.Other, HomeDeviceType.fromDeviceType(0))
        assertEquals(HomeDeviceType.Other, HomeDeviceType.fromDeviceType(5))
        assertTrue(HomeDeviceType.All.matches(5))
        assertTrue(HomeDeviceType.All.matches(0))
        assertFalse(HomeDeviceType.Shower.matches(5))
        assertEquals(HomeDeviceType.Other, HomeDeviceType.fromDeviceType(999))
    }

    @Test
    fun accountPreferredTokenAndHasAnyToken() {
        val withApp = Account(appToken = "a", token = "b")
        assertEquals("a", withApp.preferredToken)
        assertTrue(withApp.hasAnyToken)

        val onlyPoints = Account(token = "b")
        assertEquals("b", onlyPoints.preferredToken)
        assertTrue(onlyPoints.hasAnyToken)

        val empty = Account()
        assertEquals("", empty.preferredToken)
        assertFalse(empty.hasAnyToken)
    }

    @Test
    fun refundProgressActive() {
        assertFalse(RefundProgress(ctime = -1L).active)
        assertTrue(RefundProgress(ctime = 123L).active)
    }

    @Test
    fun deviceSubStateAvailable() {
        assertTrue(DeviceSubState(err = 0, status = 99).available)
        assertFalse(DeviceSubState(err = null, status = 99).available)
        assertFalse(DeviceSubState(err = 0, status = null).available)
        assertFalse(DeviceSubState(err = null, status = null).available)
        assertFalse(DeviceSubState(err = 1, status = 99).available)
        assertFalse(DeviceSubState(err = 0, status = 1).available)
        assertFalse(DeviceSubState(err = 0, status = 98).available)
        assertFalse(DeviceSubState(err = null, status = 0).available)
    }

    @Test
    fun deviceDisplayStatusMapping() {
        assertEquals(DeviceDisplayStatus.Offline, deviceDisplayStatus(deviceStatus = 0, geneStatus = 99, dtype = 10))
        assertEquals(DeviceDisplayStatus.Online, deviceDisplayStatus(deviceStatus = 1, geneStatus = 99, dtype = 6))
        assertEquals(DeviceDisplayStatus.Disabled, deviceDisplayStatus(deviceStatus = 1, geneStatus = 98, dtype = 6))
        assertEquals(DeviceDisplayStatus.Starting, deviceDisplayStatus(deviceStatus = 1, geneStatus = 1, dtype = 10))
        assertEquals(DeviceDisplayStatus.Starting, deviceDisplayStatus(deviceStatus = 1, geneStatus = 30, dtype = 120))
        // 非启动过渡类型不进入“启动中”
        assertEquals(DeviceDisplayStatus.Running, deviceDisplayStatus(deviceStatus = 1, geneStatus = 1, dtype = 6))
        assertEquals(DeviceDisplayStatus.Running, deviceDisplayStatus(deviceStatus = 1, geneStatus = 5, dtype = 10))
    }
}
