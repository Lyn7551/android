/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment.syncedFolders

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.utils.BatteryOptimizationHelper
import com.owncloud.android.R
import com.owncloud.android.ui.dialog.extensions.themeButtons
import com.owncloud.android.utils.theme.ViewThemeUtils

class SyncedFoldersDialogs(private val viewThemeUtils: ViewThemeUtils) {

    fun buildPowerCheckDialog(context: Context): AlertDialog {
        val builder = MaterialAlertDialogBuilder(context)
            .setPositiveButton(R.string.common_ok) { dialog, _ -> dialog.dismiss() }
            .setTitle(R.string.autoupload_disable_power_save_check)
            .setMessage(context.getString(R.string.power_save_check_dialog_message))

        viewThemeUtils.dialog.colorMaterialAlertDialogBackground(context, builder)

        return builder.create()
    }

    fun showBatteryOptimizationDialog(activity: Activity) {
        val dialog = MaterialAlertDialogBuilder(activity, R.style.Theme_ownCloud_Dialog)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_disable) { _, _ ->
                BatteryOptimizationHelper.openBatteryOptimizationSettings(activity)
            }
            .setNeutralButton(R.string.battery_optimization_close, null)
            .setIcon(R.drawable.ic_battery_alert)

        val alertDialog = dialog.show()
        alertDialog.themeButtons(viewThemeUtils)
    }
}
