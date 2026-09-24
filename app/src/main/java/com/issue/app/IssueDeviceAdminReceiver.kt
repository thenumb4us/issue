package com.issue.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class IssueDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(
        context: Context,
        intent: Intent
    ) {
        Toast.makeText(
            context,
            "ISSUE supervision enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(
        context: Context,
        intent: Intent
    ) {
        Toast.makeText(
            context,
            "ISSUE supervision disabled",
            Toast.LENGTH_SHORT
        ).show()
    }
}
