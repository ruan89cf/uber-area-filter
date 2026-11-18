package com.ruan.uberareafilter

import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.setup.SetupChecks.A11y
import com.ruan.uberareafilter.setup.SetupChecks.Level
import com.ruan.uberareafilter.shizuku.ShizukuTapBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras do status de recursos (o que conta como pendente e o que o painel mostra). */
class SetupChecksTest {

    private fun snap(
        a11y: A11y = A11y.RUNNING,
        notificationsOk: Boolean = true,
        battery: Boolean = true,
        overlayOk: Boolean = false,
        paired: Boolean = false,
        daemon: Boolean = false,
        refused: Boolean = false,
        shizuku: ShizukuTapBridge.State = ShizukuTapBridge.State.NOT_INSTALLED,
        uber: Boolean = true,
        autoTaps: Boolean = true
    ) = SetupChecks.Snapshot(
        a11y = a11y,
        restrictedLikely = false,
        notificationsOk = notificationsOk,
        batteryUnrestricted = battery,
        overlayOk = overlayOk,
        locationOk = false,
        devOptions = true,
        wifi = true,
        wirelessDebugOn = false,
        adbPaired = paired,
        daemonAlive = daemon,
        lastTapRefused = refused,
        writeSecureSettings = false,
        shizuku = shizuku,
        filterEnabled = true,
        module99 = true,
        moduleUber = uber,
        uberAutoTaps = autoTaps
    )

    @Test
    fun accessibilityThatIsOnButStoppedStillCountsAsPending() {
        assertEquals(0, snap(a11y = A11y.RUNNING, daemon = true).pendingRequired)
        assertEquals(1, snap(a11y = A11y.ON_NOT_RUNNING, daemon = true).pendingRequired)
        val line = SetupChecks.summaryLines(snap(a11y = A11y.ON_NOT_RUNNING, daemon = true)).first()
        assertEquals(Level.BAD, line.level)
        assertTrue(line.text.contains("parado"))
    }

    @Test
    fun overlayIsNeverRequired() {
        val s = snap(daemon = true, overlayOk = false)
        assertEquals(0, s.pendingRequired)
        assertEquals(0, s.pendingRecommended)
        assertFalse(SetupChecks.summaryLines(s).any { it.text.contains("Sobrepor", ignoreCase = true) })
    }

    @Test
    fun uberTapOnlyRequiredWhenUberAutoTapsAreOn() {
        assertEquals(1, snap(daemon = false).pendingRequired)
        assertEquals(0, snap(daemon = false, autoTaps = false).pendingRequired)
        assertEquals(0, snap(daemon = false, uber = false).pendingRequired)
        assertEquals(0, snap(daemon = false, shizuku = ShizukuTapBridge.State.ACTIVE).pendingRequired)
        // Shizuku autorizado mas ainda conectando NAO conta como pronto
        assertEquals(1, snap(daemon = false, shizuku = ShizukuTapBridge.State.CONNECTING).pendingRequired)
    }

    @Test
    fun refusedTapIsFlaggedEvenWithEngineOn() {
        val lines = SetupChecks.summaryLines(snap(daemon = true, refused = true))
        val uber = lines.first { it.text.startsWith("Toque na Uber") }
        assertEquals(Level.WARN, uber.level)
        assertTrue(uber.text.contains("recusou"))
    }

    @Test
    fun batteryAndNotificationsAreRecommendedNotRequired() {
        val s = snap(daemon = true, notificationsOk = false, battery = false)
        assertEquals(0, s.pendingRequired)
        assertEquals(2, s.pendingRecommended)
    }
}
