package com.sunswift.ula

import android.app.Activity
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake Play service only: no store installs, credentials, live records or production files. */
@RunWith(AndroidJUnit4::class)
class AppUpdatesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Update callback did not arrive")
    }

    @Test fun newerBuildOffersButDoesNotStartWithoutUserAction() {
        val fake = FakeAppUpdateManager(instrumentation.targetContext)
        var launches = 0
        lateinit var updates: UlaAppUpdates
        main {
            fake.setUpdateAvailable(101)
            updates = UlaAppUpdates(instrumentation.targetContext, fake, 100, false) { launches++; true }
            updates.check(manual = true)
        }
        try {
            await { updates.stage == UpdateStage.AVAILABLE }
            main { assertTrue(updates.visible); assertEquals(0, launches); updates.download() }
            await { launches == 1 }
            main {
                updates.onFlowResult(Activity.RESULT_CANCELED)
                assertEquals(UpdateStage.ERROR, updates.stage)
                assertTrue(updates.message.contains("keep using ULA"))
            }
        } finally { main { updates.dispose() } }
    }

    @Test fun equalOrOlderBuildDoesNotOfferAnUpdate() {
        val fake = FakeAppUpdateManager(instrumentation.targetContext)
        for (available in listOf(99, 100)) {
            lateinit var updates: UlaAppUpdates
            main {
                fake.setUpdateAvailable(available)
                updates = UlaAppUpdates(instrumentation.targetContext, fake, 100, false) { fail("Must not launch"); false }
                updates.check(manual = true)
            }
            try { await { updates.stage == UpdateStage.CURRENT } }
            finally { main { updates.dispose() } }
        }
    }

    @Test fun unavailableFlexibleFlowUsesStoreAndApiFailureIsNotCurrent() {
        val fake = FakeAppUpdateManager(instrumentation.targetContext)
        lateinit var updates: UlaAppUpdates
        main {
            fake.setUpdateAvailable(101, AppUpdateType.IMMEDIATE)
            updates = UlaAppUpdates(instrumentation.targetContext, fake, 100, false) { fail("No forced immediate update"); false }
            updates.check(manual = true)
        }
        try {
            await { updates.stage == UpdateStage.STORE }
            main { fake.setInstallErrorCode(InstallErrorCode.ERROR_API_NOT_AVAILABLE); updates.check(manual = true) }
            await { updates.stage == UpdateStage.ERROR }
            main { assertTrue(updates.message.contains("do not uninstall")) }
        } finally { main { updates.dispose() } }
    }

    @Test fun testCopyDoesNotReceiveProductionUpdate() {
        val fake = FakeAppUpdateManager(instrumentation.targetContext)
        lateinit var updates: UlaAppUpdates
        main {
            fake.setUpdateAvailable(101)
            updates = UlaAppUpdates(instrumentation.targetContext, fake, 100, true) { fail("No debug updates"); false }
            updates.check(manual = true)
            assertEquals(UpdateStage.TEST, updates.stage)
            assertTrue(updates.message.contains("without TEST"))
            updates.dispose()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun downloadedUpdateNeedsExplicitRestartAndIsRecoveredOnResume() {
        val fake = FakeAppUpdateManager(instrumentation.targetContext)
        lateinit var updates: UlaAppUpdates
        main {
            fake.setUpdateAvailable(101)
            updates = UlaAppUpdates(instrumentation.targetContext, fake, 100, false) { info ->
                // Fake service does not launch a real Activity, download or install a package.
                fake.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, Activity(), 731)
            }
            updates.check(manual = true)
        }
        try {
            await { updates.stage == UpdateStage.AVAILABLE }
            main { updates.download() }
            await { fake.isConfirmationDialogVisible }
            main { fake.userAcceptsUpdate(); fake.downloadStarts() }
            await { updates.stage == UpdateStage.DOWNLOADING }
            main { fake.downloadCompletes() }
            await { updates.stage == UpdateStage.READY }
            main {
                assertFalse(fake.isInstallSplashScreenVisible)
                assertTrue(updates.message.contains("Save any unsaved"))
                updates.dismiss(); updates.check()
            }
            await { updates.visible && updates.stage == UpdateStage.READY }
            main { assertFalse(fake.isInstallSplashScreenVisible); updates.complete() }
            await { fake.isInstallSplashScreenVisible }
        } finally { main { updates.dispose() } }
    }
}
