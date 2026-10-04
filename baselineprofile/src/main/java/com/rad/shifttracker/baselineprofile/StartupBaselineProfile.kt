package com.rad.shifttracker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.rad.shifttracker.benchmark"
private const val WAIT_MS = 5_000L

/**
 * Hladan start do prvog ekrana i prelazak meseca napred-nazad (kalendar, obračun meseca).
 * Sveža instalacija prvo nudi „Popuni smene“; i taj tok je deo pravog prvog pokretanja.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.descContains("mesec")), WAIT_MS)
        device.findObject(By.desc("Sledeći mesec"))?.click()
        device.waitForIdle()
        device.findObject(By.desc("Prethodni mesec"))?.click()
        device.waitForIdle()
    }
}
