/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment.syncedFolders

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.BackgroundJobManager
import com.owncloud.android.R
import com.owncloud.android.datamodel.SyncedFolderDisplayItem
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.ui.dialog.ConfirmationDialogFragment
import com.owncloud.android.utils.FilesSyncHelper

/**
 * Confirmation flows for deleting already uploaded files of synced folders.
 */
class SyncedFolderDeleteUploadedHandler(
    private val fragment: Fragment,
    private val syncedFolderProvider: SyncedFolderProvider,
    private val backgroundJobManager: BackgroundJobManager
) {

    fun showDeleteAllDialog(user: User) {
        val dialog = ConfirmationDialogFragment.newInstance(
            R.string.autoupload_delete_uploaded_all_dialog_description,
            null,
            R.string.autoupload_delete_uploaded_all_dialog_title,
            R.drawable.selector_trashbin,
            R.string.autoupload_delete_uploaded_all_dialog_button_all_users,
            R.string.autoupload_delete_uploaded_all_dialog_button_current_user,
            R.string.common_cancel
        )
        dialog.isCancelable = false
        dialog.setOnConfirmationListener(object : ConfirmationDialogFragment.ConfirmationDialogFragmentListener {
            override fun onConfirmation(callerTag: String?) {
                FilesSyncHelper.startLocalDeletionForEnabledSyncedFolders(syncedFolderProvider, backgroundJobManager)
            }

            override fun onCancel(callerTag: String?) {
                FilesSyncHelper.startLocalDeletionForEnabledSyncedFolders(
                    syncedFolderProvider,
                    backgroundJobManager,
                    user
                )
            }

            override fun onNeutral(callerTag: String?) = Unit
        })

        show(dialog, DELETE_ALL_UPLOADED_DIALOG_TAG)
    }

    fun showDeleteDialog(item: SyncedFolderDisplayItem) {
        val dialog = ConfirmationDialogFragment.newInstance(
            R.string.autoupload_delete_uploaded_dialog_description,
            null,
            R.string.autoupload_delete_uploaded_dialog_title,
            R.drawable.selector_trashbin,
            R.string.common_ok,
            R.string.common_cancel,
            -1
        )
        dialog.isCancelable = false
        dialog.setOnConfirmationListener(object : ConfirmationDialogFragment.ConfirmationDialogFragmentListener {
            override fun onConfirmation(callerTag: String?) {
                FilesSyncHelper.startLocalDeletionForSyncedFolder(item, backgroundJobManager)
            }

            override fun onNeutral(callerTag: String?) = Unit

            override fun onCancel(callerTag: String?) = Unit
        })

        show(dialog, DELETE_UPLOADED_DIALOG_TAG)
    }

    private fun show(dialog: ConfirmationDialogFragment, tag: String) {
        if (fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            dialog.show(fragment.childFragmentManager, tag)
        }
    }

    companion object {
        private const val DELETE_UPLOADED_DIALOG_TAG = "SYNCED_FOLDER_DELETE_UPLOADED_DIALOG_TAG"
        private const val DELETE_ALL_UPLOADED_DIALOG_TAG = "SYNCED_FOLDER_DELETE_ALL_UPLOADED_DIALOG_TAG"
    }
}
