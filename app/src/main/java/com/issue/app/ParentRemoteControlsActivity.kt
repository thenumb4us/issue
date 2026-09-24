package com.issue.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parent controls are scoped to a linked child and sent to the authenticated
 * backend. The child device applies them on its next visible policy sync.
 */
class ParentRemoteControlsActivity : Activity() {
    companion object {
        const val EXTRA_CHILD_ID = "issue_child_id"
        const val EXTRA_CHILD_NAME = "issue_child_name"
    }

    private lateinit var childId: String
    private lateinit var parentToken: String
    private var currentPolicy = JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = getSharedPreferences("issue_prefs", MODE_PRIVATE)
        childId = intent.getStringExtra(EXTRA_CHILD_ID).orEmpty()
        parentToken = session.getString("parent_token", "").orEmpty()
        if (session.getString("role", "") != "parent" ||
            parentToken.isBlank() || childId.isBlank()
        ) {
            finish()
            return
        }
        loadStatus()
    }

    private fun loadStatus() {
        showMessage("Loading child controls...")
        request("/api/parent/children/$childId/sync-status") { data ->
            currentPolicy = data.optJSONObject("policy") ?: JSONObject()
            showControls(data)
        }
    }

    private fun showControls(data: JSONObject) {
        val root = base()
        val name = intent.getStringExtra(EXTRA_CHILD_NAME)
            ?.takeIf { it.isNotBlank() } ?: "Child"
        root.addView(title("ISSUE"))
        root.addView(subtitle("${name}'s dashboard"))
        root.addView(info("Manage healthy screen time, approved apps, schedules, location sharing and safety alerts. Every change is delivered only to this linked child device."))

        val devices = data.optJSONArray("devices") ?: JSONArray()
        val device = devices.optJSONObject(0)
        if (device != null) {
            root.addView(info("Device: ${device.optString("name", "Android")}" +
                "\nOnline: ${if (device.optBoolean("online")) "Yes" else "No"}" +
                "\nBattery: ${device.optInt("batteryLevel", -1).let { if (it < 0) "Unknown" else "$it%" }}" +
                "\nUsage today: ${device.optInt("usageMinutes", 0)} min" +
                "\nPolicy version: ${device.optInt("policyVersion", 0)}" +
                "\nPending commands: ${device.optInt("pendingCommands", 0)}"))
        } else {
            root.addView(info("No active child device linked."))
        }

        root.addView(section("Screen time & routines"))
        val limit = input("Daily screen time (15–1440 minutes)",
            currentPolicy.optInt("dailyLimitMinutes", 180).toString())
        root.addView(limit)
        root.addView(button("Save Screen Time") {
            val minutes = limit.text.toString().toIntOrNull()
            if (minutes == null || minutes !in 15..1440) {
                toast("Enter a limit from 15 to 1440 minutes")
            } else {
                updatePolicy(JSONObject().put("dailyLimitMinutes", minutes))
            }
        })

        val start = input("Bedtime start HH:MM",
            currentPolicy.optString("bedtimeStart", "22:00"))
        val end = input("Bedtime end HH:MM",
            currentPolicy.optString("bedtimeEnd", "07:00"))
        root.addView(start)
        root.addView(end)
        root.addView(button("Save Bedtime") {
            val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            val from = start.text.toString()
            val to = end.text.toString()
            if (!timePattern.matches(from) || !timePattern.matches(to)) {
                toast("Use 24-hour HH:MM, for example 22:00")
            } else {
                updatePolicy(JSONObject()
                    .put("bedtimeStart", from)
                    .put("bedtimeEnd", to))
            }
        })

        root.addView(section("Apps & device safety"))
        val schoolMode = currentPolicy.optBoolean("schoolMode", false)
        root.addView(button("School Mode: ${if (schoolMode) "ON" else "OFF"}") {
            updatePolicy(JSONObject().put("schoolMode", !schoolMode))
        })

        root.addView(button("School Schedule") { showSchoolSchedule() })
        root.addView(button("App Time Limits") { showAppLimits() })
        root.addView(button("App Approvals") { showAppApprovals() })
        root.addView(button("Device Safety") { showDeviceSafety() })

        root.addView(section("Location & alerts"))
        val locationEnabled = currentPolicy.optBoolean("locationTracking", false)
        root.addView(button("Location Sharing: ${if (locationEnabled) "ON" else "OFF"}") {
            updatePolicy(JSONObject().put("locationTracking", !locationEnabled))
        })

        val latestLocation = data.optJSONObject("latestLocation")
        if (locationEnabled && latestLocation != null) {
            root.addView(info("Last location: ${latestLocation.optDouble("latitude")}, " +
                "${latestLocation.optDouble("longitude")}" +
                "\nUpdated: ${latestLocation.optString("recordedAt", "Unknown")}"))
        }

        root.addView(button("Location History") { showLocationHistory() })
        root.addView(button("Safe Zones") { showSafeZones() })

        val events = data.optJSONArray("recentEvents") ?: JSONArray()
        for (index in 0 until minOf(events.length(), 10)) {
            val event = events.optJSONObject(index) ?: continue
            val type = event.optString("type")
            val detail = event.optJSONObject("payload") ?: JSONObject()
            val label = when (type) {
                "SOS" -> "SOS / Help requested"
                "MORE_TIME_REQUEST" -> "Child requested ${detail.optInt("minutes", 30)} more minutes. To grant, update the daily limit above."
                "GEOFENCE_ENTER" -> "Entered ${detail.optString("name", "safe zone")}" 
                "GEOFENCE_EXIT" -> "Left ${detail.optString("name", "safe zone")}" 
                "SPEEDING_ALERT" -> "Speeding alert: ${detail.optInt("speedKph")} km/h"
                else -> null
            }
            if (label != null) {
                root.addView(info("$label\n" + event.optString("createdAt", "Time unknown")))
            }
        }

        root.addView(section("Quick actions"))
        val packageField = input("App package, e.g. com.example.app", "")
        root.addView(packageField)
        root.addView(button("Block App") {
            setBlockedPackage(packageField.text.toString().trim(), true)
        })
        root.addView(button("Unblock App") {
            setBlockedPackage(packageField.text.toString().trim(), false)
        })
        root.addView(button("App Usage Report") { showUsageReport() })
        root.addView(button("Lock Child Device") {
            AlertDialog.Builder(this)
                .setTitle("Lock child device?")
                .setMessage("The command is delivered on the next device sync.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Lock") { _, _ ->
                    request(
                        "/api/parent/children/$childId/lock",
                        JSONObject().put("reason", "Parent requested lock")
                    ) { response ->
                        toast("Lock queued for ${response.optInt("queuedDevices", 0)} device(s)")
                        loadStatus()
                    }
                }
                .show()
        })
        root.addView(button("Refresh") { loadStatus() })
        root.addView(button("Back") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setBlockedPackage(packageName: String, block: Boolean) {
        if (!Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$").matches(packageName)) {
            toast("Enter a valid app package name")
            return
        }
        val blocked = currentPolicy.optJSONArray("blockedPackages") ?: JSONArray()
        val updated = linkedSetOf<String>()
        for (index in 0 until blocked.length()) {
            blocked.optString(index).takeIf { it.isNotBlank() }?.let(updated::add)
        }
        if (block) updated.add(packageName) else updated.remove(packageName)
        updatePolicy(JSONObject().put("blockedPackages", JSONArray(updated.toList())))
    }

    private fun updatePolicy(body: JSONObject) {
        request("/api/parent/children/$childId/policy", body) { response ->
            val queued = response.optInt("queuedDevices", 0)
            toast("Saved. Policy queued for $queued device(s)")
            loadStatus()
        }
    }

    private fun showAppApprovals() {
        val root = base()
        root.addView(title("App Approvals"))
        root.addView(info("Approve packages before enabling the restriction. Existing apps are not approved automatically. Device Owner mode is required on the child device."))
        val approved = currentPolicy.optJSONArray("approvedPackages") ?: JSONArray()
        val names = (0 until approved.length())
            .map { approved.optString(it) }.filter { it.isNotBlank() }.distinct()
        val restrictionOn = currentPolicy.optBoolean("blockNewApps", false)
        root.addView(info("Approved packages: ${names.size}"))
        root.addView(button("Unapproved apps blocked: ${if (restrictionOn) "ON" else "OFF"}") {
            if (!restrictionOn && names.isEmpty()) {
                toast("Approve at least one app before turning this on")
            } else {
                AlertDialog.Builder(this)
                    .setTitle(if (restrictionOn) "Allow unapproved apps?" else "Restrict unapproved apps?")
                    .setMessage("This applies on the child's next sync when Device Owner mode is active.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue") { _, _ ->
                        updatePolicy(JSONObject().put("blockNewApps", !restrictionOn))
                    }.show()
            }
        })
        for (pkg in names.sorted()) {
            root.addView(info(pkg))
            root.addView(button("Revoke approval: $pkg") {
                AlertDialog.Builder(this)
                    .setTitle("Revoke approval?")
                    .setMessage("$pkg may be restricted after the next child sync.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Revoke") { _, _ -> setAppApproved(pkg, false) }
                    .show()
            })
        }
        val packageField = input("App package, e.g. com.example.app", "")
        root.addView(packageField)
        root.addView(button("Approve App") {
            setAppApproved(packageField.text.toString().trim(), true)
        })
        root.addView(button("Back to Controls") { loadStatus() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setAppApproved(packageName: String, approve: Boolean) {
        if (!Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$").matches(packageName)) {
            toast("Enter a valid app package name")
            return
        }
        val old = currentPolicy.optJSONArray("approvedPackages") ?: JSONArray()
        val names = linkedSetOf<String>()
        for (index in 0 until old.length()) {
            old.optString(index).takeIf { it.isNotBlank() }?.let(names::add)
        }
        if (approve && names.size >= 300 && packageName !in names) {
            toast("Approved app list is full")
            return
        }
        if (approve) names.add(packageName) else names.remove(packageName)
        val change = JSONObject().put("approvedPackages", JSONArray(names.toList()))
        if (names.isEmpty() && currentPolicy.optBoolean("blockNewApps", false)) {
            change.put("blockNewApps", false)
        }
        updatePolicy(change)
    }

    private fun showDeviceSafety() {
        val root = base()
        root.addView(title("Device Safety"))
        root.addView(info("These controls need supervised Device Owner mode on the child device."))
        val storeBlocked = currentPolicy.optBoolean("purchaseBlock", false)
        root.addView(button("Play Store blocked: ${if (storeBlocked) "ON" else "OFF"}") {
            updatePolicy(JSONObject().put("purchaseBlock", !storeBlocked))
        })
        val wifiLocked = currentPolicy.optBoolean("wifiConfigLocked", false)
        root.addView(button("Wi-Fi settings locked: ${if (wifiLocked) "ON" else "OFF"}") {
            updatePolicy(JSONObject().put("wifiConfigLocked", !wifiLocked))
        })
        val dnsEnabled = currentPolicy.optBoolean("webFilterEnabled", false)
        root.addView(info("Private DNS uses a hostname you configure. A filter provider must support Android Private DNS."))
        val host = input("Private DNS hostname",
            currentPolicy.optString("safeDnsHost", ""))
        root.addView(host)
        root.addView(button("Save and Enable Private DNS") {
            val value = host.text.toString().trim()
            if (value.length !in 3..253 ||
                !Regex("^[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)+$").matches(value)) {
                toast("Enter a valid Private DNS hostname")
                return@button
            }
            updatePolicy(JSONObject().put("safeDnsHost", value).put("webFilterEnabled", true))
        })
        if (dnsEnabled) {
            root.addView(button("Disable Private DNS filter") {
                updatePolicy(JSONObject().put("webFilterEnabled", false))
            })
        }
        val speed = input("Driving speed alert km/h (0 disables)",
            currentPolicy.optInt("speedingLimitKph", 0).toString())
        root.addView(speed)
        root.addView(button("Save Speed Alert") {
            val value = speed.text.toString().toIntOrNull()
            if (value == null || value !in 0..250) {
                toast("Enter speed from 0 to 250 km/h")
                return@button
            }
            updatePolicy(JSONObject().put("speedingLimitKph", value))
        })
        root.addView(button("Back to Controls") { loadStatus() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showSchoolSchedule() {
        val root = base()
        root.addView(title("School Schedule"))
        val enabled = currentPolicy.optBoolean("schoolScheduleEnabled", false)
        root.addView(button("Schedule: ${if (enabled) "ON" else "OFF"}") {
            updatePolicy(JSONObject().put("schoolScheduleEnabled", !enabled))
        })
        val from = input("School start HH:MM",
            currentPolicy.optString("schoolScheduleStart", "08:00"))
        val to = input("School end HH:MM",
            currentPolicy.optString("schoolScheduleEnd", "15:00"))
        val days = input("Days: MON,TUE,WED,THU,FRI",
            (currentPolicy.optJSONArray("schoolScheduleDays") ?: JSONArray())
                .let { arr -> (0 until arr.length()).joinToString(",") { arr.optString(it) } })
        root.addView(from)
        root.addView(to)
        root.addView(days)
        root.addView(button("Save School Schedule") {
            val pattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            val selected = days.text.toString().split(",")
                .map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            val validDays = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
            if (!pattern.matches(from.text.toString()) ||
                !pattern.matches(to.text.toString()) ||
                selected.isEmpty() || selected.any { it !in validDays }) {
                toast("Enter valid HH:MM times and days like MON,TUE,WED")
                return@button
            }
            updatePolicy(JSONObject()
                .put("schoolScheduleStart", from.text.toString())
                .put("schoolScheduleEnd", to.text.toString())
                .put("schoolScheduleDays", JSONArray(selected)))
        })
        root.addView(button("Back to Controls") { loadStatus() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showAppLimits() {
        val root = base()
        root.addView(title("App Time Limits"))
        val limits = currentPolicy.optJSONObject("appLimits") ?: JSONObject()
        val packages = limits.keys().asSequence().toList().sorted()
        if (packages.isEmpty()) root.addView(info("No app time limits set."))
        for (pkg in packages) {
            root.addView(info("$pkg — ${limits.optInt(pkg)} min/day"))
            root.addView(button("Remove limit: $pkg") { saveAppLimit(pkg, null) })
        }
        val packageField = input("App package, e.g. com.example.app", "")
        val minutesField = input("Minutes per day (1–1440)", "60")
        root.addView(packageField)
        root.addView(minutesField)
        root.addView(button("Save App Limit") {
            val pkg = packageField.text.toString().trim()
            val minutes = minutesField.text.toString().toIntOrNull()
            if (!Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$").matches(pkg) ||
                minutes == null || minutes !in 1..1440 ||
                (limits.length() >= 200 && !limits.has(pkg))) {
                toast("Enter a valid package and 1 to 1440 minutes")
                return@button
            }
            saveAppLimit(pkg, minutes)
        })
        root.addView(button("Back to Controls") { loadStatus() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveAppLimit(packageName: String, minutes: Int?) {
        val existing = currentPolicy.optJSONObject("appLimits") ?: JSONObject()
        val updated = JSONObject()
        for (key in existing.keys()) {
            if (key != packageName) updated.put(key, existing.optInt(key))
        }
        if (minutes != null) updated.put(packageName, minutes)
        updatePolicy(JSONObject().put("appLimits", updated))
    }

    private fun showLocationHistory() {
        showMessage("Loading location history...")
        request("/api/parent/children/$childId/location-history?limit=50") { response ->
            val root = base()
            root.addView(title("Location History"))
            val locations = response.optJSONArray("locations") ?: JSONArray()
            if (locations.length() == 0) {
                root.addView(info("No locations shared yet."))
            }
            for (index in 0 until locations.length()) {
                val location = locations.optJSONObject(index) ?: continue
                root.addView(info(
                    "${location.optString("recordedAt", "Unknown time")}\n" +
                    "${location.optDouble("latitude")}, ${location.optDouble("longitude")}" +
                    " • Accuracy: ${location.optInt("accuracyMeters", 0)} m"
                ))
            }
            root.addView(button("Back to Controls") { loadStatus() })
            setContentView(ScrollView(this).apply { addView(root) })
        }
    }

    private fun showSafeZones() {
        val root = base()
        root.addView(title("Safe Zones"))
        root.addView(info("Safe zones use visible location sharing on the child device."))
        val zones = currentPolicy.optJSONArray("geofences") ?: JSONArray()
        for (index in 0 until zones.length()) {
            val zone = zones.optJSONObject(index) ?: continue
            root.addView(info("${zone.optString("name", "Safe zone")} — " +
                "${zone.optInt("radiusMeters", 200)} m radius"))
            root.addView(button("Remove this zone") {
                val remaining = JSONArray()
                for (other in 0 until zones.length()) {
                    if (other != index) remaining.put(zones.optJSONObject(other))
                }
                updatePolicy(JSONObject().put("geofences", remaining))
            })
        }
        val nameField = input("Zone name, e.g. Home", "")
        val latitudeField = input("Latitude (-90 to 90)", "")
        val longitudeField = input("Longitude (-180 to 180)", "")
        val radiusField = input("Radius in metres (50–50000)", "200")
        root.addView(nameField)
        root.addView(latitudeField)
        root.addView(longitudeField)
        root.addView(radiusField)
        root.addView(button("Add Safe Zone") {
            val name = nameField.text.toString().trim()
            val latitude = latitudeField.text.toString().toDoubleOrNull()
            val longitude = longitudeField.text.toString().toDoubleOrNull()
            val radius = radiusField.text.toString().toIntOrNull()
            if (name.isBlank() || name.length > 80 ||
                latitude == null || !latitude.isFinite() || latitude !in -90.0..90.0 ||
                longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0 ||
                radius == null || radius !in 50..50000 || zones.length() >= 50
            ) {
                toast("Enter a name, valid coordinates and a radius from 50 to 50000 m")
                return@button
            }
            val updated = JSONArray()
            for (index in 0 until zones.length()) updated.put(zones.optJSONObject(index))
            updated.put(JSONObject()
                .put("name", name)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("radiusMeters", radius))
            updatePolicy(JSONObject().put("geofences", updated))
        })
        root.addView(button("Back to Controls") { loadStatus() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showUsageReport() {
        showMessage("Loading usage report...")
        request("/api/parent/children/$childId/usage-report") { response ->
            val root = base()
            root.addView(title("App Usage"))
            root.addView(info("Date: ${response.optString("day", "Today")}"))
            val apps = response.optJSONArray("apps") ?: JSONArray()
            if (apps.length() == 0) root.addView(info("No usage reported yet."))
            for (index in 0 until minOf(apps.length(), 50)) {
                val app = apps.optJSONObject(index) ?: continue
                val pkg = app.optString("packageName")
                root.addView(info("$pkg — ${app.optInt("minutes")} min"))
                if (pkg.isNotBlank()) {
                    root.addView(button("Approve: $pkg") { setAppApproved(pkg, true) })
                }
            }
            root.addView(button("Back to Controls") { loadStatus() })
            setContentView(ScrollView(this).apply { addView(root) })
        }
    }

    private fun request(
        path: String,
        body: JSONObject? = null,
        onSuccess: (JSONObject) -> Unit
    ) {
        Thread {
            try {
                val result = if (body == null) {
                    ApiClient.get(path, parentToken)
                } else {
                    ApiClient.post(path, body, parentToken)
                }
                val payload = runCatching { JSONObject(result.body) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (result.statusCode == 401) {
                        toast("Parent session expired. Log in again.")
                        finish()
                    } else if (result.statusCode !in 200..299) {
                        toast(payload?.optString("message")
                            ?.takeIf { it.isNotBlank() } ?: "Request failed")
                        if (body == null) showRetry()
                    } else if (payload == null) {
                        toast("Unexpected server response")
                        if (body == null) showRetry()
                    } else {
                        onSuccess(payload)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast("Connection error: ${error.message ?: "Unknown"}")
                    if (body == null) showRetry()
                }
            }
        }.start()
    }

    private fun showRetry() {
        val root = base()
        root.addView(title("ISSUE Controls"))
        root.addView(info("Could not load the child device."))
        root.addView(button("Retry") { loadStatus() })
        root.addView(button("Back") { finish() })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showMessage(message: String) {
        val root = base()
        root.addView(title("ISSUE Controls"))
        root.addView(info(message))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun base() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(36, 54, 36, 48)
        setBackgroundColor(Color.rgb(246, 249, 255))
    }

    private fun shape(color: Int, radius: Float = 28f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun subtitle(label: String) = TextView(this).apply {
        text = label
        textSize = 21f
        setTextColor(Color.rgb(37, 99, 235))
        setPadding(0, 0, 0, 18)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.rgb(25, 35, 58))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(4, 26, 4, 10)
    }

    private fun title(label: String) = TextView(this).apply {
        text = label
        textSize = 29f
        setTextColor(Color.rgb(25, 35, 58))
        setPadding(0, 0, 0, 6)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun info(label: String) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(24, 22, 24, 22)
        background = shape(Color.WHITE)
        elevation = 3f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 }
    }

    private fun input(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(Color.rgb(25, 35, 58))
        setHintTextColor(Color.rgb(100, 116, 139))
        setSingleLine(true)
        setPadding(22, 16, 22, 16)
        background = shape(Color.WHITE, 22f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10 }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        background = shape(Color.rgb(37, 99, 235), 22f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10 }
        setOnClickListener { action() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
