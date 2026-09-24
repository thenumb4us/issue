package com.issue.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager

class DeviceOwnerManager(
    private val context: Context
) {

    private val dpm =
        context.getSystemService(
            Context.DEVICE_POLICY_SERVICE
        ) as DevicePolicyManager

    private val admin =
        ComponentName(
            context,
            IssueDeviceAdminReceiver::class.java
        )

    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(
            context.packageName
        )
    }

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(admin)
    }

    fun supervisionStatus(): String {
        return when {
            isDeviceOwner() -> "Device Owner Active"
            isAdminActive() -> "Device Admin Active"
            else -> "Not Supervised"
        }
    }

    fun requestDeviceAdmin(
        activity: Activity
    ) {
        val intent =
            Intent(
                DevicePolicyManager
                    .ACTION_ADD_DEVICE_ADMIN
            ).apply {
                putExtra(
                    DevicePolicyManager
                        .EXTRA_DEVICE_ADMIN,
                    admin
                )

                putExtra(
                    DevicePolicyManager
                        .EXTRA_ADD_EXPLANATION,
                    "ISSUE uses supervised device controls for parental management."
                )
            }

        activity.startActivity(intent)
    }

    fun lockDevice(): Boolean {
        return try {
            if (
                isDeviceOwner() ||
                isAdminActive()
            ) {
                dpm.lockNow()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setIssueUninstallBlocked(
        blocked: Boolean
    ): Boolean {
        if (!isDeviceOwner()) {
            return false
        }

        return try {
            dpm.setUninstallBlocked(
                admin,
                context.packageName,
                blocked
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isIssueUninstallBlocked(): Boolean {
        if (!isDeviceOwner()) {
            return false
        }

        return try {
            dpm.isUninstallBlocked(
                admin,
                context.packageName
            )
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Suspends only packages managed by ISSUE. This requires Device Owner.
     * Returns package names Android refused to change.
     */
    fun setPackagesSuspended(
        packages: Collection<String>,
        suspended: Boolean
    ): Set<String> {
        if (!isDeviceOwner()) {
            return packages.toSet()
        }

        val clean = packages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it != context.packageName }
            .distinct()

        if (clean.isEmpty()) {
            return emptySet()
        }

        return try {
            dpm.setPackagesSuspended(
                admin,
                clean.toTypedArray(),
                suspended
            ).toSet()
        } catch (_: Exception) {
            clean.toSet()
        }
    }

    fun setPrivateDns(
        enabled: Boolean,
        host: String
    ): Boolean {
        if (!isDeviceOwner() || Build.VERSION.SDK_INT < 29) {
            return false
        }

        return try {
            if (enabled && host.isNotBlank()) {
                dpm.setGlobalPrivateDnsModeSpecifiedHost(
                    admin,
                    host.trim()
                )
            } else {
                dpm.setGlobalPrivateDnsModeOpportunistic(
                    admin
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setWifiConfigLocked(
        locked: Boolean
    ): Boolean {
        if (!isDeviceOwner()) {
            return false
        }

        return try {
            if (locked) {
                dpm.addUserRestriction(
                    admin,
                    UserManager.DISALLOW_CONFIG_WIFI
                )
            } else {
                dpm.clearUserRestriction(
                    admin,
                    UserManager.DISALLOW_CONFIG_WIFI
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
