package com.mrassaf.firewall

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var searchBox: EditText
    private lateinit var scheduleCheck: CheckBox
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var countText: TextView
    private lateinit var cloudStatusText: TextView
    private val prefs by lazy { getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE) }
    private var pendingStart = false
    private var currentScreen = "home"
    private var arabic = true
    private var startHour = 22
    private var startMinute = 0
    private var endHour = 7
    private var endMinute = 0
    private val appRows = mutableListOf<Pair<String, View>>()
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private val telemetryPrefs by lazy { getSharedPreferences("assaf_telemetry", MODE_PRIVATE) }
    private val telemetryTask = object : Runnable {
        override fun run() {
            if (telemetryPrefs.getBoolean("enabled", false)) {
                Thread {
                    runCatching {
                        val installId = telemetryPrefs.getString("install_id", null)
                            ?: UUID.randomUUID().toString().replace("-", "").also {
                                telemetryPrefs.edit().putString("install_id", it).apply()
                            }
                        val version = runCatching {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                        }.getOrDefault("unknown")
                        AssafApiClient.pingTelemetry(installId, version)
                    }
                }.start()
                telemetryHandler.postDelayed(this, 2 * 60 * 1000L)
            }
        }
    }

    private val navy = Color.rgb(9, 17, 32)
    private val panel = Color.rgb(18, 31, 52)
    private val panelLight = Color.rgb(25, 42, 67)
    private val blue = Color.rgb(57, 157, 255)
    private val white = Color.rgb(242, 247, 255)
    private val muted = Color.rgb(151, 170, 197)
    private val green = Color.rgb(67, 211, 155)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arabic = prefs.getBoolean(KEY_ARABIC, true)
        startHour = prefs.getInt(KEY_START_HOUR, 22)
        startMinute = prefs.getInt(KEY_START_MINUTE, 0)
        endHour = prefs.getInt(KEY_END_HOUR, 7)
        endMinute = prefs.getInt(KEY_END_MINUTE, 0)
        buildUi()
        if (currentScreen == "apps") loadApps()
        if (!telemetryPrefs.getBoolean("choice_made", false)) showTelemetryConsent()
    }

    private fun showTelemetryConsent() {
        AlertDialog.Builder(this)
            .setTitle(tr("Help improve ASSAF", "ساعدنا على تحسين ASSAF"))
            .setMessage(tr(
                "Allow anonymous usage statistics? ASSAF will send a random installation ID, app version, and last-active time so the owner can count active installations. It will not send your app list, blocked apps, messages, or browsing activity. You can decline and still use the app.",
                "هل تسمح بإرسال إحصائيات استخدام مجهولة؟ سيرسل ASSAF معرّف تثبيت عشوائيًا وإصدار التطبيق وآخر وقت نشاط حتى يتمكن المالك من حساب الأجهزة النشطة. لن يرسل قائمة تطبيقاتك أو التطبيقات المحظورة أو رسائلك أو نشاط التصفح. يمكنك الرفض والاستمرار باستخدام التطبيق."
            ))
            .setPositiveButton(tr("Allow", "سماح")) { _, _ ->
                telemetryPrefs.edit().putBoolean("choice_made", true).putBoolean("enabled", true).apply()
                telemetryHandler.removeCallbacks(telemetryTask)
                telemetryHandler.post(telemetryTask)
            }
            .setNegativeButton(tr("Not now", "ليس الآن")) { _, _ ->
                telemetryPrefs.edit().putBoolean("choice_made", true).putBoolean("enabled", false).apply()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        telemetryHandler.removeCallbacks(telemetryTask)
        if (telemetryPrefs.getBoolean("enabled", false)) telemetryHandler.post(telemetryTask)
    }

    override fun onPause() {
        telemetryHandler.removeCallbacks(telemetryTask)
        super.onPause()
    }

    @Deprecated("Use platform callback; kept compatible without AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK && pendingStart) startFirewall()
            else Toast.makeText(this, tr("VPN permission is required", "يلزم السماح باتصال VPN"), Toast.LENGTH_LONG).show()
            pendingStart = false
        }
    }

    private fun buildUi() {
        when (currentScreen) {
            "apps" -> { buildAppsScreen(); loadApps() }
            "stats", "privacy" -> buildStatsScreen()
            "connection" -> buildConnectionScreen()
            "schedule" -> buildScheduleScreen()
            "settings" -> buildSettingsScreen()
            "shield" -> buildShieldScreen()
            "link" -> buildLinkScreen()
            "wifi" -> buildWifiScreen()
            "profile" -> buildProfileScreen()
            "plus" -> buildPlusScreen()
            else -> buildHomeScreen()
        }
    }

    private fun goTo(screen: String) { currentScreen = screen; buildUi() }

    private fun pageRoot(title: String): LinearLayout {
        val scroll = ScrollView(this).apply { setFillViewport(true); setBackgroundColor(navy); isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundResource(R.drawable.assaf_background)
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)
        root.addView(smallButton().apply {
            text = tr("← Back to home", "← الرجوع للرئيسية")
            setOnClickListener { goTo("home") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(TextView(this).apply {
            text = title; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(white); gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
        }, wrap())
        setContentView(scroll)
        return root
    }

    private fun infoCard(value: String): TextView = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(white)
        setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(panel, 16)
    }

    private fun buildStatsScreen() {
        val root = pageRoot(if (currentScreen == "privacy") tr("Privacy report", "تقرير الخصوصية") else tr("Network statistics", "إحصائيات الشبكة"))
        statsText = infoCard("")
        root.addView(statsText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        root.addView(primaryButton(if (currentScreen == "privacy") tr("Refresh report", "تحديث التقرير") else tr("Refresh statistics", "تحديث الإحصائيات")).apply { setOnClickListener { updateStats() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        updateStats()
    }

    private fun buildConnectionScreen() {
        val root = pageRoot(tr("Data connection", "اتصال البيانات"))
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val connected = caps != null
        val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val status = tr(
            "Connection: " + (if (connected) "Connected" else "No active connection") + "\nWi-Fi: " + (if (wifi) "Connected" else "Not active") + "\nMobile data: " + (if (cellular) "Active" else "Not active"),
            "الاتصال: " + (if (connected) "متصل" else "لا يوجد اتصال نشط") + "\nالواي فاي: " + (if (wifi) "متصل" else "غير نشط") + "\nبيانات الهاتف: " + (if (cellular) "نشطة" else "غير نشطة")
        )
        root.addView(infoCard(status), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        root.addView(primaryButton(tr("Open internet settings", "فتح إعدادات الإنترنت")).apply {
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(TextView(this).apply {
            text = tr("Android does not let ordinary apps switch Wi-Fi/mobile data directly. Use system settings to change them.",
                "أندرويد لا يسمح للتطبيقات العادية بتشغيل أو إيقاف الواي فاي/بيانات الهاتف مباشرة. استخدم إعدادات النظام للتحكم.")
            textSize = 13f; setTextColor(muted); setPadding(0, dp(16), 0, 0)
        }, wrap())
    }

    private fun buildScheduleScreen() {
        val root = pageRoot(tr("Protection schedule", "جدولة الحماية"))
        scheduleCheck = CheckBox(this).apply {
            text = tr("Enable scheduled blocking", "تفعيل الحجب حسب الجدول")
            setTextColor(white); isChecked = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
            buttonTintList = ColorStateList.valueOf(blue)
        }
        root.addView(scheduleCheck, wrap())
        startTimeButton = primaryButton("").apply { setOnClickListener { chooseTime(true) } }
        endTimeButton = primaryButton("").apply { setOnClickListener { chooseTime(false) } }
        root.addView(startTimeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })
        root.addView(endTimeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })
        updateTimeButtons()
        root.addView(primaryButton(tr("Save schedule", "حفظ الجدولة")).apply { setOnClickListener { saveSchedule() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        root.addView(TextView(this).apply {
            text = tr("Selected apps are blocked between the start and end times.", "سيتم حجب التطبيقات المحددة بين وقت البداية والنهاية.")
            textSize = 13f; setTextColor(muted); setPadding(0, dp(16), 0, 0)
        }, wrap())
    }

    private fun buildSettingsScreen() {
        val root = pageRoot(tr("Settings", "الإعدادات"))
        root.addView(CheckBox(this).apply {
            text = tr("Use Arabic interface", "استخدام الواجهة العربية")
            setTextColor(white); isChecked = arabic; buttonTintList = ColorStateList.valueOf(blue)
            setOnCheckedChangeListener { _, checked ->
                arabic = checked; prefs.edit().putBoolean(KEY_ARABIC, arabic).apply(); goTo("settings")
            }
        }, wrap())
        root.addView(primaryButton(tr("ASSAF cloud account", "حساب ASSAF السحابي")).apply { setOnClickListener { showAccountDialog() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        root.addView(smallButton().apply {
            text = tr("Protection schedule", "جدولة الحماية")
            setOnClickListener { goTo("schedule") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        root.addView(smallButton().apply {
            text = tr("Connection details", "تفاصيل الاتصال")
            setOnClickListener { goTo("connection") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        val active = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        root.addView(infoCard(tr("Firewall status: " + if (active) "Active" else "Off", "حالة جدار الحماية: " + if (active) "يعمل" else "متوقف")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    private fun buildShieldScreen() {
        val root = pageRoot(tr("ASSAF Shield", "درع عساف"))
        root.addView(infoCard(tr(
            "Protection status and filtering engine",
            "حالة الحماية ومحرك التصفية"
        )), wrap())
        root.addView(infoCard(tr(
            "App internet blocking is available in the Applications screen. Ad and malicious-domain blocking needs a separate filtering engine and is not active yet.",
            "يمكن التحكم بإنترنت التطبيقات من صفحة التطبيقات. حجب الإعلانات والنطاقات الضارة يحتاج محرك تصفية مستقل، وهو غير مفعّل حالياً."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(primaryButton(tr("Open applications", "فتح التطبيقات")).apply {
            setOnClickListener { goTo("apps") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
    }

    private fun buildLinkScreen() {
        val root = pageRoot(tr("Link Check", "فحص الروابط"))
        val intro = infoCard(tr(
            "ASSAF checks URL patterns, domain/DNS signals, and known-threat reputation when configured. A high score is not a guarantee; if threat reputation is unavailable, the result remains preliminary.",
            "يفحص ASSAF أنماط الرابط ومؤشرات النطاق وDNS وسمعة التهديدات المعروفة عند تفعيل المصدر. الدرجة العالية ليست ضماناً؛ وإذا تعذّر فحص السمعة تبقى النتيجة مبدئية."
        ))
        root.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val input = EditText(this).apply {
            hint = tr("Paste a public website URL", "الصق رابط موقع عام")
            setTextColor(white); setHintTextColor(muted); textSize = 16f
            setSingleLine(true); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 14)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val check = primaryButton(tr("Analyze link", "افحص الرابط"))
        root.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        val report = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(report, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        check.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, tr("Enter a link first", "أدخل الرابط أولاً"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            check.isEnabled = false
            check.text = tr("Checking…", "جاري الفحص…")
            report.removeAllViews()
            report.addView(infoCard(tr("Checking the link indicators…", "جاري فحص مؤشرات الرابط…")))
            Thread {
                val response = runCatching { AssafApiClient.analyzeLink(value) }
                runOnUiThread {
                    check.isEnabled = true
                    check.text = tr("Analyze link", "افحص الرابط")
                    report.removeAllViews()
                    if (response.isFailure) {
                        report.addView(infoCard(tr(
                            "The check could not be completed. No safety conclusion can be made. Details: ${response.exceptionOrNull()?.message ?: "Unknown error"}",
                            "تعذّر إكمال الفحص، لذلك لا يمكن إصدار حكم على أمان الرابط. التفاصيل: ${response.exceptionOrNull()?.message ?: "خطأ غير معروف"}"
                        )))
                    } else {
                        val data = response.getOrThrow()
                        val domain = data.optJSONObject("domain") ?: org.json.JSONObject()
                        val registration = data.optJSONObject("registration") ?: org.json.JSONObject()
                        val security = data.optJSONObject("security_review") ?: org.json.JSONObject()
                        val flags = security.optJSONArray("flags") ?: org.json.JSONArray()
                        val urls = data.optJSONArray("query_parameters") ?: org.json.JSONArray()
                        val dns = domain.optJSONArray("public_dns_addresses") ?: org.json.JSONArray()
                        val nameservers = registration.optJSONArray("nameservers") ?: org.json.JSONArray()

                        fun arrValues(array: org.json.JSONArray): List<String> =
                            (0 until array.length()).mapNotNull { i ->
                                array.optString(i).takeIf { it.isNotBlank() && it != "null" }
                            }
                        val flagObjects = (0 until flags.length()).mapNotNull { flags.optJSONObject(it) }
                        val flagCodes = flagObjects.map { it.optString("code") }.toSet()
                        val threatIntel = security.optJSONObject("threat_intelligence") ?: org.json.JSONObject()
                        val threatStatus = threatIntel.optString("status", "not_checked")
                        val knownThreat = threatStatus == "malicious"
                        val score = security.optInt("score", 70).coerceIn(0, 100)
                        val hasWarning = knownThreat || flagObjects.any { it.optString("level") == "warning" || it.optString("level") == "review" }
                        val scoreColor = when {
                            knownThreat || score < 50 -> Color.rgb(239, 68, 68)
                            hasWarning || score < 80 -> Color.rgb(213, 168, 62)
                            else -> green
                        }
                        val verdict = when {
                            knownThreat -> tr("Known threat reported", "تم الإبلاغ عن تهديد معروف")
                            score < 50 -> tr("High risk indicators — avoid this link", "مؤشرات خطورة مرتفعة — تجنّب الرابط")
                            hasWarning || score < 80 -> tr("Caution — review the indicators", "حذر — راجع المؤشرات")
                            threatStatus != "clean" -> tr("Preliminary only — threat reputation not confirmed", "نتيجة مبدئية — لم يتم تأكيد سمعة الرابط")
                            else -> tr("No known threat found by the connected source — not a guarantee", "لم يرصد المصدر المتصل تهديداً معروفاً — وهذا ليس ضماناً")
                        }
                        val scoreCard = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(18), dp(18), dp(18), dp(18))
                            background = rounded(panel, 18)
                        }
                        scoreCard.addView(TextView(this).apply {
                            text = tr("SECURITY SCORE", "درجة الأمان")
                            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); gravity = Gravity.CENTER
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = "$score / 100"
                            textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(scoreColor); gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, dp(4))
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = verdict
                            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(scoreColor); gravity = Gravity.CENTER
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = when (threatStatus) {
                                "malicious" -> tr("A connected threat-reputation source reported this URL. Treat it as dangerous; avoid opening it.", "مصدر سمعة التهديدات المتصل أبلغ عن هذا الرابط. تعامل معه كخطر وتجنّب فتحه.")
                                "clean" -> tr("A known-threat lookup returned no match. The page content, TLS, and redirects are not fully verified; this is not a safety guarantee.", "لم يعثر فحص التهديدات المعروف على تطابق. لم يتم التحقق الكامل من محتوى الصفحة أو TLS أو التحويلات؛ وهذه ليست ضمانة أمان.")
                                else -> tr("Threat reputation could not be confirmed. This is an indicator score, not proof of safety; do not enter sensitive information on uncertain links.", "تعذّر تأكيد سمعة التهديدات. هذه درجة للمؤشرات وليست إثباتاً للأمان؛ لا تدخل بيانات حساسة في رابط غير موثوق.")
                            }
                            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER
                            setPadding(0, dp(10), 0, 0)
                        })
                        report.addView(scoreCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

                        if (hasWarning) {
                            val warningCard = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(14), dp(14), dp(14))
                                background = rounded(Color.rgb(105, 28, 35), 16)
                            }
                            warningCard.addView(TextView(this).apply {
                                text = tr("SECURITY WARNING", "تحذير أمني")
                                textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                            })
                            warningCard.addView(TextView(this).apply {
                                text = if (knownThreat) tr("A connected threat-reputation source matched this URL to a known threat. Do not open it or enter any information.", "طابق مصدر سمعة التهديدات المتصل هذا الرابط مع تهديد معروف. لا تفتحه ولا تدخل أي معلومات.")
                                    else tr("Suspicious indicators were found, but these indicators alone do not prove the site is malicious. Avoid passwords, payment details, and downloads until independently verified.", "تم العثور على مؤشرات مريبة، لكنها وحدها لا تثبت أن الموقع خبيث. تجنّب كلمات المرور وبيانات الدفع والتنزيلات حتى يتم التحقق منه بشكل مستقل.")
                                textSize = 14f; setTextColor(Color.WHITE); setPadding(0, dp(7), 0, 0)
                            })
                            report.addView(warningCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                        }

                        fun addSection(title: String, lines: List<String>) {
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(12), dp(14), dp(12))
                                background = rounded(panel, 16)
                            }
                            card.addView(TextView(this).apply {
                                text = title; setTextColor(blue); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                            })
                            lines.filter { it.isNotBlank() }.forEach { line ->
                                card.addView(TextView(this).apply {
                                    text = line; setTextColor(white); textSize = 13f
                                    setPadding(0, dp(7), 0, dp(2))
                                })
                            }
                            report.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                        }

                        addSection(tr("01 • Link identity", "01 • هوية الرابط"), listOf(
                            "${tr("Submitted", "الرابط المدخل")}: ${data.optString("submitted_url")}",
                            "${tr("Normalized", "الرابط بعد الترتيب")}: ${data.optString("normalized_url")}",
                            "${tr("Protocol", "البروتوكول")}: ${data.optString("scheme").uppercase(Locale.ROOT)}",
                            "${tr("Host", "اسم المضيف")}: ${data.optString("hostname")}",
                            "${tr("Port", "المنفذ")}: ${data.optInt("port")}",
                            "${tr("Path", "المسار")}: ${data.optString("path")}",
                            "${tr("Fragment", "علامة الصفحة")}: ${data.optBoolean("fragment_present")}"
                        ))
                        addSection(tr("02 • Domain & DNS", "02 • النطاق وDNS"), listOf(
                            "${tr("Domain estimate", "النطاق التقريبي")}: ${domain.optString("registrable_domain_estimate")}",
                            "${tr("Address status", "حالة العنوان")}: ${domain.optString("dns_status")}",
                            "${tr("Public IP addresses", "عناوين IP العامة")}: ${arrValues(dns).joinToString().ifBlank { tr("No public address returned", "لم يُرجع عنوان عام") }}",
                            domain.optString("note")
                        ))
                        val regEvents = registration.optJSONArray("events") ?: org.json.JSONArray()
                        val eventLines = (0 until regEvents.length()).map { i ->
                            val e = regEvents.optJSONObject(i) ?: org.json.JSONObject()
                            "${e.optString("event")}: ${e.optString("date")}"
                        }
                        addSection(tr("03 • Domain registration records", "03 • سجلات تسجيل النطاق"), listOf(
                            "${tr("Lookup status", "حالة الاستعلام")}: ${registration.optString("status")}",
                            "${tr("Registered domain", "النطاق المسجل")}: ${registration.optString("domain_name")}",
                            "${tr("Registry handle", "معرّف السجل")}: ${registration.optString("handle")}",
                            "${tr("Name servers", "خوادم الأسماء")}: ${arrValues(nameservers).joinToString().ifBlank { tr("Not available", "غير متاح") }}",
                            *eventLines.toTypedArray(),
                            registration.optString("disclaimer"),
                            registration.optString("reason")
                        ))
                        val flagLines = flagObjects.map { f ->
                            "• [${f.optString("level")}] ${f.optString("code")}: ${f.optString("detail")}"
                        }
                        addSection(tr("04 • Security indicators", "04 • مؤشرات الأمان"), listOf(
                            "${tr("Assessment", "التقييم")}: ${security.optString("status")}",
                            "${tr("Confidence", "مستوى الثقة")}: ${security.optString("confidence")}",
                            *flagLines.toTypedArray(),
                            security.optString("important")
                        ))
                        addSection(tr("05 • URL parameters", "05 • معاملات الرابط"), if (urls.length() == 0)
                            listOf(tr("No query parameters found.", "لا توجد معاملات إضافية في الرابط."))
                        else (0 until urls.length()).map { i ->
                            val p = urls.optJSONObject(i) ?: org.json.JSONObject()
                            "${p.optString("name")} = ${p.optString("value")}"
                        })
                        addSection(tr("06 • Data sources & limitations", "06 • مصادر البيانات والحدود"), listOf(
                            "${tr("Generated at", "وقت إنشاء التقرير")}: ${data.optString("generated_at")}",
                            "${tr("Sources", "المصادر")}: ${arrValues(data.optJSONArray("data_sources") ?: org.json.JSONArray()).joinToString()}",
                            tr("No basic warning is not the same as safe. Live phishing/malware reputation, page content, TLS certificate, and redirect checks are not available yet.", "عدم ظهور تحذير أساسي لا يعني أن الرابط آمن. فحص سمعة التصيّد والبرمجيات الخبيثة المباشر ومحتوى الصفحة وشهادة TLS والتحويلات غير متاح بعد.")
                        ))
                    }
                }
            }.start()
        }
    }

    private fun buildWifiScreen() {
        val root = pageRoot(tr("Wi-Fi Check", "فحص الواي فاي"))
        val connected = try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) { false }
        root.addView(infoCard(tr(
            if (connected) "Wi-Fi connection detected." else "No active Wi-Fi connection detected.",
            if (connected) "تم اكتشاف اتصال واي فاي." else "لم يتم اكتشاف اتصال واي فاي نشط."
        )), wrap())
        root.addView(infoCard(tr(
            "ASSAF cannot guarantee a network is safe from connection status alone. Check the router password and use trusted networks.",
            "لا يستطيع ASSAF ضمان أمان الشبكة من حالة الاتصال وحدها. تأكد من كلمة مرور الراوتر واستخدم الشبكات الموثوقة."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun buildProfileScreen() {
        val root = pageRoot(tr("Personal profile", "الملف الشخصي"))
        root.addView(infoCard(tr("ASSAF User", "مستخدم ASSAF")), wrap())
        root.addView(infoCard(tr(
            "Manage your language and protection settings on this device.",
            "أدر إعدادات اللغة والحماية على هذا الجهاز."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(primaryButton(tr("Settings", "الإعدادات")).apply {
            setOnClickListener { goTo("settings") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
    }

    private fun buildPlusScreen() {
        val root = pageRoot(tr("ASSAF Plus", "عساف بلس"))
        root.addView(infoCard(tr(
            "Premium features and subscriptions are not active yet.",
            "ميزات الاشتراك والدفع غير مفعّلة حتى الآن."
        )), wrap())
        root.addView(infoCard(tr(
            "No payment will be taken from this screen. Subscription management will be added after the service is implemented.",
            "لن يتم تحصيل أي دفعة من هذه الشاشة. ستتم إضافة إدارة الاشتراك بعد تنفيذ الخدمة."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    // Native ASSAF home screen based on the new cyber-security concept; no screenshot background.
    private fun buildHomeScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_assaf_shield)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ASSAF"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brand.addView(TextView(this).apply {
            text = "ASSAF"; textSize = 27f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(white)
        }, wrap())
        brand.addView(TextView(this).apply {
            text = tr("Your Internet. Your Control.", "إنترنتك، تحكّمك.")
            textSize = 12f; setTextColor(muted)
        }, wrap())
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton().apply {
            text = "⚙"
            textSize = 22f
            contentDescription = tr("Settings", "الإعدادات")
            setOnClickListener { goTo("settings") }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))

        val welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 38, 76), Color.rgb(12, 24, 47), Color.rgb(8, 16, 31))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(24, 116, 202))
            }
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        welcome.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_assaf_shield)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(78), dp(78)))
        welcome.addView(TextView(this).apply {
            text = tr("MR. ASSAF", "MR. ASSAF")
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap().height).apply { topMargin = dp(8) })
        welcome.addView(TextView(this).apply {
            text = tr("Welcome to ASSAF", "مرحباً بك في تطبيق عساف")
            textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        val languageRow = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        languageRow.addView(smallButton().apply {
            text = if (arabic) "عربي  |  English" else "English  |  عربي"
            setOnClickListener {
                arabic = !arabic
                prefs.edit().putBoolean(KEY_ARABIC, arabic).apply()
                buildUi()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { topMargin = dp(14) })
        welcome.addView(languageRow)
        root.addView(welcome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        val active = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 15)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val statusDot = TextView(this).apply {
            text = if (active) "●" else "○"
            textSize = 22f; setTextColor(if (active) green else muted)
        }
        statusCard.addView(statusDot, LinearLayout.LayoutParams(dp(32), dp(32)))
        statusCard.addView(TextView(this).apply {
            text = if (active) tr("Protection is active", "الحماية مفعّلة") else tr("Protection is off", "الحماية غير مفعّلة")
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(smallButton().apply {
            text = tr("Apps", "التطبيقات")
            setOnClickListener { goTo("apps") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        root.addView(TextView(this).apply {
            text = tr("ASSAF TOOLS", "أدوات عساف")
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white)
            gravity = if (arabic) Gravity.RIGHT else Gravity.LEFT
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22); bottomMargin = dp(10)
        })

        fun toolTile(iconRes: Int, english: String, arabicLabel: String, screen: String): LinearLayout {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(24, 45, 72), Color.rgb(17, 33, 56))
                ).apply {
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.rgb(42, 78, 117))
                }
                contentDescription = tr(english, arabicLabel)
                setOnClickListener { goTo(screen) }
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            tile.addView(ImageView(this).apply {
                setImageResource(iconRes)
                contentDescription = tr(english, arabicLabel)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            tile.addView(TextView(this).apply {
                text = tr(english, arabicLabel)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(white)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
            return tile
        }
        val rows = listOf(
            listOf(toolTile(R.drawable.ic_assaf_shield, "ASSAF Shield", "درع عساف", "shield"), toolTile(R.drawable.ic_assaf_link, "Link Check", "فحص الروابط", "link")),
            listOf(toolTile(R.drawable.ic_assaf_privacy, "Privacy Report", "تقرير الخصوصية", "privacy"), toolTile(R.drawable.ic_assaf_wifi, "Wi-Fi Check", "فحص الواي فاي", "wifi")),
            listOf(toolTile(R.drawable.ic_assaf_apps, "Applications", "التطبيقات", "apps"), toolTile(R.drawable.ic_assaf_settings, "Settings", "الإعدادات", "settings"))
        )
        rows.forEach { row ->
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEachIndexed { index, tile ->
                rowLayout.addView(tile, LinearLayout.LayoutParams(0, dp(132), 1f).apply {
                    if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                })
            }
            root.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        }
        root.addView(TextView(this).apply {
            text = tr("Your Internet. Your Control.", "إنترنتك، تحكّمك.")
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }, wrap())
        setContentView(scroll)
    }

    // Separate app screen: only the six requested apps appear here.
    private fun buildAppsScreen() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundResource(R.drawable.assaf_main_interface)
            isVerticalScrollBarEnabled = false
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundResource(R.drawable.assaf_background)
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(smallButton().apply {
            text = tr("← Back", "رجوع ←")
            setOnClickListener {
                currentScreen = "home"
                buildUi()
            }
        }, LinearLayout.LayoutParams(dp(100), dp(44)))
        top.addView(TextView(this).apply {
            text = tr("YOUR APPLICATIONS", "التطبيقات")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, wrap())

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(white)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, 14)
        }
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.topMargin = dp(14)
        root.addView(statusText, statusParams)

        countText = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(countText, wrap())

        searchBox = EditText(this).apply {
            hint = tr("Search installed apps", "ابحث في تطبيقات الهاتف")
            setSingleLine(true)
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            background = rounded(panel, 13)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(SimpleTextWatcher { filterApps(it) })
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchParams.topMargin = dp(10)
        root.addView(searchBox, searchParams)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(listContainer, wrap())

        root.addView(primaryButton(tr("Apply & start protection", "تطبيق وبدء الحماية")).apply {
            setOnClickListener { requestVpnAndStart() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(smallButton().apply {
            text = tr("Turn firewall off", "إيقاف جدار الحماية")
            setOnClickListener {
                startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                statusText.text = tr("Firewall stop requested", "تم طلب إيقاف الحجب")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        setContentView(scroll)
    }

    private fun showAccountDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val emailInput = EditText(this).apply {
            hint = tr("Email address", "البريد الإلكتروني")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = tr("Password (10+ characters for new accounts)", "كلمة المرور (10 أحرف على الأقل للحساب الجديد)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val registerCheck = CheckBox(this).apply {
            text = tr("Create a new account", "إنشاء حساب جديد")
            setTextColor(white)
        }
        form.addView(emailInput, wrap())
        form.addView(passwordInput, wrap())
        form.addView(registerCheck, wrap())
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(tr("ASSAF Cloud Account", "حساب ASSAF السحابي"))
            .setView(form)
            .setNegativeButton(tr("Cancel", "إلغاء"), null)
            .setPositiveButton(tr("Continue", "متابعة"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailInput.error = tr("Enter a valid email", "أدخل بريدًا إلكترونيًا صحيحًا")
                    return@setOnClickListener
                }
                if (password.length < if (registerCheck.isChecked) 10 else 1) {
                    passwordInput.error = tr("Password is too short", "كلمة المرور قصيرة")
                    return@setOnClickListener
                }
                cloudStatusText.text = tr("Connecting securely…", "جارٍ الاتصال بشكل آمن…")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = runCatching {
                        AssafApiClient.authenticate(email, password, registerCheck.isChecked)
                    }
                    runOnUiThread {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        result.onSuccess { token ->
                            prefs.edit().putString(KEY_API_TOKEN, token).putString(KEY_ACCOUNT_EMAIL, email).apply()
                            cloudStatusText.text = tr("Signed in • Cloud sync ready", "تم تسجيل الدخول • المزامنة السحابية جاهزة")
                            Toast.makeText(this, tr("ASSAF account connected", "تم ربط حساب ASSAF"), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                            syncCloudSettingChanges()
                        }.onFailure {
                            cloudStatusText.text = tr("Sign-in failed", "فشل تسجيل الدخول")
                            android.app.AlertDialog.Builder(this)
                                .setTitle(tr("Could not connect", "تعذر الاتصال"))
                                .setMessage(it.message ?: tr("Please try again.", "حاول مرة أخرى."))
                                .setPositiveButton(tr("OK", "حسنًا"), null).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun syncCloudSettingChanges() {
        val token = prefs.getString(KEY_API_TOKEN, null) ?: return
        val localSelected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()
        Thread {
            val result = runCatching {
                val remote = AssafApiClient.listSettings(token)
                val remoteSettings = mutableMapOf<String, Boolean>()
                for (i in 0 until remote.length()) {
                    val item = remote.optJSONObject(i) ?: continue
                    val packageName = item.optString("package_name")
                    if (packageName.isNotBlank()) remoteSettings[packageName] = item.optBoolean("blocked", false)
                }

                val installedLaunchable = packageManager.getInstalledApplications(0)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
                    .map { it.packageName }
                    .toSet()
                val merged = localSelected.filter { it in installedLaunchable }.toMutableSet()

                // Cloud values restore settings on this device; only apply packages installed here.
                remoteSettings.forEach { (pkg, blocked) ->
                    if (pkg in installedLaunchable) {
                        if (blocked) merged.add(pkg) else merged.remove(pkg)
                    }
                }

                // Upload local selections that have never been saved to the account.
                merged.filter { it in installedLaunchable && it !in remoteSettings }
                    .forEach { AssafApiClient.saveSetting(token, it, true) }

                val savedCount = AssafApiClient.listSettings(token).length()
                Pair(merged, savedCount)
            }
            runOnUiThread {
                result.onSuccess { (merged, count) ->
                    prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, merged).apply()
                    loadApps()
                    if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) {
                        if (merged.isEmpty()) {
                            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                        } else {
                            val serviceIntent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
                        }
                    }
                    cloudStatusText.text = tr("Cloud connected • $count settings synced", "السحابة متصلة • تمت مزامنة $count إعدادات")
                }.onFailure {
                    cloudStatusText.text = tr("Signed in • Sync needs retry", "تم تسجيل الدخول • تحتاج المزامنة لإعادة المحاولة")
                }
            }
        }.start()
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        appRows.clear()
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()

        // Discover every installed app that has a launcher activity. The approved
        // home artwork is untouched; only the contents of the Apps screen are dynamic.
        val installedApps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                Triple(info, label, info.packageName)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

        var tileRow: LinearLayout? = null
        installedApps.forEachIndexed { index, item ->
            val info = item.first
            val label = item.second
            val pkg = item.third

            if (index % 3 == 0) {
                tileRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                listContainer.addView(tileRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(9) })
            }

            val isBlocked = selected.contains(pkg)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(10))
                background = rounded(if (isBlocked) Color.rgb(55, 34, 49) else panel, 16)
                isClickable = true
                isFocusable = true
                contentDescription = label
            }

            val iconView = ImageView(this).apply {
                val drawable = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
                if (drawable != null) setImageDrawable(drawable)
                else setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(blue)
                })
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(iconView, LinearLayout.LayoutParams(dp(52), dp(52)))
            tile.addView(TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(white)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(7), 0, dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
            tile.addView(TextView(this).apply {
                text = if (isBlocked) tr("BLOCKED", "محجوب") else tr("ALLOWED", "مسموح")
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (isBlocked) Color.rgb(255, 153, 153) else green)
            }, wrap())

            tile.setOnClickListener { confirmAppConnection(pkg, label) }
            val tileParams = LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            tileRow?.addView(tile, tileParams)
            appRows.add(pkg to tile)
        }

        if (installedApps.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = tr("No launchable apps were found.", "لم يتم العثور على تطبيقات قابلة للفتح.")
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }, wrap())
        }

        countText.text = tr(
            "${installedApps.size} apps found • ${selected.size} selected",
            "التطبيقات الموجودة: ${installedApps.size} • المحددة للحجب: ${selected.size}"
        )
        val firewallActive = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        statusText.text = if (firewallActive) {
            tr("${selected.size} apps selected • Protection is active", "التطبيقات المحددة: ${selected.size} • الحماية تعمل")
        } else {
            tr("${selected.size} apps selected. Apply to start protection.", "التطبيقات المحددة: ${selected.size}. اضغط تطبيق لبدء الحماية.")
        }
    }

    private fun confirmAppConnection(packageName: String, appName: String) {
        val blocked = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().contains(packageName)
        val title = if (blocked) tr("Restore internet connection?", "معاودة الاتصال بالإنترنت؟")
                    else tr("Stop internet connection?", "إيقاف الاتصال بالإنترنت؟")
        val message = if (blocked) tr("Allow $appName to access the internet again?", "هل تريد السماح لتطبيق $appName بالاتصال بالإنترنت من جديد؟")
                      else tr("Block internet access for $appName?", "هل تريد إيقاف اتصال تطبيق $appName بالإنترنت؟")
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(tr("Cancel", "إلغاء"), null)
            .setPositiveButton(if (blocked) tr("Reconnect", "معاودة الاتصال") else tr("Block internet", "إيقاف الاتصال")) { _, _ ->
                val updated = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
                if (blocked) updated.remove(packageName) else updated.add(packageName)
                prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, updated).apply()
                prefs.getString(KEY_API_TOKEN, null)?.let { token ->
                    Thread { runCatching { AssafApiClient.saveSetting(token, packageName, !blocked) } }.start()
                }
                loadApps()
                if (updated.isEmpty()) {
                    startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                } else if (!blocked) {
                    // When the user blocks an app, start the VPN immediately if needed.
                    // Android's VPN consent is requested only the first time (or after revocation).
                    requestVpnAndStart()
                } else if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) {
                    // Keep the VPN rules in sync after an app is allowed again.
                    val serviceIntent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
                }
                val toastText = if (blocked) tr("Internet connection restored for $appName", "تمت معاودة اتصال $appName بالإنترنت")
                                 else tr("Internet blocking requested for $appName", "تم طلب فصل الإنترنت عن $appName")
                Toast.makeText(this, toastText, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        appRows.forEach { (pkg, row) ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            row.visibility = if (q.isEmpty() || label.lowercase(Locale.ROOT).contains(q) || pkg.lowercase(Locale.ROOT).contains(q)) View.VISIBLE else View.GONE
        }
    }

    private fun chooseTime(start: Boolean) {
        val h = if (start) startHour else endHour
        val m = if (start) startMinute else endMinute
        TimePickerDialog(this, { _, hour, minute ->
            if (start) { startHour = hour; startMinute = minute } else { endHour = hour; endMinute = minute }
            updateTimeButtons()
        }, h, m, true).show()
    }

    private fun updateTimeButtons() {
        if (!::startTimeButton.isInitialized || !::endTimeButton.isInitialized) return
        startTimeButton.text = tr("START", "البداية") + "  " + "%02d:%02d".format(startHour, startMinute)
        endTimeButton.text = tr("END", "النهاية") + "  " + "%02d:%02d".format(endHour, endMinute)
    }

    private fun saveSchedule() {
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, scheduleCheck.isChecked)
            .putInt(KEY_START_HOUR, startHour).putInt(KEY_START_MINUTE, startMinute)
            .putInt(KEY_END_HOUR, endHour).putInt(KEY_END_MINUTE, endMinute).apply()
        ScheduleReceiver.reschedule(this)
        Toast.makeText(this, tr("Schedule saved", "تم حفظ الجدولة"), Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnAndStart() {
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        if (selected.isEmpty()) {
            Toast.makeText(this, tr("Select at least one app first", "اختر تطبيقًا واحدًا على الأقل"), Toast.LENGTH_LONG).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) { pendingStart = true; @Suppress("DEPRECATION") startActivityForResult(intent, VPN_REQUEST) }
        else startFirewall()
    }

    private fun startFirewall() {
        val intent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusText.text = tr("Protection start requested • Check VPN status.", "تم طلب بدء الحماية • تحقق من حالة VPN.")
    }

    private fun updateStats() {
        if (!::statsText.isInitialized) return
        val blockedBytes = prefs.getLong(FirewallVpnService.KEY_BLOCKED_BYTES, 0L)
        val packets = prefs.getLong(FirewallVpnService.KEY_BLOCKED_PACKETS, 0L)
        val rx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val tx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        statsText.text = tr(
            """BLOCKED TRAFFIC
${formatBytes(blockedBytes)}  •  ${packets} packets

DEVICE NETWORK USAGE
↓ ${formatBytes(rx)} received     ↑ ${formatBytes(tx)} sent""",
            """الحركة المحجوبة
${formatBytes(blockedBytes)}  •  ${packets} حزمة

استخدام شبكة الجهاز
↓ ${formatBytes(rx)} استقبال     ↑ ${formatBytes(tx)} إرسال"""
        )
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            setTextColor(muted)
            setPadding(0, dp(20), 0, dp(9))
        }, wrap())
    }

    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 18)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(blue, 14)
        isAllCaps = false
    }

    private fun smallButton() = Button(this).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(white)
        background = rounded(panelLight, 12)
        isAllCaps = false
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun tr(en: String, ar: String) = if (arabic) ar else en
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VPN_REQUEST = 901
        private const val KEY_ARABIC = "ui_arabic"
        private const val KEY_API_TOKEN = "api_access_token"
        private const val KEY_ACCOUNT_EMAIL = "api_account_email"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_START_HOUR = "schedule_start_hour"
        private const val KEY_START_MINUTE = "schedule_start_minute"
        private const val KEY_END_HOUR = "schedule_end_hour"
        private const val KEY_END_MINUTE = "schedule_end_minute"
    }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
