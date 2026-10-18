/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-FileCopyrightText: 2025 Alper Ozturk <alper.ozturk@nextcloud.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.nextcloud.client

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.AbstractIT
import com.owncloud.android.R
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderDisplayItem
import com.owncloud.android.ui.dialog.SyncedFolderPreferencesDialogFragment.Companion.newInstance
import com.owncloud.android.ui.fragment.syncedFolders.SyncedFoldersFragment
import com.owncloud.android.ui.navigation.NavigatorActivity
import com.owncloud.android.ui.navigation.NavigatorScreen
import com.owncloud.android.utils.ScreenshotTest
import org.junit.Test

class SyncedFoldersFragmentIT : AbstractIT() {
    private val testClassName = "com.nextcloud.client.SyncedFoldersFragmentIT"

    private fun launchScreen(): ActivityScenario<NavigatorActivity> {
        val intent = NavigatorActivity.intent(targetContext, NavigatorScreen.SyncedFolders)
        return ActivityScenario.launch(intent)
    }

    private fun NavigatorActivity.syncedFoldersFragment(): SyncedFoldersFragment =
        supportFragmentManager.fragments.filterIsInstance<SyncedFoldersFragment>().first()

    @Test
    @ScreenshotTest
    fun open() {
        launchScreen().use { scenario ->
            val screenShotName = createName(testClassName + "_" + "open", "")
            onView(isRoot()).check(matches(isDisplayed()))

            scenario.onActivity { sut ->
                val fragment = sut.syncedFoldersFragment()
                fragment.adapter.clear()
                screenshotViaName(fragment.requireView().findViewById(R.id.emptyList), screenShotName)
            }
        }
    }

    @Test
    @ScreenshotTest
    fun testSyncedFolderDialog() {
        val item = SyncedFolderDisplayItem(
            1,
            "/sdcard/DCIM/",
            "/InstantUpload/",
            true,
            false,
            false,
            true,
            "test@https://nextcloud.localhost",
            0,
            0,
            true,
            1000,
            "Name",
            MediaFolderType.IMAGE,
            false,
            SubFolderRule.YEAR_MONTH,
            false,
            SyncedFolder.NOT_SCANNED_YET
        )

        launchScreen().use { scenario ->
            val screenShotName = createName(testClassName + "_" + "testSyncedFolderDialog", "")
            onView(isRoot()).check(matches(isDisplayed()))

            scenario.onActivity { sut ->
                val dialog = newInstance(item, 0)
                dialog!!.show(sut.syncedFoldersFragment().childFragmentManager, "")
                screenshot(dialog.requireDialog().window?.decorView, screenShotName)
            }
        }
    }

    @Test
    @ScreenshotTest
    fun showPowerCheckDialog() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        launchScreen().use { scenario ->
            var dialog: AlertDialog? = null
            scenario.onActivity { sut ->
                val fragment = sut.syncedFoldersFragment()
                dialog = fragment.buildPowerCheckDialog()
                fragment.showPowerCheckDialog()
            }

            val screenShotName = createName(testClassName + "_" + "showPowerCheckDialog", "")
            onView(isRoot()).check(matches(isDisplayed()))
            screenshot(dialog!!.window?.decorView, screenShotName)
        }
    }
}
