package com.issue.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class IssueSupervisionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1101
        private const val TICK_MS = 60_000L
        private const val SYNC_MS = 5 * 60_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(
                        context,
                        IssueSupervisionService::class.java
                    )
                )
            } catch (_: Exception) {
                try {
                    context.startService(
                        Intent(
                            context,
                            IssueSupervisionService::class.java
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(
                        context,
                        IssueSupervisionService::class.java
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var lastSyncAt = 0L

    private val runner = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        IssueNotificationHelper.ensureChannels(this)

        startForeground(
            NOTIFICATION_ID,
            IssueNotificationHelper.supervisionNotification(
                this,
                "Rules are active and visible on this child device."
            )
        )

        handler.post(runner)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun tick() {
        if (!busy.compareAndSet(false, true)) {
            return
        }

        Thread {
            try {
                val session = getSharedPreferences(
                    "issue_prefs",
                    MODE_PRIVATE
                )

                if (
                    session.getString("role", null) != "child"
                ) {
                    stopSelf()
                    return@Thread
                }

                val token = session.getString(
                    "device_token",
                    ""
                ).orEmpty()

                if (token.isBlank()) {
                    stopSelf()
                    return@Thread
                }

                val result = IssueRuleEngine
                    .evaluateAndApply(this)

                IssueLocationService
                    .syncWithPolicy(this)

                val now = System.currentTimeMillis()

                if (
                    now - lastSyncAt >= SYNC_MS
                ) {
                    try {
                        DeviceSyncManager.sync(
                            this,
                            token
                        )
                        lastSyncAt = now
                    } catch (_: Exception) {
                        // Offline rules remain active from the last saved policy.
                    }
                }

                runOnMain {
                    val text = when (result.state) {
                        "SCREEN_TIME_LOCK" ->
                            "Screen-time limit reached • ${result.suspendedCount} app(s) restricted"
                        "BEDTIME_LOCK" ->
                            "Bedtime rules active • ${result.suspendedCount} app(s) restricted"
                        "SCHOOL_MODE" ->
                            "School Mode active • ${result.suspendedCount} app(s) restricted"
                        else ->
                            "Supervision active • background sync enabled"
                    }

                    val manager = getSystemService(
                        NOTIFICATION_SERVICE
                    ) as android.app.NotificationManager

                    manager.notify(
                        NOTIFICATION_ID,
                        IssueNotificationHelper
                            .supervisionNotification(
                                this,
                                text
                            )
                    )
                }
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun runOnMain(block: () -> Unit) {
        handler.post { block() }
    }
}
