/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment.syncedFolders

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.core.Clock
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.client.jobs.MediaFoldersDetectionWork
import com.nextcloud.client.jobs.NotificationWork
import com.nextcloud.client.jobs.upload.FileUploadHelper
import com.nextcloud.client.jobs.upload.FileUploadWorker
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.SubFolderRule
import com.nextcloud.ui.component.UploadWarningCard
import com.nextcloud.utils.BatteryOptimizationHelper
import com.nextcloud.utils.extensions.getParcelableArgument
import com.nextcloud.utils.extensions.getTypedActivity
import com.nextcloud.utils.extensions.isDialogFragmentReady
import com.nextcloud.utils.extensions.setVisibleIf
import com.owncloud.android.R
import com.owncloud.android.databinding.StoragePermissionWarningBannerBinding
import com.owncloud.android.databinding.SyncedFoldersFragmentBinding
import com.owncloud.android.datamodel.MediaFolderType
import com.owncloud.android.datamodel.MediaProvider
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.SyncedFolder
import com.owncloud.android.datamodel.SyncedFolderDisplayItem
import com.owncloud.android.datamodel.SyncedFolderProvider
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.ui.activity.FolderPickerActivity
import com.owncloud.android.ui.activity.UploadFilesActivity
import com.owncloud.android.ui.adapter.SyncedFolderAdapter
import com.owncloud.android.ui.adapter.storagePermissionBanner.setup
import com.owncloud.android.ui.decoration.MediaGridItemDecoration
import com.owncloud.android.ui.dialog.ConfirmationDialogFragment
import com.owncloud.android.ui.dialog.SyncedFolderPreferencesDialogFragment
import com.owncloud.android.ui.dialog.SyncedFolderPreferencesDialogFragment.OnSyncedFolderPreferenceListener
import com.owncloud.android.ui.dialog.parcel.SyncedFolderParcelable
import com.owncloud.android.ui.navigation.NavigatorActivity
import com.owncloud.android.utils.PermissionUtil
import com.owncloud.android.utils.theme.ThemeUtils
import com.owncloud.android.utils.theme.ViewThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Fragment displaying all auto-synced folders and/or instant upload media folders,
 * hosted by [NavigatorActivity].
 */
