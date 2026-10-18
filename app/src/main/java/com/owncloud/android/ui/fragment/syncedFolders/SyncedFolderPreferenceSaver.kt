/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment.syncedFolders

import com.nextcloud.client.core.Clock
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.MainApp
import com.owncloud.android.datamodel.ArbitraryDataProviderImpl
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderDisplayItem
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.ui.dialog.parcel.SyncedFolderParcelable
import java.io.File

/**
 * Persists synced folder configuration changes made on the auto upload screen.
 */
class SyncedFolderPreferenceSaver(
    private val syncedFolderProvider: SyncedFolderProvider,
    private val backgroundJobManager: BackgroundJobManager,
    private val clock: Clock
) {

    fun saveOrUpdateSyncedFolder(item: SyncedFolderDisplayItem) {
        if (item.id == SyncedFolder.UNPERSISTED_ID) {
            storeSyncedFolder(item)
        } else {
            syncedFolderProvider.updateSyncFolder(item)
            if (item.isEnabled) {
                backgroundJobManager.startAutoUpload(item, overridePowerSaving = false)
            } else {
                deleteInitiatedKey(item.id)
            }
        }
    }

    private fun storeSyncedFolder(item: SyncedFolderDisplayItem) {
        val storedId = syncedFolderProvider.storeSyncedFolder(item)
        if (storedId != -1L) {
            item.id = storedId
            if (item.isEnabled) {
                backgroundJobManager.startAutoUpload(item, overridePowerSaving = false)
            } else {
                deleteInitiatedKey(item.id)
            }
        }
    }

    private fun deleteInitiatedKey(id: Long) {
        val arbitraryDataProvider = ArbitraryDataProviderImpl(MainApp.getAppContext())
        arbitraryDataProvider.deleteKeyForAccount("global", KEY_SYNCED_FOLDER_INITIATED_PREFIX + id)
    }

    /**
     * Copies the values of the preference dialog parcelable into the given display item.
     */
    @Suppress("LongParameterList")
    fun updateSyncedFolderItem(
        item: SyncedFolderDisplayItem,
        id: Long,
        localPath: String,
        remotePath: String,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        existing: Boolean,
        subfolderByDate: Boolean,
        uploadAction: Int,
        nameCollisionPolicy: Int,
        enabled: Boolean,
        subFolderRule: SubFolderRule,
        excludeHidden: Boolean
    ) {
        item.id = id
        item.localPath = localPath
        item.remotePath = remotePath
        item.isWifiOnly = wifiOnly
        item.isChargingOnly = chargingOnly
        item.setExisting(existing)
        item.isSubfolderByDate = subfolderByDate
        item.uploadAction = uploadAction
        item.setNameCollisionPolicy(nameCollisionPolicy)
        item.setEnabled(enabled, clock.currentTime)
        item.setSubFolderRule(subFolderRule)
        item.setExcludeHidden(excludeHidden)
    }

    /**
     * Builds a display item for a custom folder that is not part of the list yet.
     */
    fun createCustomFolderItem(syncedFolder: SyncedFolderParcelable): SyncedFolderDisplayItem = SyncedFolderDisplayItem(
        SyncedFolder.UNPERSISTED_ID,
        syncedFolder.localPath,
        syncedFolder.remotePath,
        syncedFolder.isWifiOnly,
        syncedFolder.isChargingOnly,
        syncedFolder.isExisting,
        syncedFolder.isSubfolderByDate,
        syncedFolder.account,
        syncedFolder.uploadAction,
        syncedFolder.nameCollisionPolicy.serialize(),
        syncedFolder.isEnabled,
        clock.currentTime,
        File(syncedFolder.localPath).name,
        syncedFolder.type,
        syncedFolder.isHidden,
        syncedFolder.subFolderRule,
        syncedFolder.isExcludeHidden,
        SyncedFolder.NOT_SCANNED_YET
    )

    companion object {
        // yes, there is a typo in this value
        private const val KEY_SYNCED_FOLDER_INITIATED_PREFIX = "syncedFolderIntitiated_"
    }
}
