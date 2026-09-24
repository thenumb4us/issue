package com.issue.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Telephony
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object IssueRuleEngine {

    private const val CONTROL_PREFS = "issue_controls"

    data class EnforcementResult(
        val state: String,
        val suspendedCount: Int,
        val dailyUsedMinutes: Long?,
        val dailyRemainingMinutes: Long?,
        val bedtimeActive: Boolean,
        val schoolActive: Boolean,
        val limitReached: Boolean
    )

    fun evaluateAndApply(
        context: Context
    ): EnforcementResult {
        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )

        val dailyLimit = prefs.getInt(
            "daily_limit",
            180
        ).coerceIn(15, 1440)

        val used = DeviceSyncManager.usageMinutes(context)
        val remaining = used?.let {
            (dailyLimit - it).coerceAtLeast(0)
        }

        val limitReached = used != null &&
            used >= dailyLimit

        val bedtime = isTimeWindowActive(
            prefs.getString("bed_start", "22:00") ?: "22:00",
            prefs.getString("bed_end", "07:00") ?: "07:00"
        )

        val manualSchool = prefs.getBoolean(
            "school_mode",
            false
        )

        val scheduledSchool =
            prefs.getBoolean(
                "school_schedule_enabled",
                false
            ) &&
                currentDayCode() in readStringArray(
                    prefs.getString(
                        "school_schedule_days",
                        "[]"
                    ) ?: "[]"
                ) &&
                isTimeWindowActive(
                    prefs.getString(
                        "school_schedule_start",
                        "08:00"
                    ) ?: "08:00",
                    prefs.getString(
                        "school_schedule_end",
                        "15:00"
                    ) ?: "15:00"
                )

        val schoolActive = manualSchool || scheduledSchool

        val state = when {
            limitReached -> "SCREEN_TIME_LOCK"
            bedtime -> "BEDTIME_LOCK"
            schoolActive -> "SCHOOL_MODE"
            else -> "NORMAL"
        }

        maybeWarnScreenTime(
            context,
            remaining,
            limitReached
        )

        val manager = DeviceOwnerManager(context)

        manager.setIssueUninstallBlocked(
            prefs.getBoolean(
                "uninstall_protection",
                true
            )
        )

        manager.setPrivateDns(
            prefs.getBoolean(
                "web_filter_enabled",
                false
            ),
            prefs.getString(
                "safe_dns_host",
                ""
            ) ?: ""
        )

        manager.setWifiConfigLocked(
            prefs.getBoolean(
                "wifi_config_locked",
                false
            )
        )

        val explicitBlocked = readStringArray(
            prefs.getString(
                "blocked_packages",
                "[]"
            ) ?: "[]"
        ).toMutableSet()

        if (
            prefs.getBoolean(
                "purchase_block",
                false
            )
        ) {
            explicitBlocked.add("com.android.vending")
        }

        val usageByPackage =
            DeviceSyncManager.usageByPackageMinutes(
                context
            )

        val appLimits = readAppLimits(
            prefs.getString(
                "app_limits",
                "{}"
            ) ?: "{}"
        )

        for ((packageName, limit) in appLimits) {
            val minutes = usageByPackage[packageName]
                ?: 0L

            if (minutes >= limit) {
                explicitBlocked.add(packageName)
            }
        }

        val allowed = readStringArray(
            prefs.getString(
                "allowed_packages",
                "[]"
            ) ?: "[]"
        ).toMutableSet()

        allowed.add(context.packageName)
        protectedPackages(context).forEach {
            allowed.add(it)
        }

        val userPackages = installedUserPackages(context)
        val desired = explicitBlocked.toMutableSet()

        if (state != "NORMAL") {
            for (packageName in userPackages) {
                if (packageName !in allowed) {
                    desired.add(packageName)
                }
            }
        }

        val approved = readStringArray(
            prefs.getString(
                "approved_packages",
                "[]"
            ) ?: "[]"
        ).toSet()

        if (
            prefs.getBoolean(
                "block_new_apps",
                false
            ) && approved.isNotEmpty()
        ) {
            for (packageName in userPackages) {
                if (
                    packageName !in approved &&
                    packageName !in allowed
                ) {
                    desired.add(packageName)
                }
            }
        }

        desired.remove(context.packageName)
        allowed.forEach { desired.remove(it) }

        val appliedCount = syncSuspendedPackages(
            context,
            manager,
            desired
        )

        prefs.edit()
            .putString(
                "enforcement_state",
                state
            )
            .putInt(
                "suspended_count",
                appliedCount
            )
            .putLong(
                "last_enforcement_check",
                System.currentTimeMillis()
            )
            .apply()

        return EnforcementResult(
            state = state,
            suspendedCount = appliedCount,
            dailyUsedMinutes = used,
            dailyRemainingMinutes = remaining,
            bedtimeActive = bedtime,
            schoolActive = schoolActive,
            limitReached = limitReached
        )
    }

    private fun syncSuspendedPackages(
        context: Context,
        manager: DeviceOwnerManager,
        desired: Set<String>
    ): Int {
        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )

        if (!manager.isDeviceOwner()) {
            return 0
        }

        val previous = readStringArray(
            prefs.getString(
                "issue_suspended_packages",
                "[]"
            ) ?: "[]"
        ).toSet()

        val toResume = previous - desired
        val toSuspend = desired - previous

        if (toResume.isNotEmpty()) {
            manager.setPackagesSuspended(
                toResume,
                false
            )
        }

        val failed = if (toSuspend.isNotEmpty()) {
            manager.setPackagesSuspended(
                toSuspend,
                true
            )
        } else {
            emptySet()
        }

        val applied = (
            (previous - toResume) +
                (toSuspend - failed)
            )
            .filter { it in desired }
            .toSet()

        prefs.edit()
            .putString(
                "issue_suspended_packages",
                JSONArray(applied.toList()).toString()
            )
            .apply()

        return applied.size
    }

    private fun maybeWarnScreenTime(
        context: Context,
        remaining: Long?,
        limitReached: Boolean
    ) {
        if (remaining == null) {
            return
        }

        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )

        val day = SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        ).format(Date())

        if (
            remaining in 1..30 &&
            prefs.getString(
                "warning_30_day",
                ""
            ) != day
        ) {
            IssueNotificationHelper.alert(
                context,
                3030,
                "30 minutes remaining",
                "Screen-time limit will be reached soon."
            )

            prefs.edit()
                .putString(
                    "warning_30_day",
                    day
                )
                .apply()
        }

        if (
            limitReached &&
            prefs.getString(
                "limit_reached_day",
                ""
            ) != day
        ) {
            IssueNotificationHelper.alert(
                context,
                3031,
                "Screen-time limit reached",
                "Parent screen-time rules are now active."
            )

            prefs.edit()
                .putString(
                    "limit_reached_day",
                    day
                )
                .apply()
        }
    }

    private fun installedUserPackages(
        context: Context
    ): Set<String> {
        return try {
            context.packageManager
                .getInstalledApplications(0)
                .filter {
                    it.flags and ApplicationInfo.FLAG_SYSTEM == 0
                }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun protectedPackages(
        context: Context
    ): Set<String> {
        val result = mutableSetOf<String>()

        try {
            val home = context.packageManager
                .resolveActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                    },
                    0
                )
                ?.activityInfo
                ?.packageName

            if (!home.isNullOrBlank()) {
                result.add(home)
            }
        } catch (_: Exception) {
        }

        try {
            val telecom = context.getSystemService(
                Context.TELECOM_SERVICE
            ) as TelecomManager

            telecom.defaultDialerPackage
                ?.takeIf { it.isNotBlank() }
                ?.let { result.add(it) }
        } catch (_: Exception) {
        }

        try {
            Telephony.Sms.getDefaultSmsPackage(context)
                ?.takeIf { it.isNotBlank() }
                ?.let { result.add(it) }
        } catch (_: Exception) {
        }

        return result
    }

    private fun readStringArray(
        raw: String
    ): List<String> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index, "")
                        .trim()

                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readAppLimits(
        raw: String
    ): Map<String, Int> {
        return try {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optInt(key, 0)
                    if (value > 0) {
                        put(key, value)
                    }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isTimeWindowActive(
        start: String,
        end: String
    ): Boolean {
        val startMinutes = parseMinutes(start)
            ?: return false

        val endMinutes = parseMinutes(end)
            ?: return false

        if (startMinutes == endMinutes) {
            return false
        }

        val now = Calendar.getInstance()
        val nowMinutes =
            now.get(Calendar.HOUR_OF_DAY) * 60 +
                now.get(Calendar.MINUTE)

        return if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes ||
                nowMinutes < endMinutes
        }
    }

    private fun parseMinutes(
        value: String
    ): Int? {
        val parts = value.split(":")
        if (parts.size != 2) {
            return null
        }

        val hour = parts[0].toIntOrNull()
            ?: return null

        val minute = parts[1].toIntOrNull()
            ?: return null

        if (hour !in 0..23 || minute !in 0..59) {
            return null
        }

        return hour * 60 + minute
    }

    private fun currentDayCode(): String {
        return when (
            Calendar.getInstance().get(
                Calendar.DAY_OF_WEEK
            )
        ) {
            Calendar.MONDAY -> "MON"
            Calendar.TUESDAY -> "TUE"
            Calendar.WEDNESDAY -> "WED"
            Calendar.THURSDAY -> "THU"
            Calendar.FRIDAY -> "FRI"
            Calendar.SATURDAY -> "SAT"
            else -> "SUN"
        }
    }
}
