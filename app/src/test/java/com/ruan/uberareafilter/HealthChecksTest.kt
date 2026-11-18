package com.ruan.uberareafilter

import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthChecksTest {

    private fun snap(
        a11y: SetupChecks.A11y = SetupChecks.A11y.RUNNING,
        enabled: Boolean = true,
        battery: Boolean = true,
        uber: Boolean = false,
        daemon: Boolean = false,
        autoTaps: Boolean = true
    ) = SetupChecks.Snapshot(
        a11y = a11y, restrictedLikely = false, notificationsOk = true, batteryUnrestricted = battery,
        overlayOk = true, locationOk = true, devOptions = true, wifi = true, wirelessDebugOn = false,
        adbPaired = false, daemonAlive = daemon, lastTapRefused = false, writeSecureSettings = true,
        shizuku = ShizukuTapBridge.State.NOT_INSTALLED, filterEnabled = enabled, module99 = true,
        moduleUber = uber, uberAutoTaps = autoTaps
    )

    @Test
    fun everythingReadyIsAllGreen() {
        val checks = SetupChecks.healthChecks(snap(), hasArea = true)
        assertTrue(checks.all { it.level == SetupChecks.Level.OK })
    }

    @Test
    fun accessibilityOffIsRedWithFixAndPlainExplanation() {
        val first = SetupChecks.healthChecks(snap(a11y = SetupChecks.A11y.OFF), hasArea = true).first()
        assertEquals(SetupChecks.Level.BAD, first.level)
        assertEquals(SetupChecks.Fix.ACCESSIBILITY, first.fix)
        assertTrue(first.detail.startsWith("Falta isso:"))
    }

    @Test
    fun turnedOffByUserIsNeutralWithTurnOnButton() {
        val first = SetupChecks.healthChecks(snap(enabled = false), hasArea = true).first()
        assertEquals(SetupChecks.Level.OFF, first.level)
        assertEquals(SetupChecks.Fix.TURN_ON, first.fix)
    }

    @Test
    fun missingAreaBatteryAndUberTapAreFlagged() {
        val checks = SetupChecks.healthChecks(snap(battery = false, uber = true), hasArea = false)
        val byTitle = checks.associateBy { it.title }
        assertEquals(SetupChecks.Fix.MAP, byTitle["Sua área no mapa"]?.fix)
        assertEquals(SetupChecks.Fix.BATTERY, byTitle["Bateria"]?.fix)
        assertEquals(SetupChecks.Level.WARN, byTitle["Toque automático na Uber"]?.level)
    }
}
