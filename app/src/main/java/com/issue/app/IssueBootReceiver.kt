package com.issue.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class IssueBootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(
                "issue_prefs",
                Context.MODE_PRIVATE
            )
            prefs.edit()
                .putLong(
                    "last_boot_seen",
                    System.currentTimeMillis()
                )
                .apply()

            val isApprovedChild =
                prefs.getString("role", "") == "child" &&
                    prefs.getBoolean("child_approved", false) &&
                    !prefs.getString("device_token", "").isNullOrBlank()

            // Resume the visible supervisor only for a paired, approved child device.
            if (isApprovedChild) {
                IssueSupervisionService.start(context)
            }
        }
    }
}
