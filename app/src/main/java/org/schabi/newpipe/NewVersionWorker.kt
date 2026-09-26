package org.schabi.newpipe

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.util.ReleaseVersionUtil.coerceUpdateCheckExpiry
import org.schabi.newpipe.util.ReleaseVersionUtil.isLastUpdateCheckExpired
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: HttpStatusException) {
            Log.w(TAG, "GitHub release request failed: ${e.statusCode}", e)
            if (e.statusCode == 403 || e.statusCode == 429 || e.statusCode >= 500) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (e: InvalidResponseException) {
            Log.w(TAG, "Invalid AlterTube release response", e)
            Result.failure()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch AlterTube releases", e)
            Result.retry()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException while checking AlterTube releases", e)
            Result.failure()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid AlterTube release data", e)
            Result.failure()
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        val manual = inputData.getBoolean(IS_MANUAL, false)
        if (!manual) {
            if (!isAutomaticCheckEnabled()) {
                return
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0L)
            if (!isLastUpdateCheckExpired(expiry)) {
                return
            }
        }

        val response = DownloaderImpl.getInstance().get(RELEASES_API_URL)
        if (response.responseCode() !in 200..299) {
            throw HttpStatusException(response.responseCode())
        }
        handleResponse(response, manual)
    }

    private fun handleResponse(response: Response, manual: Boolean) {
        val releases = try {
            JsonParser.array().from(response.responseBody())
        } catch (e: Exception) {
            throw InvalidResponseException("Invalid GitHub release response", e)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            val expiry = coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit {
                putLong(applicationContext.getString(R.string.update_expiry_key), expiry)
            }
        } catch (e: Exception) {
            if (DEBUG) {
                Log.w(TAG, "Could not save update check expiry", e)
            }
        }

        val includePreRelease = prefs.getBoolean(
            applicationContext.getString(R.string.show_prerelease_key), false
        )
        var selectedRelease: JsonObject? = null
        for (index in 0 until releases.size) {
            if (isStopped) {
                return
            }
            val release = releases.getObject(index)
            if (release.getBoolean("draft", false)
                || (!includePreRelease && release.getBoolean("prerelease", false))
                || releaseVersion(release) == null
            ) {
                continue
            }
            if (selectedRelease == null || isNewerRelease(release, selectedRelease)) {
                selectedRelease = release
            }
        }

        val release = selectedRelease ?: return
        val version = releaseVersion(release) ?: return
        val releaseUrl = releaseUrl(release)?.takeIf(::isAllowedReleaseUrl) ?: return
        val currentVersion = parseVersion(BuildConfig.VERSION_NAME)
        val latestVersion = parseVersion(version)
        if (compareVersions(currentVersion, latestVersion) >= 0) {
            clearPendingUpdate(applicationContext)
            if (manual) {
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(
                        applicationContext,
                        R.string.app_update_unavailable_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }

        savePendingUpdate(version, releaseUrl)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(UPDATE_AVAILABLE_ACTION)
        )
        val lastNotifiedVersion = prefs.getString(
            applicationContext.getString(R.string.update_last_notified_version_key), null
        )
        if (lastNotifiedVersion != version) {
            prefs.edit {
                putString(
                    applicationContext.getString(R.string.update_last_notified_version_key),
                    version
                )
            }
            showUpdateNotification(version, releaseUrl)
        }
    }

    private fun savePendingUpdate(version: String, url: String) {
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
            putString(applicationContext.getString(R.string.update_pending_version_key), version)
            putString(applicationContext.getString(R.string.update_pending_url_key), url)
        }
    }

    private fun showUpdateNotification(version: String, releaseUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            2000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
                    or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
        )
        val notification = NotificationCompat.Builder(
            applicationContext,
            applicationContext.getString(R.string.app_update_notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setContentTitle(applicationContext.getString(R.string.app_update_available_title))
            .setContentText(
                applicationContext.getString(R.string.app_update_available_message, version)
            )
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(2000, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Update notification permission is not granted", e)
        }
    }

    private fun isNewerRelease(newRelease: JsonObject, currentRelease: JsonObject): Boolean {
        val newVersion = releaseVersion(newRelease)?.let(::parseVersion) ?: return false
        val currentVersion = releaseVersion(currentRelease)?.let(::parseVersion) ?: return false
        return compareVersions(newVersion, currentVersion) > 0
    }

    private fun releaseVersion(release: JsonObject): String? {
        val raw = release.getString("tag_name", "")
            .ifBlank { release.getString("name", "") }
        val match = VERSION_PATTERN.find(raw) ?: return null
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()
        val suffix = raw.substring(match.range.last + 1).lowercase(Locale.ROOT)
        val beta = if (suffix.startsWith("-beta")) {
            Regex("-beta(\\d+)", RegexOption.IGNORE_CASE).find(suffix)?.groupValues?.get(1)?.toIntOrNull()
        } else {
            null
        }
        return buildString {
            append(major).append('.').append(minor).append('.').append(patch)
            if (beta != null) {
                append("-beta").append(beta)
            }
        }
    }

    private fun releaseUrl(release: JsonObject): String? {
        return release.getString("html_url", "").ifBlank { null }
    }

    private class HttpStatusException(val statusCode: Int) : IOException()

    private class InvalidResponseException(message: String, cause: Throwable) :
        IOException(message, cause)

    data class PendingUpdate(val version: String, val url: String)

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val RELEASES_API_URL =
            "https://api.github.com/repositories/1380977205/releases"
        private const val PERIODIC_WORK_NAME = "altertube_version_check"
        private const val ONE_TIME_WORK_NAME = "altertube_version_check_now"
        private const val PERIODIC_WORK_TAG = "altertube_version_check_periodic"
        private const val ONE_TIME_WORK_TAG = "altertube_version_check_now"
        const val UPDATE_AVAILABLE_ACTION = "org.schabi.newpipe.UPDATE_AVAILABLE"
        private const val IS_MANUAL = "isManual"
        private const val PERIODIC_INTERVAL_HOURS = 6L
        private val VERSION_PATTERN = Regex(
            "(?:altertube\\s+)?v?(\\d+)\\.(\\d+)\\.(\\d+)",
            RegexOption.IGNORE_CASE
        )

        @JvmStatic
        fun initializePeriodicChecks(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!isAutomaticCheckEnabled(context)) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequest.Builder(
                NewVersionWorker::class.java,
                PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK_TAG)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            if (!isManual && !isAutomaticCheckEnabled(context)) {
                return
            }
            val request = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .addTag(ONE_TIME_WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                if (isManual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        @JvmStatic
        fun consumePendingUpdate(context: Context): PendingUpdate? {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val version = prefs.getString(context.getString(R.string.update_pending_version_key), null)
            val url = prefs.getString(context.getString(R.string.update_pending_url_key), null)
            if (version.isNullOrBlank() || url.isNullOrBlank() || !isAllowedReleaseUrl(url)) {
                clearPendingUpdate(context)
                return null
            }
            try {
                if (compareVersions(parseVersion(BuildConfig.VERSION_NAME), parseVersion(version)) >= 0) {
                    clearPendingUpdate(context)
                    return null
                }
            } catch (e: IllegalArgumentException) {
                clearPendingUpdate(context)
                return null
            }
            return PendingUpdate(version, url)
        }

        @JvmStatic
        fun clearPendingUpdate(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                remove(context.getString(R.string.update_pending_version_key))
                remove(context.getString(R.string.update_pending_url_key))
            }
        }

        private fun isAllowedReleaseUrl(value: String): Boolean {
            return try {
                val uri = Uri.parse(value)
                val path = uri.path
                "https".equals(uri.scheme, ignoreCase = true)
                    && "github.com".equals(uri.host, ignoreCase = true)
                    && path?.startsWith("/DezFix/AlterTube/releases/") == true
            } catch (e: Exception) {
                false
            }
        }

        private fun isAutomaticCheckEnabled(context: Context): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                context.getString(R.string.update_app_key), true
            )
        }
    }
}

data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val betaVersion: Int?
)

private fun parseVersion(versionString: String): Version {
    val match = Regex(
        "(?:altertube\\s+)?v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta(\\d+))?",
        RegexOption.IGNORE_CASE
    ).find(versionString) ?: throw IllegalArgumentException("Invalid version: $versionString")
    return Version(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
        match.groupValues[4].ifBlank { null }?.toInt()
    )
}

private fun compareVersions(first: Version, second: Version): Int {
    val mainComparison = compareValuesBy(first, second, { it.major }, { it.minor }, { it.patch })
    if (mainComparison != 0) {
        return mainComparison
    }
    return when {
        first.betaVersion == null && second.betaVersion == null -> 0
        first.betaVersion == null -> 1
        second.betaVersion == null -> -1
        else -> first.betaVersion.compareTo(second.betaVersion)
    }
}
