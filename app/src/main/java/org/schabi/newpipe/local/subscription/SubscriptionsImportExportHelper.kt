package org.schabi.newpipe.local.subscription

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.local.subscription.services.SubscriptionsExportService
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.CHANNEL_LIST_MODE
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_MODE
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_TEXT
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.KEY_VALUE
import org.schabi.newpipe.local.subscription.services.SubscriptionsImportService.PREVIOUS_EXPORT_MODE
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.Constants
import org.schabi.newpipe.youtube.YouTubeSubscriptionImportHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shares the subscription JSON import/export flow between fragments.
 *
 * This helper must be created before its fragment reaches the created state because it registers
 * activity result launchers.
 */
class SubscriptionsImportExportHelper(private val fragment: Fragment) {
    @Suppress("unused")
    private val detailsCoordinator = SubscriptionImportDetailsCoordinator(fragment)

    private val requestExportLauncher =
        fragment.registerForActivityResult(StartActivityForResult(), this::requestExportResult)
    private val requestImportLauncher =
        fragment.registerForActivityResult(StartActivityForResult(), this::requestImportResult)

    fun importSubscriptions() {
        NoFileManagerSafeGuard.launchSafe(
            requestImportLauncher,
            StoredFileHelper.getPicker(fragment.requireContext(), JSON_MIME_TYPE),
            TAG,
            fragment.requireContext()
        )
    }

    fun importChannelListFromClipboard(): Int {
        val clipboard = ContextCompat.getSystemService(
            fragment.requireContext(),
            ClipboardManager::class.java
        )
        val clip = clipboard?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            return R.string.import_channel_list_empty
        }
        if (!YouTubeSubscriptionImportHelper.isPastedChannelListSizeSupported(text)) {
            return R.string.import_channel_list_too_large
        }
        fragment.requireContext().startService(
            Intent(fragment.requireContext(), SubscriptionsImportService::class.java)
                .putExtra(KEY_MODE, CHANNEL_LIST_MODE)
                .putExtra(KEY_TEXT, text)
                .putExtra(Constants.KEY_SERVICE_ID, ServiceList.YouTube.getServiceId())
        )
        return 0
    }

    fun exportSubscriptions() {
        val date = SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(Date())
        val exportName = "newpipe_subscriptions_$date.json"

        NoFileManagerSafeGuard.launchSafe(
            requestExportLauncher,
            StoredFileHelper.getNewPicker(
                fragment.requireContext(),
                exportName,
                JSON_MIME_TYPE,
                null
            ),
            TAG,
            fragment.requireContext()
        )
    }

    private fun requestExportResult(result: ActivityResult) {
        val outputUri = result.data?.data
        if (outputUri != null && result.resultCode == Activity.RESULT_OK) {
            fragment.requireContext().startService(
                Intent(fragment.requireContext(), SubscriptionsExportService::class.java)
                    .putExtra(SubscriptionsExportService.KEY_FILE_PATH, outputUri)
            )
        }
    }

    private fun requestImportResult(result: ActivityResult) {
        val inputUri = result.data?.data
        if (inputUri != null && result.resultCode == Activity.RESULT_OK) {
            fragment.requireContext().startService(
                Intent(fragment.requireContext(), SubscriptionsImportService::class.java)
                    .putExtra(KEY_MODE, PREVIOUS_EXPORT_MODE)
                    .putExtra(KEY_VALUE, inputUri)
            )
        }
    }

    private companion object {
        const val JSON_MIME_TYPE = "application/json"
        val TAG: String = SubscriptionsImportExportHelper::class.java.simpleName
    }
}
