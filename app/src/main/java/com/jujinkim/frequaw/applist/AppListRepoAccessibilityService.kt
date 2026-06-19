package com.jujinkim.frequaw.applist

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jujinkim.frequaw.FrequawApp
import com.jujinkim.frequaw.data.FrequawDataHelper
import com.jujinkim.frequaw.model.AppInfo
import com.jujinkim.frequaw.model.AppInfo.Companion.launchedCountsBy30mSize
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class AppListRepoAccessibilityService : AppListRepo {
    private var lastUpdatedPackage = ""
    var lastUpdateTime = "--" ; private set

    override fun getAppList(): MutableList<AppInfo> {
        Log.d(FrequawApp.TAG_DEBUG, "Get App List from AccessibilityService Repo")

        val apps: MutableList<AppInfo> = mutableListOf()
        apps.addAll(FrequawDataHelper.load().appInfos.map { AppInfo.fromData(it) })
        if (apps.isEmpty()) apps.addAll(createInitAppInfoList())

        apps.forEach { it.sortValue = it.launchedTimes }

        return apps
    }

    override fun updateAndSaveAppInfo(packageName: String,
                                      lastLaunched: Long,
                                      ignoreInMomentRequest: Boolean) {

        val apps = getAppList()

        val appInfo = apps.firstOrNull { info -> info.packageName == packageName }

        // if the same package requested to be updated between very short time, ignore it.
        if (ignoreInMomentRequest && appInfo != null) {
            if (abs(appInfo.lastLaunched - lastLaunched) < 500) return // 0.5sec is enough
        }

        if (appInfo == null) {
            apps.add(AppInfo(packageName, lastLaunched, LongArray(launchedCountsBy30mSize)))
            lastUpdatedPackage = packageName
        } else if (appInfo.packageName != lastUpdatedPackage) {
            appInfo.lastLaunched = lastLaunched
            lastUpdatedPackage = packageName

            // update launched count by every 30 minutes
            val cal = Calendar.getInstance()
            val curDayOfWeek = cal[Calendar.DAY_OF_WEEK] - 1
            val curHour = cal[Calendar.HOUR_OF_DAY]
            val curMinute = cal[Calendar.MINUTE]
            val idx30m = curDayOfWeek * 48 + curHour * 2 + curMinute / 30
            if (idx30m < appInfo.launchedCountsBy30m.size) {
                appInfo.launchedCountsBy30m[idx30m] =
                    (appInfo.launchedCountsBy30m[idx30m] + 1).coerceAtMost(Long.MAX_VALUE)
            }

            // remove duplicated - merge every duplicate into the first kept instance
            val duplicates = apps.filter { info -> info.packageName == appInfo.packageName }
            if (duplicates.size >= 2) {
                val keep = duplicates.first()
                duplicates.drop(1).forEach { dup ->
                    // integrate two appInfo
                    keep.lastLaunched = max(keep.lastLaunched, dup.lastLaunched)
                    for (i in 0 until keep.launchedCountsBy30m.size) {
                        if (i >= dup.launchedCountsBy30m.size) break
                        keep.launchedCountsBy30m[i] =
                            (keep.launchedCountsBy30m[i] + dup.launchedCountsBy30m[i])
                                .coerceAtMost(Long.MAX_VALUE)
                    }
                    apps.remove(dup)
                }
            }
            Log.d(FrequawApp.TAG_DEBUG, "APP Updated: $packageName")
        }

        saveAppList(apps)

        lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private val saveHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingApps: List<AppInfo>? = null
    private val saveRunnable = Runnable {
        val toSave = pendingApps ?: return@Runnable
        pendingApps = null
        FrequawDataHelper
            .load()
            .apply {
                appInfos.clear()
                appInfos.addAll(toSave.map { it.toData() })
            }.run {
                FrequawDataHelper.save(this)
            }
    }
    override fun saveAppList(apps: List<AppInfo>) {
        // keep the latest snapshot, debounce, don't drop newer data
        pendingApps = apps.toList()
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 1000) // don't save too frequently
    }

    override fun clearAppList() {
        // drop any pending debounced save so it can't resurrect cleared data
        saveHandler.removeCallbacks(saveRunnable)
        pendingApps = null

        FrequawDataHelper
            .load()
            .apply {
                appInfos.clear()
            }.run {
                FrequawDataHelper.save(this)
            }
    }

    override fun resetOnAppInfo(packageName: String) {
        val apps = getAppList()
        val appInfo = apps.find { appInfo -> appInfo.packageName == packageName }

        appInfo?.apply {
            lastLaunched = 0
            for(i in launchedCountsBy30m.indices) {
                launchedCountsBy30m[i] = 0
            }
        }

        saveAppList(apps)
        lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    /**
     * Create a list of AppInfo, all installed applications but no launched time info
     */
    private fun createInitAppInfoList() =
        AppListManager.getInstalledApps()
            .map { packageName -> AppInfo(packageName, 0, LongArray(launchedCountsBy30mSize)) }
}