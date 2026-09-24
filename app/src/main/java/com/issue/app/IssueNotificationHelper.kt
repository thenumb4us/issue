package com.issue.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object IssueNotificationHelper {

    const val CHANNEL_SUPERVISION = "issue_supervision"
    const val CHANNEL_ALERTS = "issue_alerts"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUPERVISION,
                "ISSUE supervision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible supervision, rule enforcement and sync status"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "ISSUE alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Screen-time, bedtime, safety and policy alerts"
            }
        )
    }

    fun supervisionNotification(
        context: Context,
        text: String
    ): Notification {
        ensureChannels(context)

        val openIntent = Intent(
            context,
            MainActivity::class.java
        )

        val pending = PendingIntent.getActivity(
            context,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            Notification.Builder(
                context,
                CHANNEL_SUPERVISION
            )
        } else {
            Notification.Builder(context)
        }

        return builder
            .setContentTitle("ISSUE supervision active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    fun alert(
        context: Context,
        id: Int,
        title: String,
        text: String
    ) {
        ensureChannels(context)

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val openIntent = Intent(
            context,
            MainActivity::class.java
        )

        val pending = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            Notification.Builder(
                context,
                CHANNEL_ALERTS
            )
        } else {
            Notification.Builder(context)
        }

        manager.notify(
            id,
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
        )
    }
}
