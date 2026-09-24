package com.issue.app

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DeviceSyncManager {

    private const val CONTROL_PREFS = "issue_controls"

    data class SyncResult(
        val dailyLimitMinutes: Int,
        val schoolMode: Boolean,
        val bedtimeStart: String,
        val bedtimeEnd: String,
        val policyVersion: Int,
        val commandCount: Int
    )

    fun sync(
        context: Context,
        deviceToken: String
    ): SyncResult {
        require(deviceToken.isNotBlank()) {
            "Device authorization is missing"
        }

        val response = ApiClient.get(
            "/api/device/sync",
            deviceToken
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                apiMessage(
                    response.body,
                    "Device sync failed"
                )
            )
        }

        val json = JSONObject(response.body)
        val policy = json.optJSONObject("policy")
            ?: throw IllegalStateException("Device sync response is missing its policy")
        val childId = json.optString("childId", "")
        require(childId.isNotBlank() && policy.optString("childId") == childId) {
            "Policy does not match enrolled child"
        }
        applyPolicy(context, policy)

        val commands = json.optJSONArray("commands")
        var handled = 0

        if (commands != null) {
            for (index in 0 until commands.length()) {
                val command = commands.optJSONObject(index)
                    ?: continue

                val commandId = command.optString("id", "")
                val type = command.optString("type", "")
                val payload = command.optJSONObject("payload")
                    ?: JSONObject()

                var status = "IGNORED"
                var result = "Unsupported command"

                when (type) {
                    "SYNC_POLICY" -> {
                        try {
                            require(payload.optString("childId") == childId) {
                                "Policy does not match enrolled child"
                            }
                            val applied = applyPolicy(context, payload)
                            status = if (applied) "APPLIED" else "IGNORED"
                            result = if (applied) "Policy applied" else "Policy already current or superseded"
                        } catch (error: Exception) {
                            status = "FAILED"
                            result = error.message ?: "Invalid policy"
                        }
                    }

                    "LOCK_DEVICE" -> {
                        val ok = DeviceOwnerManager(context)
                            .lockDevice()

                        status = if (ok) {
                            "APPLIED"
                        } else {
                            "FAILED"
                        }

                        result = if (ok) {
                            "Device locked"
                        } else {
                            "Device Admin or Device Owner permission required"
                        }
                    }
                }

                if (commandId.isNotBlank()) {
                    try {
                        ApiClient.post(
                            "/api/device/ack",
                            JSONObject().apply {
                                put("commandId", commandId)
                                put("status", status)
                                put("result", result)
                            },
                            deviceToken
                        )
                    } catch (_: Exception) {
                    }
                }

                handled++
            }
        }

        val enforcement = try {
            IssueRuleEngine.evaluateAndApply(context)
        } catch (_: Exception) {
            null
        }

        IssueLocationService.syncWithPolicy(context)

        try {
            postUsageReport(
                context,
                deviceToken
            )
        } catch (_: Exception) {
        }

        postStatus(
            context,
            deviceToken,
            enforcement
        )

        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )

        return SyncResult(
            dailyLimitMinutes = prefs.getInt(
                "daily_limit",
                180
            ),
            schoolMode = prefs.getBoolean(
                "school_mode",
                false
            ),
            bedtimeStart = prefs.getString(
                "bed_start",
                "22:00"
            ) ?: "22:00",
            bedtimeEnd = prefs.getString(
                "bed_end",
                "07:00"
            ) ?: "07:00",
            policyVersion = prefs.getInt(
                "policy_version",
                0
            ),
            commandCount = handled
        )
    }

    fun sendSos(
        context: Context,
        deviceToken: String,
        note: String = "Child requested help"
    ) {
        val response = ApiClient.post(
            "/api/device/sos",
            JSONObject().apply {
                put("note", note)
            },
            deviceToken
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                apiMessage(
                    response.body,
                    "Could not send SOS"
                )
            )
        }

        context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putLong(
                "last_sos",
                System.currentTimeMillis()
            )
            .apply()
    }

    fun postLocation(
        context: Context,
        deviceToken: String,
        location: Location
    ) {
        val response = ApiClient.post(
            "/api/device/location",
            JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                if (location.hasAccuracy()) {
                    put("accuracyMeters", location.accuracy)
                }
                if (location.hasSpeed()) {
                    put("speedMps", location.speed)
                }
                put("provider", location.provider ?: "unknown")
            },
            deviceToken
        )

        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                apiMessage(
                    response.body,
                    "Location update failed"
                )
            )
        }

        context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putLong(
                "last_location_upload",
                System.currentTimeMillis()
            )
            .apply()
    }

    fun usageMinutes(context: Context): Long? {
        if (!hasUsageAccess(context)) {
            return null
        }

        return usageByPackageMinutes(context)
            .values
            .sum()
    }

    fun usageByPackageMinutes(
        context: Context
    ): Map<String, Long> {
        if (!hasUsageAccess(context)) {
            return emptyMap()
        }

        val start = startOfToday()
        val manager = context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            start,
            System.currentTimeMillis()
        )
            .groupBy { it.packageName }
            .mapValues { (_, rows) ->
                rows.sumOf {
                    it.totalTimeInForeground
                } / 60_000L
            }
            .filterValues { it > 0L }
    }

    fun foregroundPackage(
        context: Context
    ): String? {
        if (!hasUsageAccess(context)) {
            return null
        }

        val now = System.currentTimeMillis()
        val manager = context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 10 * 60_000L,
            now
        )
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(
            Context.APP_OPS_SERVICE
        ) as AppOpsManager

        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun hasLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    // Validate the complete server policy before writing any preferences. A queued
    // command can arrive after a newer policy in the same sync response.
    private fun validatePolicy(policy: JSONObject): Int {
        fun integer(key: String, min: Int, max: Int): Int {
            val value = policy.opt(key)
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() % 1.0 == 0.0 &&
                value.toDouble() in min.toDouble()..max.toDouble()) {
                "Invalid policy field: $key"
            }
            return value.toInt()
        }
        fun boolean(key: String) {
            require(policy.opt(key) is Boolean) { "Invalid policy field: $key" }
        }
        fun time(key: String) {
            require(policy.opt(key) is String &&
                Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(policy.getString(key))) {
                "Invalid policy field: $key"
            }
        }
        fun strings(key: String) {
            val array = policy.opt(key) as? JSONArray
            require(array != null && array.length() <= 300) { "Invalid policy field: $key" }
            for (index in 0 until array.length()) {
                require(array.opt(index) is String) { "Invalid policy field: $key" }
            }
        }
        val version = integer("version", 1, Int.MAX_VALUE)
        integer("dailyLimitMinutes", 15, 1440)
        integer("locationIntervalSeconds", 60, 3600)
        integer("speedingLimitKph", 0, 250)
        for (key in arrayOf("schoolMode", "blockNewApps", "purchaseBlock",
            "uninstallProtection", "locationTracking", "schoolScheduleEnabled",
            "webFilterEnabled", "wifiConfigLocked")) boolean(key)
        for (key in arrayOf("bedtimeStart", "bedtimeEnd", "schoolScheduleStart",
            "schoolScheduleEnd")) time(key)
        for (key in arrayOf("blockedPackages", "allowedPackages", "approvedPackages",
            "schoolScheduleDays")) strings(key)
        require(policy.opt("safeDnsHost") is String) { "Invalid policy field: safeDnsHost" }
        val limits = policy.opt("appLimits") as? JSONObject
            ?: throw IllegalArgumentException("Invalid policy field: appLimits")
        for (key in limits.keys()) {
            val value = limits.opt(key)
            require(value is Number && value.toDouble().isFinite() &&
                value.toDouble() % 1.0 == 0.0 && value.toInt() in 1..1440) {
                "Invalid app limit: $key"
            }
        }
        val geofences = policy.opt("geofences") as? JSONArray
            ?: throw IllegalArgumentException("Invalid policy field: geofences")
        require(geofences.length() <= 50) { "Too many geofences" }
        for (index in 0 until geofences.length()) {
            require(geofences.opt(index) is JSONObject) { "Invalid geofence" }
        }
        return version
    }

    private fun applyPolicy(
        context: Context,
        policy: JSONObject
    ): Boolean {
        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )
        val version = validatePolicy(policy)
        if (version <= prefs.getInt("policy_version", 0)) return false

        val limit = policy.optInt(
            "dailyLimitMinutes",
            prefs.getInt("daily_limit", 180)
        ).coerceIn(15, 1440)

        val schoolMode = if (
            policy.has("schoolMode")
        ) {
            policy.optBoolean("schoolMode", false)
        } else {
            prefs.getBoolean("school_mode", false)
        }

        fun arrayString(
            key: String,
            oldKey: String
        ): String {
            val value = policy.optJSONArray(key)
            return value?.toString()
                ?: prefs.getString(oldKey, "[]")
                ?: "[]"
        }

        fun objectString(
            key: String,
            oldKey: String
        ): String {
            val value = policy.optJSONObject(key)
            return value?.toString()
                ?: prefs.getString(oldKey, "{}")
                ?: "{}"
        }

        val editor = prefs.edit()
            .putInt("daily_limit", limit)
            .putBoolean("school_mode", schoolMode)
            .putString(
                "bed_start",
                policy.optString(
                    "bedtimeStart",
                    prefs.getString("bed_start", "22:00")
                        ?: "22:00"
                )
            )
            .putString(
                "bed_end",
                policy.optString(
                    "bedtimeEnd",
                    prefs.getString("bed_end", "07:00")
                        ?: "07:00"
                )
            )
            .putString(
                "blocked_packages",
                arrayString(
                    "blockedPackages",
                    "blocked_packages"
                )
            )
            .putString(
                "allowed_packages",
                arrayString(
                    "allowedPackages",
                    "allowed_packages"
                )
            )
            .putString(
                "approved_packages",
                arrayString(
                    "approvedPackages",
                    "approved_packages"
                )
            )
            .putString(
                "app_limits",
                objectString(
                    "appLimits",
                    "app_limits"
                )
            )
            .putBoolean(
                "block_new_apps",
                policy.optBoolean(
                    "blockNewApps",
                    prefs.getBoolean(
                        "block_new_apps",
                        false
                    )
                )
            )
            .putBoolean(
                "purchase_block",
                policy.optBoolean(
                    "purchaseBlock",
                    prefs.getBoolean(
                        "purchase_block",
                        false
                    )
                )
            )
            .putBoolean(
                "uninstall_protection",
                policy.optBoolean(
                    "uninstallProtection",
                    prefs.getBoolean(
                        "uninstall_protection",
                        true
                    )
                )
            )
            .putBoolean(
                "location_tracking",
                policy.optBoolean(
                    "locationTracking",
                    prefs.getBoolean(
                        "location_tracking",
                        false
                    )
                )
            )
            .putInt(
                "location_interval_seconds",
                policy.optInt(
                    "locationIntervalSeconds",
                    prefs.getInt(
                        "location_interval_seconds",
                        120
                    )
                ).coerceIn(60, 3600)
            )
            .putInt(
                "speeding_limit_kph",
                policy.optInt(
                    "speedingLimitKph",
                    prefs.getInt(
                        "speeding_limit_kph",
                        0
                    )
                ).coerceIn(0, 250)
            )
            .putString(
                "geofences",
                arrayString(
                    "geofences",
                    "geofences"
                )
            )
            .putBoolean(
                "school_schedule_enabled",
                policy.optBoolean(
                    "schoolScheduleEnabled",
                    prefs.getBoolean(
                        "school_schedule_enabled",
                        false
                    )
                )
            )
            .putString(
                "school_schedule_start",
                policy.optString(
                    "schoolScheduleStart",
                    prefs.getString(
                        "school_schedule_start",
                        "08:00"
                    ) ?: "08:00"
                )
            )
            .putString(
                "school_schedule_end",
                policy.optString(
                    "schoolScheduleEnd",
                    prefs.getString(
                        "school_schedule_end",
                        "15:00"
                    ) ?: "15:00"
                )
            )
            .putString(
                "school_schedule_days",
                arrayString(
                    "schoolScheduleDays",
                    "school_schedule_days"
                )
            )
            .putBoolean(
                "web_filter_enabled",
                policy.optBoolean(
                    "webFilterEnabled",
                    prefs.getBoolean(
                        "web_filter_enabled",
                        false
                    )
                )
            )
            .putString(
                "safe_dns_host",
                policy.optString(
                    "safeDnsHost",
                    prefs.getString(
                        "safe_dns_host",
                        ""
                    ) ?: ""
                )
            )
            .putBoolean(
                "wifi_config_locked",
                policy.optBoolean(
                    "wifiConfigLocked",
                    prefs.getBoolean(
                        "wifi_config_locked",
                        false
                    )
                )
            )
            .putInt(
                "policy_version",
                policy.optInt(
                    "version",
                    prefs.getInt(
                        "policy_version",
                        0
                    )
                )
            )
            .putLong(
                "last_sync",
                System.currentTimeMillis()
            )

        check(editor.commit()) { "Could not persist device policy" }
        return true
    }

    private fun postUsageReport(
        context: Context,
        deviceToken: String
    ) {
        if (!hasUsageAccess(context)) {
            return
        }

        val apps = JSONArray()

        usageByPackageMinutes(context)
            .entries
            .sortedByDescending { it.value }
            .take(300)
            .forEach { (packageName, minutes) ->
                apps.put(
                    JSONObject().apply {
                        put("packageName", packageName)
                        put("minutes", minutes)
                    }
                )
            }

        ApiClient.post(
            "/api/device/usage-report",
            JSONObject().apply {
                put(
                    "day",
                    SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.US
                    ).format(Date())
                )
                put("apps", apps)
            },
            deviceToken
        )
    }

    private fun postStatus(
        context: Context,
        deviceToken: String,
        enforcement: IssueRuleEngine.EnforcementResult?
    ) {
        val prefs = context.getSharedPreferences(
            CONTROL_PREFS,
            Context.MODE_PRIVATE
        )

        val battery = batteryInfo(context)
        val usage = usageMinutes(context)

        try {
            ApiClient.post(
                "/api/device/status",
                JSONObject().apply {
                    if (usage != null) {
                        put("usageMinutes", usage)
                    }

                    if (battery.first != null) {
                        put("batteryLevel", battery.first)
                    }

                    put("charging", battery.second)
                    put(
                        "screenLocked",
                        isScreenLocked(context)
                    )
                    put(
                        "usageAccess",
                        hasUsageAccess(context)
                    )
                    put(
                        "locationPermission",
                        hasLocationPermission(context)
                    )
                    put(
                        "schoolMode",
                        prefs.getBoolean(
                            "school_mode",
                            false
                        )
                    )
                    put(
                        "policyVersion",
                        prefs.getInt(
                            "policy_version",
                            0
                        )
                    )
                    put(
                        "enforcementState",
                        enforcement?.state
                            ?: prefs.getString(
                                "enforcement_state",
                                "NORMAL"
                            )
                            ?: "NORMAL"
                    )
                    put(
                        "locationSharing",
                        prefs.getBoolean(
                            "location_tracking",
                            false
                        ) && hasLocationPermission(context)
                    )
                    put(
                        "suspendedPackages",
                        enforcement?.suspendedCount
                            ?: prefs.getInt(
                                "suspended_count",
                                0
                            )
                    )
                    foregroundPackage(context)?.let {
                        put("foregroundPackage", it)
                    }
                },
                deviceToken
            )
        } catch (_: Exception) {
        }
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun batteryInfo(
        context: Context
    ): Pair<Int?, Boolean> {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (intent == null) {
            return Pair(null, false)
        }

        val level = intent.getIntExtra(
            BatteryManager.EXTRA_LEVEL,
            -1
        )

        val scale = intent.getIntExtra(
            BatteryManager.EXTRA_SCALE,
            -1
        )

        val status = intent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            -1
        )

        val percentage = if (
            level >= 0 && scale > 0
        ) {
            ((level * 100f) / scale)
                .toInt()
                .coerceIn(0, 100)
        } else {
            null
        }

        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(percentage, charging)
    }

    private fun isScreenLocked(
        context: Context
    ): Boolean {
        val manager = context.getSystemService(
            Context.KEYGUARD_SERVICE
        ) as KeyguardManager

        return manager.isKeyguardLocked
    }

    private fun apiMessage(
        body: String,
        fallback: String
    ): String {
        return try {
            val json = JSONObject(body)
            json.optString(
                "message",
                json.optString("error", fallback)
            ).ifBlank { fallback }
        } catch (_: Exception) {
            fallback
        }
    }
}
