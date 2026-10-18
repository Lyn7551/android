/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment.syncedFolders

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nextcloud.client.core.Clock
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.preferences.SubFolderRule
import com.owncloud.android.R
import com.owncloud.android.datamodel.MediaFolder
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderDisplayItem
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.utils.SyncedFolderUtils
import java.io.File
import java.util.Locale

/**
 * Merges synced folder configurations with device media folders into display items.
 */
class SyncedFolderItemMerger(private val context: Context, private val clock: Clock) {

    val itemsDisplayedPerFolder: Int
        get() = context.resources.getInteger(R.integer.media_grid_width) * 2

    /**
     * merges two lists of [SyncedFolder] and [MediaFolder] items into one of SyncedFolderItems.
     */
    @Suppress("NestedBlockDepth") // legacy code
    fun mergeFolderData(
        accountName: String,
        syncedFolders: List<SyncedFolder>,
        mediaFolders: List<MediaFolder>
    ): List<SyncedFolderDisplayItem?> {
        val syncedFoldersMap = createSyncedFoldersMap(syncedFolders)
        val result: MutableList<SyncedFolderDisplayItem?> = ArrayList()
        for (mediaFolder in mediaFolders) {
            if (syncedFoldersMap.containsKey(mediaFolder.absolutePath + "-" + mediaFolder.type)) {
                val syncedFolder = syncedFoldersMap[mediaFolder.absolutePath + "-" + mediaFolder.type]
                syncedFoldersMap.remove(mediaFolder.absolutePath + "-" + mediaFolder.type)
                if (syncedFolder != null && SyncedFolderUtils.isQualifyingMediaFolder(syncedFolder)) {
                    if (MediaFolderType.CUSTOM == syncedFolder.type) {
                        result.add(createSyncedFolderWithoutMediaFolder(syncedFolder))
                    } else {
                        result.add(createSyncedFolder(syncedFolder, mediaFolder))
                    }
                }
            } else {
                if (SyncedFolderUtils.isQualifyingMediaFolder(mediaFolder)) {
                    result.add(createSyncedFolderFromMediaFolder(accountName, mediaFolder))
                }
            }
        }
        for (syncedFolder in syncedFoldersMap.values) {
            result.add(createSyncedFolderWithoutMediaFolder(syncedFolder))
        }
        return result
    }

    private fun createSyncedFolderWithoutMediaFolder(syncedFolder: SyncedFolder): SyncedFolderDisplayItem {
        val localFolder = File(syncedFolder.localPath)
        val files = SyncedFolderUtils.getFileList(localFolder)
        val filePaths = getDisplayFilePathList(files)
        return SyncedFolderDisplayItem(
            syncedFolder.id,
            syncedFolder.localPath,
            syncedFolder.remotePath,
            syncedFolder.isWifiOnly,
            syncedFolder.isChargingOnly,
            syncedFolder.alsoUploadExistingFiles(),
            syncedFolder.isSubfolderByDate,
            syncedFolder.account,
            syncedFolder.uploadAction,
            syncedFolder.nameCollisionPolicyInt,
            syncedFolder.isEnabled,
            clock.currentTime,
            filePaths,
            localFolder.name,
            files.size.toLong(),
            syncedFolder.type,
            syncedFolder.isHidden,
            syncedFolder.subfolderRule,
            syncedFolder.isExcludeHidden,
            syncedFolder.lastScanTimestampMs
        )
    }

