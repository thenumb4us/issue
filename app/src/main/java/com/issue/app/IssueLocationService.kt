package com.issue.app

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder

class IssueLocationService : Service(), LocationListener {

    companion object {
        private const val NOTIFICATION_ID = 1102

        fun syncWithPolicy(context: Context) {
            val controls = context.getSharedPreferences(
                "issue_controls",
                Context.MODE_PRIVATE
            )

            val enabled = controls.getBoolean(
                "location_tracking",
                false
            )

            val permission =
                context.checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

            if (enabled && permission) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(
                        context,
                        IssueLocationService::class.java
                    )
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(
                        context,
                        IssueLocationService::class.java
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()

        IssueNotificationHelper.ensureChannels(this)

        startForeground(
            NOTIFICATION_ID,
            IssueNotificationHelper.supervisionNotification(
                this,
                "Location sharing is active and visible."
            )
        )

        locationManager = getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager

        beginUpdates()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        beginUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onLocationChanged(location: Location) {
        val token = getSharedPreferences(
            "issue_prefs",
            MODE_PRIVATE
        ).getString(
            "device_token",
            ""
        ).orEmpty()

        if (token.isBlank()) {
            stopSelf()
            return
        }

        Thread {
            try {
                DeviceSyncManager.postLocation(
                    this,
                    token,
                    location
                )
            } catch (_: Exception) {
            }
        }.start()
    }

    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(
        provider: String?,
        status: Int,
        extras: Bundle?
    ) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    private fun beginUpdates() {
        if (
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val controls = getSharedPreferences(
            "issue_controls",
            MODE_PRIVATE
        )

        if (!controls.getBoolean("location_tracking", false)) {
            stopSelf()
            return
        }

        val intervalMs = controls.getInt(
            "location_interval_seconds",
            120
        ).coerceIn(60, 3600) * 1000L

        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }

        var registered = false

        try {
            if (
                locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                )
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    20f,
                    this
                )
                registered = true
            }
        } catch (_: Exception) {
        }

        try {
            if (
                locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs,
                    50f,
                    this
                )
                registered = true
            }
        } catch (_: Exception) {
        }

        if (!registered) {
            IssueNotificationHelper.alert(
                this,
                3110,
                "Location unavailable",
                "Turn on device location to share it with the parent."
            )
        }
    }
}
