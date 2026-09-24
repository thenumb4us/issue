package com.issue.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class ControlCenterActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("issue_controls", MODE_PRIVATE)
    }

    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE)
            as DevicePolicyManager
    }

    private val admin by lazy {
        ComponentName(
            this,
            IssueDeviceAdminReceiver::class.java
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home()
    }

    private fun home() {
        val root = base()

        root.addView(title("ISSUE Controls"))

        root.addView(button("Screen Time") {
            screenTime()
        })

        root.addView(button("App Controls") {
            appControls()
        })

        root.addView(button("Bedtime / School Mode") {
            schedules()
        })

        root.addView(button("Location / Safe Zone") {
            location()
        })

        root.addView(button("SOS / Alerts") {
            sos()
        })

        root.addView(button("Back") {
            finish()
        })

        setContentView(scroll(root))
    }

    private fun screenTime() {
        val root = base()
        root.addView(title("Screen Time"))

        val limit = EditText(this).apply {
            hint = "Daily limit minutes"
            setText(
                prefs.getInt(
                    "daily_limit",
                    180
                ).toString()
            )
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        root.addView(limit)

        root.addView(button("Save Limit") {
            val value =
                limit.text.toString()
                    .toIntOrNull()
                    ?.coerceIn(15, 1440)
                    ?: 180

            prefs.edit()
                .putInt("daily_limit", value)
                .apply()

            toast("Daily limit saved")
        })

        root.addView(
            info(
                "Usage today",
                "${usageMinutes()} minutes"
            )
        )

        if (!hasUsageAccess()) {
            root.addView(button("Grant Usage Access") {
                startActivity(
                    Intent(
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    )
                )
            })
        }

        root.addView(backButton())
        setContentView(scroll(root))
    }

    private fun appControls() {
        val root = base()
        root.addView(title("App Controls"))

        root.addView(
            info(
                "Device Owner",
                if (
                    dpm.isDeviceOwnerApp(packageName)
                ) "Active" else "Required"
            )
        )

        val pkg = EditText(this).apply {
            hint = "App package name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        root.addView(pkg)

        root.addView(button("Block App") {
            toggleApp(
                pkg.text.toString().trim(),
                true
            )
        })

        root.addView(button("Unblock App") {
            toggleApp(
                pkg.text.toString().trim(),
                false
            )
        })

        root.addView(backButton())
        setContentView(scroll(root))
    }

    private fun schedules() {
        val root = base()
        root.addView(title("Schedules"))

        val start = EditText(this).apply {
            hint = "Bedtime start"
            setText(
                prefs.getString(
                    "bed_start",
                    "22:00"
                )
            )
            setTextColor(Color.WHITE)
        }

        val end = EditText(this).apply {
            hint = "Bedtime end"
            setText(
                prefs.getString(
                    "bed_end",
                    "07:00"
                )
            )
            setTextColor(Color.WHITE)
        }

        root.addView(start)
        root.addView(end)

        root.addView(button("Save Bedtime") {
            prefs.edit()
                .putString(
                    "bed_start",
                    start.text.toString()
                )
                .putString(
                    "bed_end",
                    end.text.toString()
                )
                .apply()

            toast("Bedtime saved")
        })

        val school =
            prefs.getBoolean(
                "school_mode",
                false
            )

        root.addView(
            info(
                "School Mode",
                if (school) "ON" else "OFF"
            )
        )

        root.addView(button(
            if (school) {
                "Turn School Mode OFF"
            } else {
                "Turn School Mode ON"
            }
        ) {
            prefs.edit()
                .putBoolean(
                    "school_mode",
                    !school
                )
                .apply()

            schedules()
        })

        root.addView(backButton())
        setContentView(scroll(root))
    }

    private fun location() {
        val root = base()
        root.addView(title("Location / Safe Zone"))

        if (!hasLocation()) {
            root.addView(
                info(
                    "Location permission",
                    "Required"
                )
            )

            root.addView(button("Allow Location") {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    1201
                )
            })
        } else {
            val manager =
                getSystemService(
                    Context.LOCATION_SERVICE
                ) as LocationManager

            var lat: Double? = null
            var lon: Double? = null

            for (provider in manager.getProviders(true)) {
                try {
                    val item =
                        manager.getLastKnownLocation(provider)

                    if (item != null) {
                        lat = item.latitude
                        lon = item.longitude
                        break
                    }
                } catch (_: Exception) {
                }
            }

            if (lat != null && lon != null) {
                root.addView(
                    info(
                        "Recent Location",
                        "$lat, $lon"
                    )
                )

                root.addView(button("Save Safe Zone") {
                    prefs.edit()
                        .putString(
                            "safe_lat",
                            lat.toString()
                        )
                        .putString(
                            "safe_lon",
                            lon.toString()
                        )
                        .putInt(
                            "safe_radius",
                            200
                        )
                        .apply()

                    toast("Safe Zone saved")
                })
            } else {
                root.addView(
                    info(
                        "Location",
                        "No recent location"
                    )
                )
            }
        }

        root.addView(backButton())
        setContentView(scroll(root))
    }

    private fun sos() {
        val root = base()
        root.addView(title("SOS / Alerts"))

        root.addView(
            info(
                "SOS",
                "Visible emergency action"
            )
        )

        root.addView(button("SEND SOS") {
            prefs.edit()
                .putLong(
                    "last_sos",
                    System.currentTimeMillis()
                )
                .apply()

            toast("SOS recorded")
        })

        root.addView(
            info(
                "School Mode",
                if (
                    prefs.getBoolean(
                        "school_mode",
                        false
                    )
                ) "ON" else "OFF"
            )
        )

        root.addView(
            info(
                "Location Permission",
                if (hasLocation()) {
                    "Granted"
                } else {
                    "Missing"
                }
            )
        )

        root.addView(backButton())
        setContentView(scroll(root))
    }

    private fun toggleApp(
        pkg: String,
        blocked: Boolean
    ) {
        if (pkg.isBlank()) {
            toast("Enter package name")
            return
        }

        if (!dpm.isDeviceOwnerApp(packageName)) {
            toast("Device Owner required")
            return
        }

        if (pkg == packageName) {
            toast("ISSUE cannot block itself")
            return
        }

        try {
            dpm.setPackagesSuspended(
                admin,
                arrayOf(pkg),
                blocked
            )

            toast(
                if (blocked) {
                    "App blocked"
                } else {
                    "App unblocked"
                }
            )
        } catch (_: Exception) {
            toast("Unable to change app state")
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops =
            getSystemService(
                Context.APP_OPS_SERVICE
            ) as AppOpsManager

        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun usageMinutes(): Long {
        if (!hasUsageAccess()) return 0

        val calendar = Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )
        calendar.set(
            Calendar.MINUTE,
            0
        )
        calendar.set(
            Calendar.SECOND,
            0
        )

        val manager =
            getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        return manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            calendar.timeInMillis,
            System.currentTimeMillis()
        ).sumOf {
            it.totalTimeInForeground
        } / 60000L
    }

    private fun hasLocation(): Boolean {
        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun base() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 60)
            setBackgroundColor(
                Color.rgb(11, 11, 16)
            )
        }

    private fun scroll(
        root: LinearLayout
    ) =
        ScrollView(this).apply {
            addView(root)
        }

    private fun title(
        value: String
    ) =
        TextView(this).apply {
            text = value
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 25)
        }

    private fun info(
        heading: String,
        value: String
    ) =
        TextView(this).apply {
            text = "$heading\n$value"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 18, 20, 18)
            setBackgroundColor(
                Color.rgb(24, 24, 34)
            )
        }

    private fun button(
        label: String,
        action: () -> Unit
    ) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                action()
            }
        }

    private fun backButton() =
        button("Back") {
            home()
        }

    private fun toast(
        value: String
    ) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }
}
