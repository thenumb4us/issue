package com.issue.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    companion object {
        private const val PREFS = "issue_prefs"

        private const val KEY_ROLE = "role"
        private const val KEY_PARENT_TOKEN = "parent_token"

        private const val KEY_CHILD_ID = "child_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_CHILD_APPROVED = "child_approved"
        private const val KEY_PAIRING_ID = "pairing_id"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_QR_TOKEN = "qr_token"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pairingPolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val role = prefs().getString(KEY_ROLE, null)

        when (role) {
            "parent" -> {
                if (parentToken().isBlank()) {
                    showParentLogin()
                } else {
                    loadParentDashboard()
                }
            }

            "child" -> {
                val childId = prefs()
                    .getString(KEY_CHILD_ID, "")
                    .orEmpty()

                val deviceId = prefs()
                    .getString(KEY_DEVICE_ID, "")
                    .orEmpty()
                val deviceToken = prefs()
                    .getString(KEY_DEVICE_TOKEN, "")
                    .orEmpty()
                val pairingId = prefs()
                    .getString(KEY_PAIRING_ID, "")
                    .orEmpty()

                if (
                    childId.isNotBlank() &&
                    deviceId.isNotBlank() &&
                    deviceToken.isNotBlank() &&
                    prefs().getBoolean(KEY_CHILD_APPROVED, false)
                ) {
                    showChildActive(null)
                } else if (pairingId.isNotBlank()) {
                    showChildPairing()
                } else {
                    showChildSignup()
                }
            }

            else -> showRoleSelection()
        }
    }

    override fun onDestroy() {
        pairingPolling = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showRoleSelection() {
        pairingPolling = false

        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(18))
        root.addView(modeTitle("Welcome to ISSUE"))
        root.addView(space(32))

        root.addView(
            primaryButton("Parent Mode") {
                prefs()
                    .edit()
                    .putString(KEY_ROLE, "parent")
                    .apply()

                showParentLogin()
            }
        )

        root.addView(space(14))

        root.addView(
            primaryButton("Child Mode") {
                prefs()
                    .edit()
                    .putString(KEY_ROLE, "child")
                    .apply()

                showChildSignup()
            }
        )

        setContentView(scroll(root))
    }

    private fun showParentLogin() {
        pairingPolling = false

        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(12))
        root.addView(modeTitle("Parent Login"))
        root.addView(space(24))

        val email = field("Approved Gmail")
        val password = field(
            "ISSUE Password",
            password = true
        )

        root.addView(email)
        root.addView(space(12))
        root.addView(password)
        root.addView(space(22))

        root.addView(
            primaryButton("Login") {
                val emailValue =
                    email.text.toString().trim()

                val passwordValue =
                    password.text.toString()

                if (
                    emailValue.isBlank() ||
                    passwordValue.isBlank()
                ) {
                    toast("Enter Gmail and password")
                } else {
                    parentLogin(
                        emailValue,
                        passwordValue
                    )
                }
            }
        )

        root.addView(space(12))

        root.addView(
            secondaryButton("Use Recovery Code") {
                showRecoveryLogin()
            }
        )

        root.addView(space(12))

        root.addView(
            secondaryButton("Change Mode") {
                clearRoleOnly()
                showRoleSelection()
            }
        )

        setContentView(scroll(root))
    }

    private fun parentLogin(
        email: String,
        password: String
    ) {
        showLoading("Signing in...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/auth/login",
                    JSONObject().apply {
                        put("email", email)
                        put("password", password)
                    }
                )
            },
            success = { response ->
                if (response.statusCode in 200..299) {
                    val json = safeJson(response.body)

                    val token = findString(
                        json,
                        "token",
                        "accessToken",
                        "access_token",
                        "jwt"
                    )

                    if (token.isBlank()) {
                        toast(
                            "Login succeeded but token was missing"
                        )
                        showParentLogin()
                        return@background
                    }

                    prefs()
                        .edit()
                        .putString(
                            KEY_PARENT_TOKEN,
                            token
                        )
                        .apply()

                    loadParentDashboard()
                } else {
                    toast(
                        apiMessage(
                            response.body,
                            "Login failed"
                        )
                    )
                    showParentLogin()
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                showParentLogin()
            }
        )
    }

    private fun showRecoveryLogin() {
        pairingPolling = false

        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(12))
        root.addView(modeTitle("Recovery Access"))
        root.addView(space(22))

        val email = field("Approved Gmail")
        val code = field(
            "Recovery Access Code",
            password = true
        )

        root.addView(email)
        root.addView(space(12))
        root.addView(code)
        root.addView(space(20))

        root.addView(
            primaryButton("Continue") {
                val emailValue =
                    email.text.toString().trim()

                val codeValue =
                    code.text.toString().trim()

                if (
                    emailValue.isBlank() ||
                    codeValue.isBlank()
                ) {
                    toast(
                        "Enter Gmail and recovery code"
                    )
                } else {
                    recoveryLogin(
                        emailValue,
                        codeValue
                    )
                }
            }
        )

        root.addView(space(12))

        root.addView(
            secondaryButton("Back") {
                showParentLogin()
            }
        )

        setContentView(scroll(root))
    }

    private fun recoveryLogin(
        email: String,
        code: String
    ) {
        showLoading("Checking recovery code...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/recovery/login",
                    JSONObject().apply {
                        put("email", email)
                        put("code", code)
                    }
                )
            },
            success = { response ->
                if (response.statusCode in 200..299) {
                    val json = safeJson(response.body)

                    val token = findString(
                        json,
                        "token",
                        "accessToken",
                        "access_token",
                        "jwt"
                    )

                    if (token.isBlank()) {
                        toast(
                            "Recovery succeeded but token was missing"
                        )
                        showRecoveryLogin()
                        return@background
                    }

                    prefs()
                        .edit()
                        .putString(
                            KEY_PARENT_TOKEN,
                            token
                        )
                        .apply()

                    loadParentDashboard()
                } else {
                    toast(
                        apiMessage(
                            response.body,
                            "Recovery login failed"
                        )
                    )
                    showRecoveryLogin()
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                showRecoveryLogin()
            }
        )
    }

    private fun loadParentDashboard() {
        pairingPolling = false

        val token = parentToken()

        if (token.isBlank()) {
            showParentLogin()
            return
        }

        showLoading("Loading dashboard...")

        background(
            action = {
                ApiClient.get(
                    "/api/parent/dashboard",
                    token
                )
            },
            success = { response ->
                if (response.statusCode == 401) {
                    clearParentSession()
                    toast("Session expired")
                    showParentLogin()
                    return@background
                }

                if (response.statusCode !in 200..299) {
                    showParentDashboard(
                        null,
                        apiMessage(
                            response.body,
                            "Dashboard unavailable"
                        )
                    )
                    return@background
                }

                showParentDashboard(
                    safeJson(response.body),
                    null
                )
            },
            failure = {
                showParentDashboard(
                    null,
                    "Backend connection failed"
                )
            }
        )
    }

    private fun showParentDashboard(
        json: JSONObject?,
        warning: String?
    ) {
        pairingPolling = false

        val blue = Color.rgb(37, 99, 235)
        val ink = Color.rgb(24, 35, 58)
        val muted = Color.rgb(94, 108, 132)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 58, 36, 48)
            setBackgroundColor(Color.rgb(246, 249, 255))
        }

        fun rounded(color: Int, radius: Float = 30f) =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = radius
            }

        fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

        fun tile(heading: String, detail: String) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 24, 30, 24)
                background = rounded(Color.WHITE, 26f)
                elevation = 3f
                addView(label(heading, 15f, muted))
                addView(label(detail, 23f, ink, true))
            }

        fun action(name: String, emphasized: Boolean = false, click: () -> Unit) =
            Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 16f
                setTextColor(if (emphasized) Color.WHITE else blue)
                background = rounded(if (emphasized) blue else Color.WHITE, 28f)
                elevation = 3f
                setOnClickListener { click() }
            }

        val summary = json?.optJSONObject("summary")
            ?: json?.optJSONObject("data")?.optJSONObject("summary")
        val total = summary?.optInt("totalChildren", -1) ?: -1
        val online = summary?.optInt("onlineChildren",
            summary.optInt("online", -1)) ?: -1
        val alerts = summary?.optInt("criticalAlerts",
            summary.optInt("alerts", -1)) ?: -1

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 32, 34, 32)
            background = rounded(blue, 38f)
            addView(label("ISSUE", 31f, Color.WHITE, true))
            addView(space(8))
            addView(label("Family Dashboard", 20f, Color.WHITE, true))
            addView(space(8))
            addView(label("Your child's safety, at a glance", 14f,
                Color.rgb(225, 236, 255)))
        }
        root.addView(hero)
        root.addView(space(22))

        if (!warning.isNullOrBlank()) {
            root.addView(tile("Connection", warning))
            root.addView(space(12))
        }

        root.addView(label("Overview", 21f, ink, true))
        root.addView(space(12))
        root.addView(tile("Children", if (total >= 0) "$total linked" else "Unavailable"))
        root.addView(space(10))
        root.addView(tile("Online", if (online >= 0) "$online devices" else "Unavailable"))
        root.addView(space(10))
        root.addView(tile("Critical alerts", if (alerts >= 0) "$alerts alerts" else "Unavailable"))
        root.addView(space(22))

        if (total == 0) {
            root.addView(tile("Get started", "No child linked yet. Approve a pairing to unlock child controls."))
            root.addView(space(12))
        }

        root.addView(label("My Kids", 21f, ink, true))
        root.addView(space(12))
        root.addView(action("Children & Controls", true) { loadChildren() })
        root.addView(space(10))
        root.addView(action("Pending Pairings") { loadPendingPairings() })
        root.addView(space(10))
        root.addView(action("Approve by 6-digit Code") { showPairingCodeLookup() })
        root.addView(space(22))

        root.addView(label("Account", 21f, ink, true))
        root.addView(space(12))
        root.addView(action("Refresh Dashboard") { loadParentDashboard() })
        root.addView(space(10))
        root.addView(action("Logout") {
            clearParentSession()
            showParentLogin()
        })
        root.addView(space(10))
        root.addView(action("Change Mode") {
            clearRoleOnly()
            showRoleSelection()
        })

        setContentView(scroll(root))
    }

    private fun loadChildren() {
        val token = parentToken()

        showLoading("Loading children...")

        background(
            action = {
                ApiClient.get(
                    "/api/parent/children",
                    token
                )
            },
            success = { response ->
                if (response.statusCode == 401) {
                    clearParentSession()
                    showParentLogin()
                    return@background
                }

                if (response.statusCode !in 200..299) {
                    toast(
                        apiMessage(
                            response.body,
                            "Could not load children"
                        )
                    )
                    loadParentDashboard()
                    return@background
                }

                val json = safeJson(response.body)

                showChildren(
                    findArray(
                        json,
                        "children",
                        "items",
                        "data"
                    )
                )
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                loadParentDashboard()
            }
        )
    }

    private fun showChildren(
        children: JSONArray?
    ) {
        val blue = Color.rgb(37, 99, 235)
        val ink = Color.rgb(25, 35, 58)
        val muted = Color.rgb(100, 116, 139)
        fun shape(color: Int, radius: Float = 28f) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
        fun textView(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 26)
            background = shape(Color.WHITE)
            elevation = 4f
        }
        fun action(label: String, filled: Boolean = true, onTap: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(20, 22, 20, 22)
                setTextColor(if (filled) Color.WHITE else blue)
                background = shape(if (filled) blue else Color.rgb(232, 240, 255))
                setOnClickListener { onTap() }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 52, 36, 48)
            setBackgroundColor(Color.rgb(246, 249, 255))
        }
        root.addView(textView("ISSUE", 29f, ink, true))
        root.addView(space(6))
        root.addView(textView("My Kids", 22f, blue, true))
        root.addView(space(12))
        root.addView(textView("Linked children and their approved devices", 15f, muted))
        root.addView(space(24))

        if (
            children == null ||
            children.length() == 0
        ) {
            val empty = card()
            empty.addView(textView("No children linked yet", 19f, ink, true))
            empty.addView(space(8))
            empty.addView(textView("Approve a pairing code from the dashboard to add a child device.", 15f, muted))
            root.addView(empty)
        } else {
            for (
                index in 0 until children.length()
            ) {
                val child =
                    children.optJSONObject(index)
                        ?: continue

                val childId =
                    findString(
                        child,
                        "childId",
                        "id"
                    )

                val deviceId =
                    findString(
                        child,
                        "deviceId"
                    )

                val name =
                    findString(
                        child,
                        "fullName",
                        "name",
                        "username"
                    ).ifBlank {
                        "Child ${index + 1}"
                    }

                val username =
                    child.optString(
                        "username",
                        ""
                    )

                val status =
                    findString(
                        child,
                        "status",
                        "accountStatus",
                        "deviceStatus",
                        "connectionStatus"
                    )

                val childCard = card()
                val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(TextView(this).apply {
                    text = name.take(1).uppercase()
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    background = shape(blue, 80f)
                }, LinearLayout.LayoutParams(66, 66))
                val names = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 0, 0, 0)
                }
                names.addView(textView(name, 20f, ink, true))
                names.addView(textView(if (status.isBlank()) "Awaiting device status" else status.lowercase().replaceFirstChar { it.uppercase() }, 14f, if (status.equals("APPROVED", true)) Color.rgb(22, 163, 74) else muted))
                top.addView(names)
                childCard.addView(top)
                if (username.isNotBlank()) {
                    childCard.addView(space(12))
                    childCard.addView(textView("@$username", 14f, muted))
                }
                childCard.addView(space(18))

                if (childId.isNotBlank() && status.equals("APPROVED", ignoreCase = true)) {
                    childCard.addView(action("Open controls & reports") {
                        startActivity(
                            Intent(this, ParentRemoteControlsActivity::class.java)
                                .putExtra(ParentRemoteControlsActivity.EXTRA_CHILD_ID, childId)
                                .putExtra(ParentRemoteControlsActivity.EXTRA_CHILD_NAME, name)
                        )
                    })
                } else {
                    childCard.addView(textView("Controls unlock after the pairing is approved.", 14f, muted))
                }
                root.addView(childCard)
                root.addView(space(14))

                if (deviceId.isNotBlank()) {
                    root.addView(action("Remove device", false) { removeDevice(deviceId) })
                    root.addView(space(10))
                }

                if (childId.isNotBlank()) {
                    root.addView(action("Remove child", false) { removeChild(childId) })
                    root.addView(space(14))
                }
            }
        }

        root.addView(action("Refresh list", false) { loadChildren() })
        root.addView(space(10))
        root.addView(action("Back to dashboard", false) { loadParentDashboard() })

        setContentView(scroll(root))
    }

    private fun removeChild(
        childId: String
    ) {
        val token = parentToken()

        showLoading("Removing child...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/children/$childId/remove",
                    JSONObject(),
                    token
                )
            },
            success = { response ->
                if (
                    response.statusCode in 200..299
                ) {
                    toast("Child removed ✔")
                    loadChildren()
                } else {
                    toast(
                        apiMessage(
                            response.body,
                            "Could not remove child"
                        )
                    )
                    loadChildren()
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                loadChildren()
            }
        )
    }

    private fun removeDevice(
        deviceId: String
    ) {
        val token = parentToken()

        showLoading("Removing device...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/devices/$deviceId/remove",
                    JSONObject(),
                    token
                )
            },
            success = { response ->
                if (
                    response.statusCode in 200..299
                ) {
                    toast("Device removed ✔")
                    loadChildren()
                } else {
                    toast(
                        apiMessage(
                            response.body,
                            "Could not remove device"
                        )
                    )
                    loadChildren()
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                loadChildren()
            }
        )
    }

    private fun loadPendingPairings() {
        val token = parentToken()

        showLoading(
            "Loading pending pairings..."
        )

        background(
            action = {
                ApiClient.get(
                    "/api/parent/pairings",
                    token
                )
            },
            success = { response ->
                if (response.statusCode == 401) {
                    clearParentSession()
                    showParentLogin()
                    return@background
                }

                if (response.statusCode !in 200..299) {
                    toast(
                        apiMessage(
                            response.body,
                            "Could not load pairings"
                        )
                    )
                    loadParentDashboard()
                    return@background
                }

                val json = safeJson(response.body)

                showPendingPairings(
                    findArray(
                        json,
                        "pairings",
                        "items",
                        "data"
                    )
                )
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                loadParentDashboard()
            }
        )
    }

    private fun showPendingPairings(
        pairings: JSONArray?
    ) {
        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(10))
        root.addView(
            modeTitle("Pending Pairings")
        )
        root.addView(space(18))

        if (
            pairings == null ||
            pairings.length() == 0
        ) {
            root.addView(
                text(
                    "No pending pairing requests."
                )
            )
        } else {
            for (
                index in 0 until pairings.length()
            ) {
                val pairing =
                    pairings.optJSONObject(index)
                        ?: continue

                val pairingId =
                    findString(
                        pairing,
                        "pairingId",
                        "id"
                    )

                val pairingCode =
                    findString(
                        pairing,
                        "pairingCode",
                        "code"
                    )

                val childName =
                    findString(
                        pairing,
                        "childName",
                        "fullName",
                        "username"
                    ).ifBlank {
                        "Child device"
                    }

                val deviceName =
                    findString(
                        pairing,
                        "deviceName",
                        "device"
                    )

                val status =
                    findString(
                        pairing,
                        "status"
                    )

                val details = buildString {
                    append(childName)

                    if (deviceName.isNotBlank()) {
                        append("\nDevice: ")
                        append(deviceName)
                    }

                    if (pairingCode.isNotBlank()) {
                        append("\nCode: ")
                        append(pairingCode)
                    }

                    if (status.isNotBlank()) {
                        append("\nStatus: ")
                        append(status)
                    }
                }

                root.addView(
                    infoCard(
                        "Pairing Request",
                        details
                    )
                )

                if (pairingId.isNotBlank()) {
                    root.addView(space(8))

                    root.addView(
                        primaryButton("Approve") {
                            approvePairing(
                                pairingId
                            )
                        }
                    )
                }

                root.addView(space(18))
            }
        }

        root.addView(
            secondaryButton("Refresh") {
                loadPendingPairings()
            }
        )

        root.addView(space(10))

        root.addView(
            secondaryButton("Back") {
                loadParentDashboard()
            }
        )

        setContentView(scroll(root))
    }

    private fun approvePairing(
        pairingId: String
    ) {
        val token = parentToken()

        showLoading("Approving child...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/pairing/" +
                        pairingId +
                        "/approve",
                    JSONObject(),
                    token
                )
            },
            success = { response ->
                if (response.statusCode in 200..299) {
                    toast("Child approved ✔")
                    loadPendingPairings()
                } else {
                    toast(
                        apiMessage(
                            response.body,
                            "Approval failed"
                        )
                    )
                    loadPendingPairings()
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                loadPendingPairings()
            }
        )
    }

    private fun showPairingCodeLookup() {
        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(10))
        root.addView(
            modeTitle("Find Pairing")
        )
        root.addView(space(20))

        val code =
            field(
                "6-digit pairing code",
                numeric = true
            )

        root.addView(code)
        root.addView(space(18))

        root.addView(
            primaryButton("Find") {
                val value =
                    code.text
                        .toString()
                        .trim()

                if (value.length != 6) {
                    toast(
                        "Enter the 6-digit code"
                    )
                } else {
                    findPairingByCode(value)
                }
            }
        )

        root.addView(space(10))

        root.addView(
            secondaryButton("Back") {
                loadParentDashboard()
            }
        )

        setContentView(scroll(root))
    }

    private fun findPairingByCode(
        code: String
    ) {
        val token = parentToken()

        showLoading("Finding pairing...")

        background(
            action = {
                ApiClient.post(
                    "/api/parent/pairing/find",
                    JSONObject().apply {
                        put("code", code)
                    },
                    token
                )
            },
            success = { response ->
                if (response.statusCode !in 200..299) {
                    toast(
                        apiMessage(
                            response.body,
                            "Pairing not found"
                        )
                    )
                    showPairingCodeLookup()
                    return@background
                }

                val json = safeJson(response.body)

                val pairingId =
                    findString(
                        json,
                        "pairingId",
                        "id"
                    )

                if (pairingId.isBlank()) {
                    toast(
                        "Pairing ID missing"
                    )
                    showPairingCodeLookup()
                } else {
                    approvePairing(pairingId)
                }
            },
            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
                showPairingCodeLookup()
            }
        )
    }

    private fun showChildSignup() {
        pairingPolling = false

        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(30))
        root.addView(
            modeTitle("Setting Up Child Device")
        )
        root.addView(space(25))
        root.addView(ProgressBar(this))
        root.addView(space(20))
        root.addView(
            text(
                "Registering this device automatically..."
            )
        )

        setContentView(scroll(root))

        zeroEnrollChild()
    }

    private fun zeroEnrollChild() {
        background(
            action = {
                val manufacturer =
                    android.os.Build.MANUFACTURER
                        ?: "Android"

                val model =
                    android.os.Build.MODEL
                        ?: "Device"

                val deviceName =
                    listOf(
                        manufacturer,
                        model
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .trim()
                        .ifBlank {
                            "Android Device"
                        }

                ApiClient.post(
                    "/api/child/zero-enroll",
                    JSONObject().apply {
                        put(
                            "installationId",
                            installationId()
                        )

                        put(
                            "deviceName",
                            deviceName
                        )

                        put(
                            "manufacturer",
                            manufacturer
                        )

                        put(
                            "model",
                            model
                        )

                        put(
                            "androidVersion",
                            android.os.Build.VERSION.RELEASE
                                ?: ""
                        )

                        put(
                            "sdkVersion",
                            android.os.Build.VERSION.SDK_INT
                        )
                    }
                )
            },

            success = { response ->
                if (
                    response.statusCode !in 200..299
                ) {
                    showZeroEnrollError(
                        apiMessage(
                            response.body,
                            "Automatic setup failed"
                        )
                    )
                    return@background
                }

                val json =
                    safeJson(response.body)

                val childId =
                    findString(
                        json,
                        "childId"
                    )

                val deviceId =
                    findString(
                        json,
                        "deviceId"
                    )

                val deviceToken = findString(json, "deviceToken")

                if (
                    childId.isBlank() ||
                    deviceId.isBlank() ||
                    deviceToken.isBlank()
                ) {
                    showZeroEnrollError(
                        "Device information missing"
                    )
                    return@background
                }

                prefs()
                    .edit()
                    .putString(
                        KEY_CHILD_ID,
                        childId
                    )
                    .putString(
                        KEY_DEVICE_ID,
                        deviceId
                    )
                    .putString(
                        KEY_DEVICE_TOKEN,
                        deviceToken
                    )
                    .putBoolean(
                        KEY_CHILD_APPROVED,
                        findString(json, "status").equals("APPROVED", ignoreCase = true)
                    )
                    .remove(KEY_PAIRING_ID)
                    .remove(KEY_PAIRING_CODE)
                    .remove(KEY_QR_TOKEN)
                    .apply()

                if (prefs().getBoolean(KEY_CHILD_APPROVED, false)) {
                    showChildActive(json)
                } else {
                    toast("Parent approval is required before supervision starts")
                    showChildSignup()
                }
            },

            failure = {
                showZeroEnrollError(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )
            }
        )
    }

    private fun showZeroEnrollError(
        message: String
    ) {
        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(20))
        root.addView(
            modeTitle("Setup Problem")
        )
        root.addView(space(20))
        root.addView(
            warningText(message)
        )
        root.addView(space(20))

        root.addView(
            primaryButton("Retry") {
                showChildSignup()
            }
        )

        root.addView(space(12))

        root.addView(
            secondaryButton("Change Mode") {
                clearRoleOnly()
                showRoleSelection()
            }
        )

        setContentView(scroll(root))
    }

    private fun installationId(): String {
        val existing =
            prefs()
                .getString(
                    KEY_INSTALLATION_ID,
                    ""
                )
                .orEmpty()

        if (existing.isNotBlank()) {
            return existing
        }

        val generated =
            java.util.UUID
                .randomUUID()
                .toString()

        prefs()
            .edit()
            .putString(
                KEY_INSTALLATION_ID,
                generated
            )
            .apply()

        return generated
    }

    private fun autoEnrollChild(
        email: String,
        password: String
    ) {
        showLoading(
            "Verifying parent and setting up device..."
        )

        background(
            action = {
                val manufacturer =
                    android.os.Build.MANUFACTURER
                        ?: "Android"

                val model =
                    android.os.Build.MODEL
                        ?: "Device"

                val deviceName =
                    listOf(
                        manufacturer,
                        model
                    )
                        .filter {
                            it.isNotBlank()
                        }
                        .joinToString(" ")
                        .trim()

                ApiClient.post(
                    "/api/child/auto-enroll",
                    JSONObject().apply {
                        put("email", email)
                        put("password", password)

                        put(
                            "deviceName",
                            deviceName.ifBlank {
                                "Android Device"
                            }
                        )

                        put(
                            "manufacturer",
                            manufacturer
                        )

                        put(
                            "model",
                            model
                        )

                        put(
                            "androidVersion",
                            android.os.Build.VERSION.RELEASE
                                ?: ""
                        )

                        put(
                            "sdkVersion",
                            android.os.Build.VERSION.SDK_INT
                        )

                        put(
                            "appVersion",
                            "1.0.0"
                        )

                        put(
                            "appVersionCode",
                            1
                        )

                        put(
                            "installationId",
                            installationId()
                        )
                    }
                )
            },

            success = { response ->
                if (
                    response.statusCode !in 200..299
                ) {
                    toast(
                        apiMessage(
                            response.body,
                            "Device setup failed"
                        )
                    )

                    showChildSignup()
                    return@background
                }

                val json =
                    safeJson(response.body)

                val childId =
                    findString(
                        json,
                        "childId"
                    )

                val deviceId =
                    findString(
                        json,
                        "deviceId"
                    )

                if (
                    childId.isBlank() ||
                    deviceId.isBlank()
                ) {
                    toast(
                        "Setup succeeded but device information was missing"
                    )

                    showChildSignup()
                    return@background
                }

                prefs()
                    .edit()
                    .putString(
                        KEY_CHILD_ID,
                        childId
                    )
                    .putString(
                        KEY_DEVICE_ID,
                        deviceId
                    )
                    .remove(KEY_PAIRING_ID)
                    .remove(KEY_PAIRING_CODE)
                    .remove(KEY_QR_TOKEN)
                    .apply()

                showChildActive(json)
            },

            failure = {
                toast(
                    "Connection error: " +
                        (it.message ?: "Unknown")
                )

                showChildSignup()
            }
        )
    }

    private fun createChild(
        fullName: String,
        username: String,
        password: String,
        dob: String,
        deviceName: String
    ) {
        showLoading(
            "Creating child account..."
        )

        background(
            action = {
                ApiClient.post(
                    "/api/child/signup",
                    JSONObject().apply {
                        put(
                            "fullName",
                            fullName
                        )
                        put(
                            "username",
                            username
                        )
                        put(
                            "password",
                            password
                        )
                        put("dateOfBirth", dob)
                        put("deviceName", deviceName)
                    }
                )
            },
            success = { response ->
                if (response.statusCode !in 200..299) {
                    toast(apiMessage(response.body, "Child signup failed"))
                    showChildSignup()
                    return@background
                }

                val json = safeJson(response.body)
                val pairingId = findString(json, "pairingId", "id")

                if (pairingId.isBlank()) {
                    toast("Pairing information missing")
                    showChildSignup()
                    return@background
                }

                val deviceToken = findString(json, "deviceToken")
                if (deviceToken.isBlank()) {
                    toast("Secure device session was not issued")
                    showChildSignup()
                    return@background
                }

                prefs().edit()
                    .putString(KEY_CHILD_ID, findString(json, "childId"))
                    .putString(KEY_DEVICE_ID, findString(json, "deviceId"))
                    .putString(KEY_DEVICE_TOKEN, deviceToken)
                    .putBoolean(KEY_CHILD_APPROVED, false)
                    .putString(KEY_PAIRING_ID, pairingId)
                    .putString(KEY_PAIRING_CODE, findString(json, "pairingCode", "code"))
                    .putString(KEY_QR_TOKEN, findString(json, "qrToken", "qr"))
                    .apply()

                showChildPairing()
            },
            failure = {
                toast("Connection error: ${it.message ?: "Unknown"}")
                showChildSignup()
            }
        )
    }

    private fun showChildPairing() {
        pairingPolling = false

        val pairingId = prefs().getString(KEY_PAIRING_ID, "").orEmpty()
        val code = prefs().getString(KEY_PAIRING_CODE, "").orEmpty()

        if (pairingId.isBlank()) {
            showChildSignup()
            return
        }

        val root = base()
        root.addView(title("ISSUE"))
        root.addView(space(10))
        root.addView(modeTitle("Parent Approval Required"))
        root.addView(space(20))
        root.addView(text("Ask your parent to approve this device."))

        if (code.isNotBlank()) {
            root.addView(space(20))
            root.addView(bigCode(code))
            root.addView(space(8))
            root.addView(text("6-digit pairing code"))
        }

        root.addView(space(24))

        root.addView(primaryButton("Check Approval") {
            checkPairingStatus(pairingId, true)
        })

        root.addView(space(10))

        root.addView(secondaryButton("Start Over") {
            clearChildSession()
            showChildSignup()
        })

        root.addView(space(10))

        root.addView(secondaryButton("Change Mode") {
            clearRoleOnly()
            showRoleSelection()
        })

        setContentView(scroll(root))
        startPairingPolling(pairingId)
    }

    private fun startPairingPolling(pairingId: String) {
        pairingPolling = true

        val task = object : Runnable {
            override fun run() {
                if (!pairingPolling) return

                checkPairingStatus(pairingId, false)

                if (pairingPolling) {
                    handler.postDelayed(this, 5000L)
                }
            }
        }

        handler.postDelayed(task, 3000L)
    }

    private fun checkPairingStatus(
        pairingId: String,
        manual: Boolean
    ) {
        if (manual) toast("Checking approval...")

        Thread {
            try {
                val response = ApiClient.get(
                    "/api/child/pairing/$pairingId/status"
                )

                runOnUiThread {
                    if (response.statusCode !in 200..299) {
                        if (manual) {
                            toast(
                                apiMessage(
                                    response.body,
                                    "Could not check approval"
                                )
                            )
                        }
                        return@runOnUiThread
                    }

                    val json = safeJson(response.body)
                    val status = findString(
                        json,
                        "status",
                        "pairingStatus"
                    ).lowercase()

                    when (status) {
                        "approved", "active", "paired" -> {
                            pairingPolling = false
                            prefs().edit()
                                .putBoolean(KEY_CHILD_APPROVED, true)
                                .apply()
                            showChildActive(json)
                        }

                        "rejected", "denied", "expired" -> {
                            pairingPolling = false
                            toast("Pairing status: $status")
                            clearChildSession()
                            showChildSignup()
                        }

                        else -> {
                            if (manual) {
                                toast("Waiting for parent approval")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (manual) {
                    runOnUiThread {
                        toast("Connection error: ${e.message ?: "Unknown"}")
                    }
                }
            }
        }.start()
    }

    private fun showChildActive(
        json: JSONObject?
    ) {
        pairingPolling = false

        if (prefs().getString(KEY_ROLE, "") == "child" &&
            prefs().getBoolean(KEY_CHILD_APPROVED, false) &&
            !prefs().getString(KEY_DEVICE_TOKEN, "").isNullOrBlank()
        ) {
            IssueSupervisionService.start(applicationContext)
        }

        val manager =
            DeviceOwnerManager(this)

        val root = base()

        root.addView(title("ISSUE"))
        root.addView(space(10))

        root.addView(
            modeTitle(
                "Child Device Active"
            )
        )

        root.addView(space(22))

        root.addView(
            successText(
                "Device linked to parent ✔"
            )
        )

        root.addView(space(18))

        val deviceId =
            findString(
                json,
                "deviceId"
            ).ifBlank {
                prefs()
                    .getString(
                        KEY_DEVICE_ID,
                        ""
                    )
                    .orEmpty()
            }

        if (deviceId.isNotBlank()) {
            root.addView(
                infoCard(
                    "Device ID",
                    deviceId
                )
            )

            root.addView(space(12))
        }

        root.addView(
            infoCard(
                "Supervision",
                manager.supervisionStatus()
            )
        )

        root.addView(space(12))

        val uninstallState =
            if (
                manager
                    .isIssueUninstallBlocked()
            ) {
                "Protected"
            } else {
                "Not Protected"
            }

        root.addView(
            infoCard(
                "ISSUE Uninstall",
                uninstallState
            )
        )

        val controls = getSharedPreferences("issue_controls", MODE_PRIVATE)
        val usedMinutes = DeviceSyncManager.usageMinutes(this)
        root.addView(space(12))
        root.addView(infoCard("Today", usedMinutes?.let { "$it min used" }
            ?: "Usage permission needed"))
        root.addView(space(12))
        root.addView(infoCard("Daily Limit",
            if (controls.contains("daily_limit")) {
                "${controls.getInt("daily_limit", 180)} min, set by parent"
            } else {
                "Waiting for parent policy"
            }))
        root.addView(space(12))
        root.addView(infoCard("School Mode",
            if (controls.getBoolean("school_mode", false)) "On" else "Off"))
        root.addView(space(12))
        root.addView(infoCard("Location Permission",
            if (DeviceSyncManager.hasLocationPermission(this)) "Allowed" else "Needed"))
        root.addView(space(20))
        root.addView(primaryButton("Request 30 More Minutes") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Request more time?")
                .setMessage("Your parent will see this request. The limit will change only if your parent decides to update it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Request") { _, _ -> sendMoreTimeRequest() }
                .show()
        })
        root.addView(space(10))
        root.addView(primaryButton("SOS / Request Help") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Send SOS to parent?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send") { _, _ -> sendChildSos() }
                .show()
        })

        setContentView(
            scroll(root)
        )
    }

    private fun sendMoreTimeRequest() {
        val token = prefs().getString(KEY_DEVICE_TOKEN, "").orEmpty()
        if (token.isBlank() || !prefs().getBoolean(KEY_CHILD_APPROVED, false)) {
            toast("Parent approval required")
            return
        }
        background(
            action = {
                ApiClient.post(
                    "/api/device/request-more-time",
                    JSONObject().put("minutes", 30),
                    token
                )
            },
            success = { response ->
                toast(if (response.statusCode in 200..299) {
                    "Request sent. Your parent will see it after refresh."
                } else {
                    apiMessage(response.body, "Request could not be sent")
                })
            },
            failure = { toast("Connection error: ${it.message ?: "Unknown"}") }
        )
    }

    private fun sendChildSos() {
        val token = prefs().getString(KEY_DEVICE_TOKEN, "").orEmpty()
        if (token.isBlank() || !prefs().getBoolean(KEY_CHILD_APPROVED, false)) {
            toast("Parent approval required")
            return
        }
        background(
            action = {
                ApiClient.post(
                    "/api/device/sos",
                    JSONObject().put("note", "Child requested help"),
                    token
                )
            },
            success = { response ->
                toast(if (response.statusCode in 200..299) {
                    "SOS recorded. Parent can see it after refresh."
                } else {
                    apiMessage(response.body, "SOS could not be sent")
                })
            },
            failure = {
                toast("Connection error: ${it.message ?: "Unknown"}")
            }
        )
    }

    private fun showLoading(message: String) {
        pairingPolling = false

        val root = base()
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(title("ISSUE"))
        root.addView(space(30))
        root.addView(ProgressBar(this))
        root.addView(space(18))
        root.addView(text(message))

        setContentView(scroll(root))
    }

    private fun background(
        action: () -> ApiClient.Response,
        success: (ApiClient.Response) -> Unit,
        failure: (Exception) -> Unit
    ) {
        Thread {
            try {
                val response = action()
                runOnUiThread { success(response) }
            } catch (e: Exception) {
                runOnUiThread { failure(e) }
            }
        }.start()
    }

    private fun safeJson(body: String): JSONObject =
        try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }

    private fun findString(
        json: JSONObject?,
        vararg keys: String
    ): String {
        if (json == null) return ""

        for (key in keys) {
            val value = json.optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }

        for (section in arrayOf(
            "data",
            "result",
            "session",
            "pairing",
            "child",
            "device"
        )) {
            val nested = json.optJSONObject(section) ?: continue

            for (key in keys) {
                val value = nested.optString(key, "")
                if (value.isNotBlank() && value != "null") return value
            }
        }

        return ""
    }

    private fun findArray(
        json: JSONObject?,
        vararg keys: String
    ): JSONArray? {
        if (json == null) return null

        for (key in keys) {
            json.optJSONArray(key)?.let { return it }
        }

        val data = json.optJSONObject("data")

        if (data != null) {
            for (key in keys) {
                data.optJSONArray(key)?.let { return it }
            }
        }

        return null
    }

    private fun apiMessage(
        body: String,
        fallback: String
    ): String =
        findString(
            safeJson(body),
            "message",
            "error",
            "detail"
        ).ifBlank { fallback }

    private fun parentToken(): String =
        prefs().getString(KEY_PARENT_TOKEN, "").orEmpty()

    private fun clearParentSession() {
        prefs().edit()
            .remove(KEY_PARENT_TOKEN)
            .apply()
    }

    private fun clearChildSession() {
        pairingPolling = false
        IssueSupervisionService.stop(this)

        prefs().edit()
            .remove(KEY_CHILD_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_CHILD_APPROVED)
            .remove(KEY_PAIRING_ID)
            .remove(KEY_PAIRING_CODE)
            .remove(KEY_QR_TOKEN)
            .apply()
    }

    private fun clearRoleOnly() {
        pairingPolling = false
        IssueSupervisionService.stop(this)
        prefs().edit().remove(KEY_ROLE).apply()
    }

    private fun prefs() =
        getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun base() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 70, 40, 70)
            setBackgroundColor(Color.rgb(11, 11, 16))
        }

    private fun scroll(content: View) =
        ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

    private fun title(value: String) =
        TextView(this).apply {
            text = value
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun modeTitle(value: String) =
        TextView(this).apply {
            text = value
            textSize = 23f
            setTextColor(Color.rgb(190, 160, 255))
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun text(value: String) =
        TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(Color.rgb(220, 220, 230))
        }

    private fun warningText(value: String) =
        TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(Color.rgb(255, 190, 120))
        }

    private fun successText(value: String) =
        TextView(this).apply {
            text = value
            textSize = 18f
            setTextColor(Color.rgb(100, 230, 150))
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun bigCode(value: String) =
        TextView(this).apply {
            text = value
            textSize = 34f
            letterSpacing = 0.18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10, 18, 10, 18)
            setBackgroundColor(Color.rgb(28, 24, 42))
        }

    private fun infoCard(
        heading: String,
        value: String
    ) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.rgb(23, 23, 31))

            addView(
                TextView(this@MainActivity).apply {
                    text = heading
                    textSize = 14f
                    setTextColor(Color.rgb(185, 165, 255))
                }
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = value
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setPadding(0, 8, 0, 0)
                }
            )
        }

    private fun field(
        hintValue: String,
        password: Boolean = false,
        numeric: Boolean = false
    ) =
        EditText(this).apply {
            hint = hintValue
            setHintTextColor(Color.rgb(145, 145, 160))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(23, 23, 31))

            inputType = when {
                password ->
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD

                numeric ->
                    InputType.TYPE_CLASS_NUMBER

                else ->
                    InputType.TYPE_CLASS_TEXT
            }
        }

    private fun primaryButton(
        label: String,
        action: () -> Unit
    ) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(124, 77, 255))
            setOnClickListener { action() }
        }

    private fun secondaryButton(
        label: String,
        action: () -> Unit
    ) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.rgb(220, 210, 255))
            setBackgroundColor(Color.rgb(35, 31, 47))
            setOnClickListener { action() }
        }

    private fun space(height: Int) =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, height)
        }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