    private fun createSyncedFolder(syncedFolder: SyncedFolder, mediaFolder: MediaFolder): SyncedFolderDisplayItem =
        SyncedFolderDisplayItem(
            syncedFolder.id,
            syncedFolder.localPath,
            syncedFolder.remotePath,
            syncedFolder.isWifiOnly,
            syncedFolder.isChargingOnly,
            syncedFolder.alsoUploadExistingFiles(),
            syncedFolder.isSubfolderByDate,
            syncedFolder.account,
            syncedFolder.uploadAction,
            syncedFolder.nameCollisionPolicyInt,
            syncedFolder.isEnabled,
            clock.currentTime,
            mediaFolder.filePaths,
            mediaFolder.folderName,
            mediaFolder.numberOfFiles,
            mediaFolder.type,
            syncedFolder.isHidden,
            syncedFolder.subfolderRule,
            syncedFolder.isExcludeHidden,
            syncedFolder.lastScanTimestampMs
        )

    private fun createSyncedFolderFromMediaFolder(
        accountName: String,
        mediaFolder: MediaFolder
    ): SyncedFolderDisplayItem = SyncedFolderDisplayItem(
        SyncedFolder.UNPERSISTED_ID,
        mediaFolder.absolutePath,
        context.getString(R.string.instant_upload_path) + "/" + mediaFolder.folderName,
        true,
        false,
        true,
        false,
        accountName,
        FileUploadWorker.LOCAL_BEHAVIOUR_FORGET,
        NameCollisionPolicy.ASK_USER.serialize(),
        false,
        clock.currentTime,
        mediaFolder.filePaths,
        mediaFolder.folderName,
        mediaFolder.numberOfFiles,
        mediaFolder.type,
        false,
        SubFolderRule.YEAR_MONTH,
        false,
        SyncedFolder.NOT_SCANNED_YET
    )

    private fun getDisplayFilePathList(files: List<File>?): List<String>? {
        if (!files.isNullOrEmpty()) {
            return files.take(itemsDisplayedPerFolder)
                .map { it.absolutePath }
        }
        return null
    }

    private fun createSyncedFoldersMap(syncFolders: List<SyncedFolder>?): MutableMap<String, SyncedFolder> {
        val result: MutableMap<String, SyncedFolder> = HashMap()
        if (syncFolders != null) {
            for (syncedFolder in syncFolders) {
                result[syncedFolder.localPath + "-" + syncedFolder.type] = syncedFolder
            }
        }
        return result
    }

    companion object {
        private val PRIORITIZED_FOLDERS = arrayOf("Camera", "Screenshots")

        /**
         * Sorts list of [SyncedFolderDisplayItem]s.
         *
         * @param syncFolderItemList list of items to be sorted
         * @return sorted list of items
         */
        @JvmStatic
        @VisibleForTesting
        @Suppress("ComplexMethod")
        fun sortSyncedFolderItems(syncFolderItemList: List<SyncedFolderDisplayItem?>): List<SyncedFolderDisplayItem?> {
            return syncFolderItemList.sortedWith { f1, f2 ->
                if (f1 == null && f2 == null) {
                    0
                } else if (f1 == null) {
                    -1
                } else if (f2 == null) {
                    1
                } else if (f1.isEnabled && f2.isEnabled) {
                    when {
                        f1.folderName == null -> -1

                        f2.folderName == null -> 1

                        else -> f1.folderName.lowercase(Locale.getDefault()).compareTo(
                            f2.folderName.lowercase(Locale.getDefault())
                        )
                    }
                } else if (f1.folderName == null && f2.folderName == null) {
                    0
                } else if (f1.isEnabled) {
                    -1
                } else if (f2.isEnabled) {
                    1
                } else if (f1.folderName == null) {
                    -1
                } else if (f2.folderName == null) {
                    1
                } else {
                    for (folder in PRIORITIZED_FOLDERS) {
                        if (folder == f1.folderName && folder == f2.folderName) {
                            return@sortedWith 0
                        } else if (folder == f1.folderName) {
                            return@sortedWith -1
                        } else if (folder == f2.folderName) {
                            return@sortedWith 1
                        }
                    }
                    f1.folderName.lowercase(Locale.getDefault()).compareTo(
                        f2.folderName.lowercase(Locale.getDefault())
                    )
                }
            }
        }
    }
}