@Suppress("TooManyFunctions")
class SyncedFoldersFragment :
    Fragment(),
    SyncedFolderAdapter.ClickListener,
    OnSyncedFolderPreferenceListener {

    @Inject
    lateinit var powerManagementService: PowerManagementService

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var syncedFolderProvider: SyncedFolderProvider

    @Inject
    lateinit var connectivityService: ConnectivityService

    @Inject
    lateinit var backgroundJobManager: BackgroundJobManager

    @Inject
    lateinit var userAccountManager: UserAccountManager

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    @Inject
    lateinit var themeUtils: ThemeUtils

    private var binding: SyncedFoldersFragmentBinding? = null

    lateinit var adapter: SyncedFolderAdapter

    private lateinit var merger: SyncedFolderItemMerger
    private lateinit var saver: SyncedFolderPreferenceSaver
    private lateinit var dialogs: SyncedFoldersDialogs
    private lateinit var deleteUploadedHandler: SyncedFolderDeleteUploadedHandler

    private var uploadWarningCard: UploadWarningCard? = null
    private var dialogFragment: SyncedFolderPreferencesDialogFragment? = null
    private var path: String? = null
    private var type = 0

    private val navigatorActivity: NavigatorActivity?
        get() = getTypedActivity(NavigatorActivity::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activity = activity ?: return
        if (themeUtils.themingEnabled(activity)) {
            activity.theme.applyStyle(R.style.FallbackThemingTheme, true)
        }

        merger = SyncedFolderItemMerger(activity, clock)
        saver = SyncedFolderPreferenceSaver(syncedFolderProvider, backgroundJobManager, clock)
        dialogs = SyncedFoldersDialogs(viewThemeUtils)
        deleteUploadedHandler = SyncedFolderDeleteUploadedHandler(this, syncedFolderProvider, backgroundJobManager)

        if (savedInstanceState == null) {
            activity.intent?.let { handleNotificationExtras(it) }
        }
    }

    private fun handleNotificationExtras(intent: Intent) {
        val accountName = intent.getStringExtra(NotificationWork.KEY_NOTIFICATION_ACCOUNT)
        val currentUser = userAccountManager.user
        if (accountName != null && !accountName.equals(currentUser.accountName, ignoreCase = true)) {
            userAccountManager.setCurrentOwnCloudAccount(accountName)
            navigatorActivity?.setUser(userAccountManager.user)
        }

        path = intent.getStringExtra(MediaFoldersDetectionWork.KEY_MEDIA_FOLDER_PATH)
        type = intent.getIntExtra(MediaFoldersDetectionWork.KEY_MEDIA_FOLDER_TYPE, -1)

        val notificationId = intent.getIntExtra(MediaFoldersDetectionWork.NOTIFICATION_ID, 0)
        val notificationManager = requireContext().getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(notificationId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SyncedFoldersFragmentBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding ?: return
        val activity = requireActivity() as AppCompatActivity

        uploadWarningCard = UploadWarningCard(
            activity,
            powerManagementService,
            syncedFolderProvider,
            backgroundJobManager,
            viewLifecycleOwner.lifecycleScope,
            viewThemeUtils
        ).apply {
            register(activity, binding.autoUploadBatterySaverWarningCard)
        }

        PermissionUtil.requestStoragePermissionIfNeeded(activity)
        setupContent(binding)
        binding.emptyList.emptyListViewAction.setOnClickListener { showHiddenItems() }

        activity.addMenuProvider(
            SyncedFoldersMenuProvider(::onCreateCustomFolderClick, ::onAllSyncFolderDeleteUploadedClick),
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    override fun onResume() {
        super.onResume()
        val context = context ?: return

        setupStoragePermissionWarningBanner()
        PermissionUtil.dismissStoragePermissionDialogFragment(requireActivity() as AppCompatActivity)
        if (PermissionUtil.checkStoragePermission(context)) {
            load(merger.itemsDisplayedPerFolder, false)
        }
        binding?.autoUploadBatterySaverWarningCard?.let { uploadWarningCard?.bind(it) }
    }

    override fun onDestroyView() {
        uploadWarningCard?.unregister(requireContext())
        uploadWarningCard = null
        binding = null
        super.onDestroyView()
    }

    fun setupStoragePermissionWarningBanner() {
        val binding = binding ?: return
        val storagePermissionWarningBanner = binding.storagePermissionWarningBanner.root
        StoragePermissionWarningBannerBinding.bind(storagePermissionWarningBanner).apply {
            setup(requireActivity(), R.string.storage_permission_banner_auto_upload_text)
        }
        storagePermissionWarningBanner.setVisibleIf(!PermissionUtil.checkStoragePermission(requireContext()))
    }

    /**
     * sets up the UI elements and loads all media/synced folders.
     */
    private fun setupContent(binding: SyncedFoldersFragmentBinding) {
        val gridWidth = resources.getInteger(R.integer.media_grid_width)
        val lightVersion = resources.getBoolean(R.bool.syncedFolder_light)
        adapter = SyncedFolderAdapter(
            viewLifecycleOwner.lifecycleScope,
            requireContext(),
            clock,
            gridWidth,
            this,
            lightVersion,
            viewThemeUtils,
            powerManagementService,
            connectivityService
        )

        binding.emptyList.emptyListIcon.setImageResource(R.drawable.nav_synced_folders)
        viewThemeUtils.material.colorMaterialButtonPrimaryFilled(binding.emptyList.emptyListViewAction)
        val lm = GridLayoutManager(requireContext(), gridWidth)
        adapter.setLayoutManager(lm)
        val spacing = resources.getDimensionPixelSize(R.dimen.media_grid_spacing)
        binding.list.addItemDecoration(MediaGridItemDecoration(spacing))
        binding.list.layoutManager = lm
        binding.list.adapter = adapter
    }

    private fun onCreateCustomFolderClick() {
        Log_OC.d(TAG, "Show custom folder dialog")
        if (PermissionUtil.checkStoragePermission(requireContext())) {
            val emptyCustomFolder = SyncedFolderDisplayItem(
                SyncedFolder.UNPERSISTED_ID,
                null,
                null,
                true,
                false,
                true,
                false,
                userAccountManager.user.accountName,
                FileUploadWorker.LOCAL_BEHAVIOUR_FORGET,
                NameCollisionPolicy.ASK_USER.serialize(),
                false,
                clock.currentTime,
                null,
                MediaFolderType.CUSTOM,
                false,
                SubFolderRule.YEAR_MONTH,
                false,
                SyncedFolder.NOT_SCANNED_YET
            )
            onSyncFolderSettingsClick(0, emptyCustomFolder)
        } else {
            PermissionUtil.requestStoragePermissionIfNeeded(requireActivity() as AppCompatActivity)
        }
    }

    private fun showHiddenItems() {
        val binding = binding ?: return
        if (adapter.sectionCount == 0 && adapter.unfilteredSectionCount > adapter.sectionCount) {
            adapter.toggleHiddenItemsVisibility()
            binding.emptyList.emptyListView.visibility = View.GONE
            binding.list.visibility = View.VISIBLE
        }
    }

    /**
     * loads all media/synced folders, adds them to the recycler view adapter and shows the list.
     *
     * @param perFolderMediaItemLimit the amount of media items to be loaded/shown per media folder
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun load(perFolderMediaItemLimit: Int, force: Boolean) {
        if (adapter.itemCount > 0 && !force) {
            return
        }

        val context = context ?: return
        val appCompatActivity = activity as? AppCompatActivity
        showLoadingContent()
        lifecycleScope.launch(Dispatchers.IO) {
            val mediaFolders = MediaProvider.getImageFolders(
                context.contentResolver,
                perFolderMediaItemLimit,
                appCompatActivity,
                false
            )
            mediaFolders.addAll(
                MediaProvider.getVideoFolders(
                    context.contentResolver,
                    perFolderMediaItemLimit,
                    appCompatActivity,
                    false
                )
            )

            val syncedFolderArrayList = syncedFolderProvider.syncedFolders
            val currentAccountSyncedFoldersList: MutableList<SyncedFolder> = ArrayList()
            val user = userAccountManager.user
            for (syncedFolder in syncedFolderArrayList) {
                if (syncedFolder.account == user.accountName) {
                    val folder = File(syncedFolder.localPath)

                    // delete non-existing & disabled synced folders
                    if (!folder.exists() && !syncedFolder.isEnabled) {
                        syncedFolderProvider.deleteSyncedFolder(syncedFolder.id)
                    } else {
                        currentAccountSyncedFoldersList.add(syncedFolder)
                    }
                }
            }

            val syncFolderItems = SyncedFolderItemMerger.sortSyncedFolderItems(
                merger.mergeFolderData(user.accountName, currentAccountSyncedFoldersList, mediaFolders)
            ).filterNotNull()

            withContext(Dispatchers.Main) {
                adapter.setSyncFolderItems(syncFolderItems)
                adapter.notifyDataSetChanged()
                showList()

                if (!TextUtils.isEmpty(path)) {
                    val section = adapter.getSectionByLocalPathAndType(path, type)
                    if (section >= 0) {
                        adapter.get(section)?.let {
                            onSyncFolderSettingsClick(section, it)
                        }
                    }
                }
            }
        }
    }

    /**
     * show recycler view list or the empty message info (in case list is empty).
     */
    private fun showList() {
        val binding = binding ?: return
        binding.list.visibility = View.VISIBLE
        binding.loadingContent.visibility = View.GONE
        checkAndShowEmptyListContent()
    }

    private fun checkAndShowEmptyListContent() {
        val binding = binding ?: return
        if (adapter.sectionCount == 0 && adapter.unfilteredSectionCount > adapter.sectionCount) {
            binding.emptyList.emptyListView.visibility = View.VISIBLE
            val hiddenFoldersCount = adapter.hiddenFolderCount
            showEmptyContent(
                getString(R.string.drawer_synced_folders),
                resources.getQuantityString(
                    R.plurals.synced_folders_show_hidden_folders,
                    hiddenFoldersCount,
                    hiddenFoldersCount
                ),
                resources.getQuantityString(
                    R.plurals.synced_folders_show_hidden_folders,
                    hiddenFoldersCount,
                    hiddenFoldersCount
                )
            )
        } else if (adapter.sectionCount == 0 && adapter.unfilteredSectionCount == 0) {
            binding.emptyList.emptyListView.visibility = View.VISIBLE
            showEmptyContent(
                getString(R.string.drawer_synced_folders),
                getString(R.string.synced_folders_no_results)
            )
        } else {
            binding.emptyList.emptyListView.visibility = View.GONE
        }
    }

    private fun showEmptyContent(headline: String, message: String, action: String) {
        showEmptyContent(headline, message)
        val binding = binding ?: return
        binding.emptyList.emptyListViewAction.text = action
        binding.emptyList.emptyListViewAction.visibility = View.VISIBLE
        binding.emptyList.emptyListViewText.visibility = View.GONE
    }

    private fun showLoadingContent() {
        val binding = binding ?: return
        binding.loadingContent.visibility = View.VISIBLE
        binding.emptyList.emptyListViewAction.visibility = View.GONE
    }

    private fun showEmptyContent(headline: String, message: String) {
        val binding = binding ?: return
        binding.emptyList.emptyListViewAction.visibility = View.GONE
        binding.emptyList.emptyListView.visibility = View.VISIBLE
        binding.list.visibility = View.GONE
        binding.loadingContent.visibility = View.GONE
        binding.emptyList.emptyListViewHeadline.text = headline
        binding.emptyList.emptyListViewText.text = message
        binding.emptyList.emptyListViewText.visibility = View.VISIBLE
        binding.emptyList.emptyListIcon.visibility = View.VISIBLE
    }

    // region SyncedFolderAdapter.ClickListener
    override fun onSyncFolderSettingsClick(section: Int, syncedFolderDisplayItem: SyncedFolderDisplayItem?) {
        check(Looper.getMainLooper().isCurrentThread) { "This must be called on the main thread!" }

        dialogFragment = SyncedFolderPreferencesDialogFragment.newInstance(
            syncedFolderDisplayItem,
            section
        )

        val dialog = dialogFragment ?: return
        if (dialog.isDialogFragmentReady() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val fragmentTransaction = childFragmentManager.beginTransaction().addToBackStack(null)
            dialog.show(fragmentTransaction, SYNCED_FOLDER_PREFERENCES_DIALOG_TAG)
        } else {
            Log_OC.d(TAG, "SyncFolderPreferences dialog not ready")
        }
    }

    override fun onVisibilityToggleClick(section: Int, syncedFolder: SyncedFolderDisplayItem?) {
        if (syncedFolder == null) return

        syncedFolder.isHidden = !syncedFolder.isHidden
        saver.saveOrUpdateSyncedFolder(syncedFolder)
        adapter.setSyncFolderItem(section, syncedFolder)
        checkAndShowEmptyListContent()
    }

    override fun onSyncFolderDeleteUploadedClick(section: Int, syncedFolderDisplayItem: SyncedFolderDisplayItem?) {
        syncedFolderDisplayItem ?: return
        deleteUploadedHandler.showDeleteDialog(syncedFolderDisplayItem)
    }

    override fun showSubFolderWarningDialog() {
        val dialog = ConfirmationDialogFragment.newInstance(
            messageResId = R.string.auto_upload_sub_folder_warning,
            messageArguments = null,
            titleResId = R.string.sync_duplication,
            titleIconId = R.drawable.ic_info,
            positiveButtonTextId = R.string.dialog_close,
            negativeButtonTextId = -1,
            neutralButtonTextId = -1
        )

        if (dialog.isDialogFragmentReady()) {
            dialog.show(childFragmentManager, SUB_FOLDER_WARNING_DIALOG_TAG)
        }
    }

    override fun onSyncStatusToggleClick(section: Int, item: SyncedFolderDisplayItem?) {
        item ?: return

        // Ensure the item is persisted
        if (item.id <= SyncedFolder.UNPERSISTED_ID) {
            syncedFolderProvider.storeSyncedFolder(item)
                .takeIf { it != -1L }
                ?.let { item.id = it }
        } else {
            syncedFolderProvider.updateSyncedFolderEnabled(item.id, item.isEnabled)
        }

        if (item.isEnabled) {
            Log_OC.d(TAG, "auto-upload configuration sync status is enabled: " + item.remotePath)
            backgroundJobManager.startAutoUpload(item, overridePowerSaving = false)
            showBatteryOptimizationDialogIfNeeded()
            return
        }

        Log_OC.d(TAG, "auto-upload configuration sync status is disabled: " + item.remotePath)

        lifecycleScope.launch(Dispatchers.IO) {
            FileUploadHelper.instance().removeEntityFromUploadEntities(item.id)
        }
    }
    // endregion

    // region OnSyncedFolderPreferenceListener
    override fun onSaveSyncedFolderPreference(syncedFolder: SyncedFolderParcelable?) {
        if (syncedFolder == null) {
            return
        }

        // custom folders newly created aren't in the list already,
        // so triggering a refresh
        if (MediaFolderType.CUSTOM == syncedFolder.type && syncedFolder.id == SyncedFolder.UNPERSISTED_ID) {
            val newCustomFolder = saver.createCustomFolderItem(syncedFolder)
            saver.saveOrUpdateSyncedFolder(newCustomFolder)
            adapter.addSyncFolderItem(newCustomFolder)
        } else {
            val item = adapter.get(syncedFolder.section) ?: return
            saver.updateSyncedFolderItem(
                item,
                syncedFolder.id,
                syncedFolder.localPath,
                syncedFolder.remotePath,
                syncedFolder.isWifiOnly,
                syncedFolder.isChargingOnly,
                syncedFolder.isExisting,
                syncedFolder.isSubfolderByDate,
                syncedFolder.uploadAction,
                syncedFolder.nameCollisionPolicy.serialize(),
                syncedFolder.isEnabled,
                syncedFolder.subFolderRule,
                syncedFolder.isExcludeHidden
            )
            saver.saveOrUpdateSyncedFolder(item)

            adapter.notifyItemChanged(adapter.getSectionHeaderIndex(syncedFolder.section))
        }
        dialogFragment = null
        if (syncedFolder.isEnabled) {
            showBatteryOptimizationDialogIfNeeded()
        }
    }

    override fun onCancelSyncedFolderPreference() {
        dialogFragment = null
    }

    override fun onDeleteSyncedFolderPreference(syncedFolder: SyncedFolderParcelable?) {
        if (syncedFolder == null) {
            return
        }

        Log_OC.d(TAG, "deleting auto upload configuration: " + syncedFolder.remotePath)

        lifecycleScope.launch(Dispatchers.IO) {
            FileUploadHelper.instance().removeEntityFromUploadEntities(syncedFolder.id)
            syncedFolderProvider.deleteSyncedFolder(syncedFolder.id)
            withContext(Dispatchers.Main) {
                adapter.removeItem(syncedFolder.section)
            }
        }
    }
    // endregion

    private fun onAllSyncFolderDeleteUploadedClick() {
        deleteUploadedHandler.showDeleteAllDialog(userAccountManager.user)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SyncedFolderPreferencesDialogFragment.REQUEST_CODE__SELECT_REMOTE_FOLDER &&
            resultCode == Activity.RESULT_OK &&
            dialogFragment != null
        ) {
            val chosenFolder: OCFile? = FolderPickerActivity.EXTRA_FOLDER?.let {
                data?.getParcelableArgument(it, OCFile::class.java)
            }
            dialogFragment?.setRemoteFolderSummary(chosenFolder?.remotePath)
        } else if (
            requestCode == SyncedFolderPreferencesDialogFragment.REQUEST_CODE__SELECT_LOCAL_FOLDER &&
            resultCode == Activity.RESULT_OK &&
            dialogFragment != null
        ) {
            val localPath = data!!.getStringExtra(UploadFilesActivity.EXTRA_CHOSEN_FILES)
            dialogFragment!!.setLocalFolderSummary(localPath)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun buildPowerCheckDialog(): AlertDialog = dialogs.buildPowerCheckDialog(requireContext())

    @VisibleForTesting
    fun showPowerCheckDialog() {
        buildPowerCheckDialog().show()
    }

    private fun showBatteryOptimizationDialogIfNeeded() {
        val activity = activity ?: return
        if (!BatteryOptimizationHelper.isBatteryOptimizationEnabled(activity)) {
            Log_OC.d(TAG, "battery optimization is disabled")
            return
        }

        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log_OC.w(TAG, "Fragment not resumed, skipping battery dialog")
            return
        }

        dialogs.showBatteryOptimizationDialog(activity)
    }

    companion object {
        private const val SYNCED_FOLDER_PREFERENCES_DIALOG_TAG = "SYNCED_FOLDER_PREFERENCES_DIALOG"
        private const val SUB_FOLDER_WARNING_DIALOG_TAG = "SUB_FOLDER_WARNING_DIALOG_TAG"
        private val TAG = SyncedFoldersFragment::class.java.simpleName
    }
}
