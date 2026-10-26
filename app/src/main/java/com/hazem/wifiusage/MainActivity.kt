package com.hazem.wifiusage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val DAY_MS = 86_400_000.0

class MainActivity : Activity() {

    private lateinit var db: UsageDb

    // Usage / Networks tabs: 0 = today, 1 = 7 days, 2 = 30 days, 3 = plan cycle, 4 = custom
    private var mode = 3
    private var customFrom = 0L
    private var customTo = 0L
    private var selectedSsid: String? = null

    // tab: 0 home, 1 networks, 2 usage, 3 more.  sub: 0 none, 1 data limit, 2 notifications, 3 this month
    private var tab = 0
    private var sub = 0
    private var monthsBack = 6
    private var byRenewal = false
    private var selectedRenewal: Long? = null
    private var renewCustom = false

    private var askedPerms = false
    private var appsToken = 0
    private var dialogShown = false
    private var shellBuilt = false
    private var dark = true
    private var appRows: List<AppStats.AppRow>? = null

    // Draft values while the "data limit" screen is open
    private var draftOn = false
    private var draftLimit = 100f
    private var draftDay = 1

    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private val navIcons = mutableListOf<IconView>()
    private val navLabels = mutableListOf<TextView>()

    // Live updates while the app is on screen
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            Thread {
                try { WifiUsage.sampleNow(applicationContext) } catch (e: Exception) { }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tick++
                    if (shellBuilt && sub == 0) {
                        if (tab == 2) { if (tick % 6 == 0) reloadApps() } else render(true)
                    }
                }
            }.start()
            handler.postDelayed(this, 5000)
        }
    }

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = isDark()
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        Palette.apply(dark)
        db = UsageDb.get(this)

        // Remember the first time the app was opened (older data gives the best answer).
        if (Prefs.firstOpen(this) == 0L) {
            val earliest = db.firstSampleTime()
            Prefs.setFirstOpen(this, if (earliest > 0L) earliest else System.currentTimeMillis())
        }

        savedInstanceState?.let {
            mode = it.getInt("mode", 3)
            selectedSsid = it.getString("ssid")
            customFrom = it.getLong("cf", 0L)
            customTo = it.getLong("ct", 0L)
            tab = it.getInt("tab", 0)
        }

        if (Prefs.of(this).getBoolean("disclosed", false)) buildShell() else showOnboarding()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode)
        outState.putString("ssid", selectedSsid)
        outState.putLong("cf", customFrom)
        outState.putLong("ct", customTo)
        outState.putInt("tab", tab)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sub != 0 -> { sub = 0; render(false) }
            shellBuilt && tab != 0 -> { tab = 0; render(false) }
            else -> super.onBackPressed()
        }
    }

    private fun isDark(): Boolean = when (Prefs.themeMode(this)) {
        1 -> false
        0 -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        else -> true
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.of(this).getBoolean("disclosed", false)) return
        proceed()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /** Usage access → location + notifications → start tracking. */
    private fun proceed() {
        if (!shellBuilt) buildShell()
        if (!hasUsageAccess()) {
            Toast.makeText(this, "فعّل Usage access للتطبيق", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) { }
            return
        }
        if (!askedPerms) {
            askedPerms = true
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 1)
                return
            }
        }
        start()
    }

    private fun start() {
        val work = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wifi_sampler", ExistingPeriodicWorkPolicy.KEEP, work)
        NetworkWatcher.register(applicationContext)
        applyKeepAlive()
        render(true)
        maybeAskBattery()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun applyKeepAlive() {
        val svc = Intent(this, TrackerService::class.java)
        if (Prefs.keepAlive(this)) {
            try { startForegroundService(svc) } catch (e: Exception) { }
        } else {
            stopService(svc)
        }
    }

    /** Ask once to be exempt from battery optimisation, so Android doesn't stop the tracking. */
    private fun maybeAskBattery() {
        if (dialogShown) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = Prefs.of(this)
        if (prefs.getBoolean("batt_asked", false)) return
        prefs.edit().putBoolean("batt_asked", true).apply()
        dialogShown = true
        AlertDialog.Builder(this)
            .setTitle("عشان التتبع ما يتقطعش")
            .setMessage("هنفتحلك قايمة تحسين البطارية. دوّر على WiFi Usage واختار «عدم التحسين» (Don't optimize) عشان أندرويد ما يوقفش التتبع.")
            .setPositiveButton("فتح القايمة") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    /** The refresh button: take a sample now, and ask for location if it's missing. */
    private fun manualRefresh() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
            return
        }
        Toast.makeText(this, "جاري التحديث...", Toast.LENGTH_SHORT).show()
        Thread {
            try { WifiUsage.sampleNow(applicationContext) } catch (e: Exception) { }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(true)
                Toast.makeText(this, "تم التحديث", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Why the network name can't be read, or null if everything needed is on. */
    private fun ssidProblem(): String? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "اسمح للتطبيق بالوصول للموقع عشان اسم الشبكة يظهر (اضغط هنا)"
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            return "شغّل الموقع (GPS) عشان اسم الشبكة يظهر (اضغط هنا)"
        }
        return null
    }

    private fun fixSsid() {
        when {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
            ssidProblem() != null -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> manualRefresh()
        }
    }

    // ───────────────────────── Small UI helpers ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) }

    private fun tv(s: CharSequence, sp: Float, color: Int = Palette.text, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun roundBg(color: Int, radiusDp: Int) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundBg(Palette.card, 20)
    }

    private fun add(v: View, bottom: Int = 12) = body.addView(v, matchWrap(bottom))

    private fun primaryButton(label: String, onClick: () -> Unit) = tv(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(14), 0, dp(14))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.primary, Palette.primaryEnd)
        ).apply { cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun softButton(label: String, color: Int = Palette.primary, onClick: () -> Unit) =
        tv(label, 13f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundBg(Palette.cardAlt, 12)
            setOnClickListener { onClick() }
        }

    private fun logoView(sizeDp: Int) = ImageView(this).apply {
        setImageResource(R.drawable.logo)
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.22f)
            }
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Title row. With [back] it shows an arrow that returns to the tabs. */
    private fun header(title: String, back: Boolean, action: View? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (back) {
            row.addView(tv("→", 24f, Palette.text, true).apply {
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { sub = 0; render(false) }
            })
        } else if (title == "WiFi Usage") {
            row.addView(logoView(36).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10) })
        }
        row.addView(tv(title, 22f, Palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (action != null) row.addView(action)
        add(row, 14)
    }

    private fun refreshPill() = tv("⟳", 20f, Palette.primary, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = roundBg(Palette.card, 14)
        setOnClickListener { manualRefresh() }
    }

    private fun colorFor(frac: Float) = when {
        frac >= 1f -> Palette.RED
        frac >= 0.8f -> Palette.ORANGE
        else -> Palette.primary
    }

    /** A selector showing the chosen network. */
    private fun networkSelector(after: () -> Unit = { render(true) }): View {
        val c = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { chooseNetwork(after) }
        }
        c.addView(IconView(this, IconView.WIFI).apply { color = Palette.primary }, LinearLayout.LayoutParams(dp(24), dp(24)))
        c.addView(
            tv(selectedSsid ?: "كل الشبكات", 16f, Palette.text, true),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) }
        )
        c.addView(tv("▾", 16f, Palette.muted))
        return c
    }

    private fun statusLine(): View {
        val live = WifiUsage.currentSsid(this)
        val (msg, color) = when {
            live != null -> "● متصل الآن بـ $live" to Palette.GREEN
            WifiUsage.isOnWifi(this) ->
                "⚠ " + (ssidProblem() ?: "متصل بواي فاي بس مش قادر أقرأ اسمه (اضغط للتحديث)") to Palette.ORANGE
            else -> "غير متصل بشبكة واي فاي" to Palette.muted
        }
        return tv(msg, 13f, color).apply { setOnClickListener { fixSsid() } }
    }

    private fun chipsRow(onChange: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = listOf("اليوم", "7 أيام", "30 يوم", "الباقة", "مخصص")
        labels.forEachIndexed { i, label ->
            val on = i == mode
            val chip = tv(label, 13f, if (on) Color.WHITE else Palette.text, on).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = roundBg(if (on) Palette.primary else Palette.card, 14)
                setOnClickListener {
                    if (i == 4) pickCustomRange(onChange) else { mode = i; onChange() }
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = if (i < labels.size - 1) dp(6) else 0
            })
        }
        return row
    }

    private fun tile(label: String, value: String): View {
        val c = card().apply { setPadding(dp(10), dp(14), dp(10), dp(14)); gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(tv(label, 11f, Palette.muted).apply { gravity = Gravity.CENTER })
        c.addView(tv(value, 17f, Palette.text, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        return c
    }

    private fun menuRow(glyph: String, title: String, desc: String?, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundBg(Palette.card, 18)
            setOnClickListener { onClick() }
        }
        row.addView(
            tv(glyph, 18f, Palette.primary, true).apply {
                gravity = Gravity.CENTER
                background = roundBg(Palette.cardAlt, 12)
            },
            LinearLayout.LayoutParams(dp(42), dp(42))
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, Palette.text, true))
        if (desc != null) col.addView(tv(desc, 12f, Palette.muted))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        row.addView(tv("‹", 22f, Palette.muted))
        return row
    }

    private fun switchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundBg(Palette.card, 18)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, Palette.text, true))
        if (desc != null) col.addView(tv(desc, 12f, Palette.muted))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return row
    }

    private fun sectionTitle(s: String) = tv(s, 15f, Palette.text, true).apply { setPadding(0, dp(4), 0, dp(10)) }

    /** A labelled row with a size and a thin bar, used by the lists. */
    private fun usageRow(label: String, bytes: Long, max: Long, color: Int = Palette.primary, icon: View? = null): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        if (icon != null) outer.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(tv(label, 14f, Palette.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(tv(Fmt.text(bytes), 13f, Palette.muted))
        col.addView(top)
        col.addView(
            ProgressBarView(this).apply { fraction = bytes.toFloat() / max.coerceAtLeast(1L); this.color = color },
            LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(6) }
        )
        outer.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return outer
    }

    // ───────────────────────── Onboarding ─────────────────────────

    private fun showOnboarding() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        col.addView(logoView(112).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20) })
        col.addView(tv("WiFi Usage", 28f, Palette.text, true).apply { gravity = Gravity.CENTER })
        col.addView(
            tv("اعرف استهلاكك من الواي فاي على كل شبكة", 15f, Palette.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(24))
            },
            matchWrap()
        )

        fun point(glyph: String, title: String, desc: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = roundBg(Palette.card, 18)
            }
            row.addView(
                tv(glyph, 18f, Palette.primary, true).apply {
                    gravity = Gravity.CENTER; background = roundBg(Palette.cardAlt, 12)
                },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(tv(title, 15f, Palette.text, true))
            c.addView(tv(desc, 12f, Palette.muted))
            row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            col.addView(row, matchWrap(10))
        }
        point("▮", "إحصائيات الاستخدام", "عشان نقرأ حجم استهلاك الواي فاي من النظام.")
        point("◎", "الموقع", "أندرويد بيعتبر اسم الشبكة بيانات موقع. التطبيق مش بيتتبع مكانك ومش بيحفظ GPS.")
        point("♪", "الإشعارات", "عشان ننبهك لما الباقة تقرب تخلص.")

        col.addView(
            tv("كل البيانات بتتخزن على موبايلك بس، والتطبيق معندوش إذن إنترنت أصلاً.", 12f, Palette.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20))
            },
            matchWrap()
        )
        col.addView(primaryButton("موافق وابدأ") {
            Prefs.of(this).edit().putBoolean("disclosed", true).apply()
            buildShell()
            proceed()
        }, matchWrap(10))
        col.addView(softButton("خروج", Palette.muted) { finish() }, matchWrap())
        col.addView(
            tv("Developed by Hazem Abdallah", 12f, Palette.muted, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
            },
            matchWrap()
        )

        val sv = ScrollView(this).apply {
            setBackgroundColor(Palette.bg)
            isFillViewport = true
            addView(col)
        }
        sv.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(sv)
        applyBars()
    }

    @Suppress("DEPRECATION")
    private fun applyBars() {
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.card
        window.decorView.systemUiVisibility =
            if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
    }

    // ───────────────────────── Shell: content + bottom navigation ─────────────────────────

    @Suppress("DEPRECATION")
    private fun buildShell() {
        if (shellBuilt) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Palette.bg)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.card)
            setPadding(0, dp(8), 0, dp(8))
        }
        val items = listOf(
            IconView.HOME to "الرئيسية", IconView.WIFI to "الشبكات",
            IconView.BARS to "الاستهلاك", IconView.MORE to "المزيد"
        )
        items.forEachIndexed { i, (kind, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { sub = 0; tab = i; if (i == 2) reloadApps(); render(false) }
            }
            val icon = IconView(this, kind)
            val lab = tv(label, 11f, Palette.muted)
            navIcons.add(icon); navLabels.add(lab)
            item.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
            item.addView(lab)
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))

        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        applyBars()
        shellBuilt = true
        render(false)
    }

    private fun render(keepScroll: Boolean) {
        if (!shellBuilt) return
        val y = scroll.scrollY
        body.removeAllViews()
        try {
            when (sub) {
                1 -> buildLimit()
                2 -> buildNotifications()
                3 -> buildMonth()
                else -> when (tab) {
                    0 -> buildHome()
                    1 -> buildNetworks()
                    2 -> buildUsage()
                    else -> buildMore()
                }
            }
        } catch (e: Exception) {
            body.removeAllViews()
            add(tv("حصل خطأ في عرض الصفحة، جرّب تقفل التطبيق وتفتحه تاني.", 14f, Palette.muted))
        }
        navIcons.forEachIndexed { i, icon ->
            val on = sub == 0 && i == tab
            icon.color = if (on) Palette.primary else Palette.muted
            navLabels[i].setTextColor(if (on) Palette.primary else Palette.muted)
        }
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.post { scroll.scrollTo(0, 0) }
    }

    // ───────────────────────── Home ─────────────────────────

    private fun buildHome() {
        val now = System.currentTimeMillis()
        val ssid = selectedSsid
        header("WiFi Usage", false, refreshPill())
        add(statusLine(), 10)
        add(networkSelector(), 12)

        val start = Cycles.start(this, ssid, now)
        val end = Cycles.end(this, ssid, now)
        val usedBytes = db.total(ssid, start, now)
        val usedGb = usedBytes / Fmt.GB
        val limit = Prefs.limitGb(this, ssid)
        val frac = if (limit > 0f) (usedGb / limit).toFloat() else 0f
        val daysLeft = Math.ceil((end - now) / DAY_MS).toInt().coerceAtLeast(1)
        val elapsedDays = ((now - start) / DAY_MS).coerceAtLeast(1.0)

        // Gauge
        val gaugeCard = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(22), dp(16), dp(18)) }
        val gaugeBox = FrameLayout(this)
        gaugeBox.addView(GaugeView(this).apply { fraction = frac; arcColor = colorFor(frac) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val (num, unit) = Fmt.split(usedBytes)
        center.addView(
            tv(SpannableString("$num $unit").apply {
                setSpan(RelativeSizeSpan(0.4f), num.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 38f, Palette.text, true).apply { gravity = Gravity.CENTER }
        )
        center.addView(
            tv(
                if (limit > 0f) "من %.0f GB".format(Locale.US, limit.toDouble()) else "استهلاك هذه الباقة",
                13f, Palette.muted
            ).apply { gravity = Gravity.CENTER }
        )
        gaugeBox.addView(center, FrameLayout.LayoutParams(MATCH, MATCH))
        gaugeCard.addView(gaugeBox, LinearLayout.LayoutParams(dp(210), dp(210)))

        val line = when {
            limit <= 0f -> "اضغط هنا وحدّد باقتك عشان تظهر النسبة"
            usedGb >= limit -> "تجاوزت الباقة بـ %.1f GB".format(Locale.US, usedGb - limit)
            else -> "باقي %.1f GB · %d%% مستخدم".format(Locale.US, limit - usedGb, (frac * 100).toInt())
        }
        gaugeCard.addView(
            tv(line, 13f, if (limit > 0f && usedGb >= limit) Palette.RED else Palette.muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        )
        gaugeCard.setOnClickListener { openSub(1) }
        add(gaugeCard, 12)
        add(softButton("تجديد الباقة (ريسيت)") { addRenewalDialog(ssid) { render(true) } }, 12)

        // Tiles
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val today = db.total(ssid, Billing.startOfDay(0), now)
        tiles.addView(tile("اليوم", Fmt.text(today)), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        tiles.addView(
            tile("المتوسط اليومي", Fmt.text((usedBytes / elapsedDays).toLong())),
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) }
        )
        tiles.addView(tile("باقي للتجديد", "$daysLeft يوم"), LinearLayout.LayoutParams(0, WRAP, 1f))
        add(tiles, 12)

        // Last 7 days
        val week = card()
        week.addView(tv("آخر 7 أيام", 14f, Palette.text, true))
        val weekDays = listOf("ح", "ن", "ث", "ر", "خ", "ج", "س")
        val vals = mutableListOf<Long>()
        val labs = mutableListOf<String>()
        for (i in 6 downTo 0) {
            val s = Billing.startOfDay(i)
            val e = if (i == 0) now else Billing.startOfDay(i - 1) - 1
            vals.add(db.total(ssid, s, e))
            val dow = Calendar.getInstance().apply { timeInMillis = s }[Calendar.DAY_OF_WEEK]
            labs.add(weekDays[dow - 1])
        }
        week.addView(
            BarChartView(this).apply { values = vals; labels = labs },
            LinearLayout.LayoutParams(MATCH, dp(150)).apply { topMargin = dp(12) }
        )
        add(week, 12)

        add(primaryButton("إرسال على واتساب") { sendToWhatsApp(ssid, start, now) }.also {
            it.setOnLongClickListener { changeWhatsAppNumber(ssid); true }
        }, 4)
        add(tv("اضغط مطوّلاً على الزرار لتغيير الرقم المحفوظ لهذه الشبكة", 11f, Palette.muted), 16)

        add(tv("بدأ التتبع من " + SimpleDateFormat("d/M/yyyy HH:mm", Locale.US).format(Prefs.firstOpen(this)), 11f, Palette.muted).apply { gravity = Gravity.CENTER }, 6)
        add(tv("Developed by Hazem Abdallah", 12f, Palette.muted, true).apply { gravity = Gravity.CENTER }, 0)
    }

    // ───────────────────────── Networks ─────────────────────────

    private fun buildNetworks() {
        header("الشبكات", false, refreshPill())
        add(softButton("الشبكات المتاحة والاتصال بشبكة") { openWifiPanel() }, 12)
        add(filterBlock { render(false) }, 12)
        val (from, to) = range()
        val rows = db.ssidTotals(from, to)
        val live = WifiUsage.currentSsid(this)
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)
        add(tv("من ${f.format(from)} إلى ${f.format(to)}", 12f, Palette.muted), 10)

        if (rows.isEmpty()) {
            val c = card()
            c.addView(tv("لا توجد بيانات بعد", 14f, Palette.text, true))
            c.addView(tv("اتصل بشبكة واي فاي واستنى دقيقة، وهتظهر هنا.", 12f, Palette.muted))
            add(c, 12)
        }
        val max = rows.maxOfOrNull { it.second } ?: 1L
        val now = System.currentTimeMillis()
        for ((name, bytes) in rows) {
            val c = card()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(
                tv(name + if (name == live) "  ● متصل" else "", 15f, if (name == live) Palette.primary else Palette.text, true),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            top.addView(tv(Fmt.text(bytes), 15f, Palette.text, true))
            c.addView(top)
            c.addView(
                ProgressBarView(this).apply { fraction = bytes.toFloat() / max.coerceAtLeast(1L) },
                LinearLayout.LayoutParams(MATCH, dp(6)).apply { topMargin = dp(10) }
            )
            val limit = Prefs.limitGb(this, name)
            if (limit > 0f) {
                val cs = Cycles.start(this, name, now)
                val used = db.total(name, cs, now) / Fmt.GB
                c.addView(
                    tv("الباقة: %.1f / %.0f GB".format(Locale.US, used, limit.toDouble()), 12f, Palette.muted)
                        .apply { setPadding(0, dp(8), 0, 0) }
                )
            }
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(softButton("عرض") { selectedSsid = name; tab = 0; render(false) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            btns.addView(softButton("واتساب", 0xFF25D366.toInt()) { sendToWhatsApp(name, from, to) }.also {
                it.setOnLongClickListener { changeWhatsAppNumber(name); true }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            c.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            add(c, 10)
        }

        val note = card()
        note.addView(tv("ملاحظة", 13f, Palette.text, true))
        note.addView(
            tv(
                "الموبايل مش بيقدر يشوف الأجهزة التانية المتصلة بالراوتر ولا استهلاكها، دا بيتشاف من إعدادات الراوتر نفسه. التطبيق بيعرض استهلاك موبايلك إنت بس على كل شبكة.",
                12f, Palette.muted
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        add(note, 0)
    }

    // ───────────────────────── Usage details ─────────────────────────

    private fun buildUsage() {
        val ssid = selectedSsid
        header("تفاصيل الاستهلاك", false, refreshPill())
        add(networkSelector { reloadApps(); render(false) }, 10)
        add(filterBlock { reloadApps(); render(false) }, 12)

        val (from, to) = range()
        val total = db.total(ssid, from, to)
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)

        val totalCard = card()
        totalCard.addView(tv("إجمالي الاستهلاك", 13f, Palette.muted))
        val (num, unit) = Fmt.split(total)
        totalCard.addView(
            tv(SpannableString("$num $unit").apply {
                setSpan(RelativeSizeSpan(0.45f), num.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 36f, Palette.text, true).apply { setPadding(0, dp(4), 0, dp(2)) }
        )
        totalCard.addView(tv("من ${f.format(from)} إلى ${f.format(to)}", 11f, Palette.muted))
        add(totalCard, 12)

        // Cumulative line
        val days = dayBuckets(from, to).takeLast(31)
        val cum = mutableListOf<Float>()
        var run = 0f
        for ((s, e) in days) { run += (db.total(ssid, s, e) / Fmt.GB).toFloat(); cum.add(run) }
        val limit = Prefs.limitGb(this, ssid)
        var yMax = (run * 1.2f).coerceAtLeast(1f)
        if ((mode == 3 || byRenewal) && limit > 0f) yMax = maxOf(yMax, limit)
        yMax = niceMax(yMax)
        val lineCard = card()
        lineCard.addView(tv("الاستهلاك التراكمي (GB)", 14f, Palette.text, true))
        lineCard.addView(
            LineChartView(this).apply { values = cum; totalDays = maxOf(days.size, 2); this.yMax = yMax },
            LinearLayout.LayoutParams(MATCH, dp(190)).apply { topMargin = dp(10) }
        )
        add(lineCard, 12)

        // Months
        val monthsCard = card()
        val mh = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mh.addView(tv("الشهور اللي فاتت", 14f, Palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        for (n in listOf(3, 6)) {
            mh.addView(
                tv("$n شهور", 12f, if (monthsBack == n) Color.WHITE else Palette.muted, monthsBack == n).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = roundBg(if (monthsBack == n) Palette.primary else Palette.cardAlt, 10)
                    setOnClickListener { monthsBack = n; render(true) }
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) }
            )
        }
        monthsCard.addView(mh)
        val mv = mutableListOf<Long>()
        val ml = mutableListOf<String>()
        for (i in monthsBack - 1 downTo 0) {
            val s = Calendar.getInstance().apply {
                add(Calendar.MONTH, -i); set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val e = (s.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis - 1
            mv.add(db.total(ssid, s.timeInMillis, e))
            ml.add((s[Calendar.MONTH] + 1).toString())
        }
        monthsCard.addView(
            BarChartView(this).apply { values = mv; labels = ml },
            LinearLayout.LayoutParams(MATCH, dp(150)).apply { topMargin = dp(10) }
        )
        add(monthsCard, 12)

        // Categories + apps
        val rows = appRows
        val catCard = card()
        catCard.addView(tv("حسب نوع التطبيق", 14f, Palette.text, true))
        catCard.addView(tv("على كل شبكات الواي فاي في الفترة المختارة", 11f, Palette.muted))
        if (rows == null) {
            catCard.addView(tv("جاري التحميل...", 12f, Palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        } else if (rows.isEmpty()) {
            catCard.addView(tv("لا توجد بيانات", 12f, Palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            val cats = AppStats.categories(rows)
            val cmax = cats.first().second
            for ((cat, b) in cats) {
                val badge = tv(cat.glyph, 13f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; background = roundBg(cat.color, 18)
                }
                catCard.addView(usageRow(cat.name, b, cmax, cat.color, badge))
            }
        }
        add(catCard, 12)

        val appsCard = card()
        appsCard.addView(tv("أكتر التطبيقات استهلاكاً", 14f, Palette.text, true))
        if (rows == null) {
            appsCard.addView(tv("جاري التحميل...", 12f, Palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        } else if (rows.isEmpty()) {
            appsCard.addView(tv("لا توجد بيانات", 12f, Palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        } else {
            val max = rows.first().bytes
            for (r in rows.take(8)) appsCard.addView(usageRow(r.label, r.bytes, max, Palette.primary, appIcon(r)))
        }
        add(appsCard, 12)

        add(softButton("تصدير CSV") { exportCsv() }, 0)
    }

    /** Opens Android's own Wi-Fi list (nearby networks + connect). Apps can't connect to saved networks themselves. */
    private fun openWifiPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 29) startActivity(Intent(Settings.Panel.ACTION_WIFI))
            else startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    private fun niceMax(v: Float): Float {
        val steps = floatArrayOf(1f, 2f, 4f, 5f, 8f, 10f, 20f, 40f, 50f, 80f, 100f, 200f, 400f, 500f, 800f, 1000f, 2000f)
        for (s in steps) if (v <= s) return s
        return Math.ceil((v / 100f).toDouble()).toFloat() * 100f
    }

    private fun appIcon(r: AppStats.AppRow): View {
        val pkg = r.pkg
        if (pkg != null) {
            try {
                return ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            } catch (e: Exception) { }
        }
        return tv(r.label.take(1), 14f, Palette.primary, true).apply {
            gravity = Gravity.CENTER; background = roundBg(Palette.cardAlt, 18)
        }
    }

    private fun reloadApps() {
        val (from, to) = range()
        val token = ++appsToken
        Thread {
            val rows = try { AppStats.perApp(applicationContext, from, to) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != appsToken) return@runOnUiThread
                appRows = rows
                if (shellBuilt && sub == 0 && tab == 2) render(true)
            }
        }.start()
    }

    // ───────────────────────── More ─────────────────────────

    private fun buildMore() {
        header("المزيد", false)
        add(menuRow("◔", "باقة البيانات", "الحد الأقصى ويوم التجديد") { openSub(1) }, 10)
        add(menuRow("♪", "الإشعارات", "تنبيهات 80% وتجاوز الباقة") { sub = 2; render(false) }, 10)
        add(menuRow("▤", "هذا الشهر", "ملخص الباقة الحالية ومقارنتها") { sub = 3; render(false) }, 10)
        add(menuRow("◐", "المظهر", themeName()) { showThemeDialog() }, 10)
        add(menuRow("⇪", "تصدير CSV", "ابعت بيانات الفترة المختارة") { exportCsv() }, 10)
        add(menuRow("⚙", "أذونات التطبيق", "الموقع والإشعارات والبطارية") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, 16)

        val info = card()
        info.addView(tv("عن التطبيق", 14f, Palette.text, true))
        info.addView(
            tv(
                "بدأ التتبع من " + SimpleDateFormat("d/M/yyyy HH:mm", Locale.US).format(Prefs.firstOpen(this)) +
                    "\nالبيانات بتتخزن على موبايلك بس، ومفيش إذن إنترنت.",
                12f, Palette.muted
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        add(info, 16)
        add(tv("Developed by Hazem Abdallah", 12f, Palette.muted, true).apply { gravity = Gravity.CENTER }, 0)
    }

    private fun themeName() = when (Prefs.themeMode(this)) {
        0 -> "تلقائي (حسب الموبايل)"
        1 -> "فاتح"
        else -> "داكن"
    }

    // ───────────────────────── Data limit ─────────────────────────

    private fun openSub(n: Int) {
        if (n == 1) initDraft()
        sub = n
        render(false)
    }

    private fun initDraft() {
        val l = Prefs.limitGb(this, selectedSsid)
        draftOn = l > 0f
        draftLimit = if (l > 0f) l else 100f
        draftDay = Prefs.renewalDay(this, selectedSsid)
    }

    private fun buildLimit() {
        header("باقة البيانات", true)
        add(networkSelector { initDraft(); render(false) }, 12)

        add(switchRow("تفعيل حد الباقة", "نتابع استهلاكك ونبهك قبل ما تخلص", draftOn) {
            draftOn = it; render(true)
        }, 12)

        val c = card()
        c.addView(tv("الحد الأقصى", 13f, Palette.muted))
        c.addView(
            tv("%.0f GB".format(Locale.US, draftLimit.toDouble()), 34f, if (draftOn) Palette.text else Palette.muted, true).apply {
                setPadding(0, dp(4), 0, dp(8))
                setOnClickListener {
                    if (draftOn) inputDialog("الحد الأقصى (GB)", draftLimit.toString().removeSuffix(".0"), true) {
                        draftLimit = it.toFloatOrNull()?.coerceIn(1f, 100000f) ?: draftLimit
                        render(true)
                    }
                }
            }
        )
        val valueView = c.getChildAt(c.childCount - 1) as TextView
        c.addView(SeekBar(this).apply {
            max = 200
            progress = (draftLimit / 5f).toInt().coerceIn(0, 200)
            isEnabled = draftOn
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    draftLimit = (p * 5f).coerceAtLeast(5f)
                    valueView.text = "%.0f GB".format(Locale.US, draftLimit.toDouble())
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        c.addView(tv("اسحب للتعديل السريع، أو اضغط على الرقم لتكتبه بالظبط.", 11f, Palette.muted).apply { setPadding(0, dp(8), 0, 0) })
        add(c, 12)

        add(switchRow("نبهني عند 80%", "إشعار لما توصل 80% من الباقة", Prefs.notify80(this)) {
            Prefs.of(this).edit().putBoolean("notify_80", it).apply()
        }, 16)

        // Renewal log: tap to reset usage for a new plan period. Old usage is never deleted.
        val log = card()
        log.addView(tv("تجديد الباقة (ريسيت)", 14f, Palette.text, true))
        log.addView(
            tv("دوس عليه لما تجدد باقتك: الاستهلاك بيبدأ من الصفر، وبيتسجل التاريخ والساعة تلقائي. الاستهلاك القديم والسجل بيفضلوا محفوظين.", 11f, Palette.muted)
                .apply { setPadding(0, dp(4), 0, dp(10)) }
        )
        log.addView(primaryButton("تجديد الباقة") { addRenewalDialog(selectedSsid) { render(true) } })
        val rl = db.renewals(Prefs.key(selectedSsid)).reversed()
        if (rl.isNotEmpty()) {
            log.addView(tv("سجل التجديدات", 12f, Palette.muted).apply { setPadding(0, dp(12), 0, dp(4)) })
            val df = SimpleDateFormat("d/M/yyyy HH:mm", Locale.US)
            val nowMs = System.currentTimeMillis()
            rl.forEachIndexed { i, r ->
                val end = if (i == 0) nowMs else rl[i - 1] - 1
                val rowV = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                rowV.addView(tv(df.format(r), 13f, Palette.text), LinearLayout.LayoutParams(0, WRAP, 1f))
                rowV.addView(tv(Fmt.text(db.total(selectedSsid, r, end)), 13f, Palette.muted))
                log.addView(rowV)
            }
        }
        add(log, 16)

        add(primaryButton("حفظ") {
            Prefs.setPlan(this, selectedSsid, if (draftOn) draftLimit else 0f, draftDay)
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
            sub = 0
            render(false)
        }, 0)
    }

    // ───────────────────────── Notifications ─────────────────────────

    private fun buildNotifications() {
        header("الإشعارات", true)
        add(switchRow("تنبيه 80% من الباقة", "إشعار لما تقرب تخلص الباقة", Prefs.notify80(this)) {
            Prefs.of(this).edit().putBoolean("notify_80", it).apply()
        }, 10)
        add(switchRow("تنبيه تجاوز الباقة", "إشعار لما تخلص الباقة", Prefs.notify100(this)) {
            Prefs.of(this).edit().putBoolean("notify_100", it).apply()
        }, 10)
        add(switchRow("التشغيل الدائم", "إشعار ثابت صغير عشان أندرويد ما يقفلش التتبع", Prefs.keepAlive(this)) {
            Prefs.setKeepAlive(this, it)
            applyKeepAlive()
        }, 12)
        add(
            tv(
                "الإشعارات بتتبعت بس لو سمحت بالإشعارات للتطبيق من إعدادات أندرويد. التنبيهات بتتحسب لكل شبكة حسب باقتها.",
                12f, Palette.muted
            ), 0
        )
    }

    // ───────────────────────── This month ─────────────────────────

    private fun buildMonth() {
        header("هذا الشهر", true)
        add(networkSelector { render(false) }, 12)

        val now = System.currentTimeMillis()
        val ssid = selectedSsid
        val start = Cycles.start(this, ssid, now)
        val logged = db.renewals(Prefs.key(ssid))
        val prevStart = if (logged.isNotEmpty()) (logged.lastOrNull { it < start } ?: start)
        else Billing.cycleStart(Prefs.renewalDay(this, ssid), start - 1)
        val total = db.total(ssid, start, now)
        val prev = if (prevStart < start) db.total(ssid, prevStart, start - 1) else 0L
        val elapsed = ((now - start) / DAY_MS).coerceAtLeast(1.0)

        val days = dayBuckets(start, now).takeLast(31)
        val per = days.map { db.total(ssid, it.first, it.second) }
        var peakIdx = -1
        var peak = 0L
        per.forEachIndexed { i, b -> if (b > peak) { peak = b; peakIdx = i } }

        val top = card()
        top.addView(tv("استهلاك الباقة الحالية", 13f, Palette.muted))
        val (num, unit) = Fmt.split(total)
        top.addView(
            tv(SpannableString("$num $unit").apply {
                setSpan(RelativeSizeSpan(0.45f), num.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }, 36f, Palette.text, true).apply { setPadding(0, dp(4), 0, dp(2)) }
        )
        val cmp = when {
            prev <= 0L -> "مفيش بيانات للباقة اللي فاتت للمقارنة"
            else -> {
                val pct = ((total - prev) * 100.0 / prev).toInt()
                (if (pct >= 0) "أعلى بـ $pct%" else "أقل بـ ${-pct}%") + " من الباقة اللي فاتت (${Fmt.text(prev)})"
            }
        }
        top.addView(tv(cmp, 12f, Palette.muted))
        add(top, 12)

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(tile("المتوسط اليومي", Fmt.text((total / elapsed).toLong())), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        tiles.addView(
            tile("أعلى يوم", if (peakIdx >= 0) Fmt.text(peak) else "—"),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        add(tiles, 12)

        val chartCard = card()
        chartCard.addView(tv("يوم بيوم", 14f, Palette.text, true))
        chartCard.addView(
            BarChartView(this).apply {
                values = per
                labels = days.map { Calendar.getInstance().apply { timeInMillis = it.first }[Calendar.DAY_OF_MONTH].toString() }
            },
            LinearLayout.LayoutParams(MATCH, dp(170)).apply { topMargin = dp(10) }
        )
        add(chartCard, 0)
    }

    // ───────────────────────── Data helpers ─────────────────────────

    private fun range(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (byRenewal) {
            val (cs, ce) = cycleBounds(selectedSsid)
            if (renewCustom && customFrom > 0L) {
                val a = customFrom.coerceIn(cs, ce)
                val b = (if (customTo > 0L) customTo else ce).coerceIn(a, ce)
                return a to b
            }
            return cs to ce
        }
        return when (mode) {
            0 -> Billing.startOfDay(0) to now
            1 -> Billing.startOfDay(6) to now
            2 -> Billing.startOfDay(29) to now
            3 -> Cycles.start(this, selectedSsid, now) to now
            else -> (if (customFrom > 0L) customFrom else Billing.startOfDay(29)) to
                (if (customTo > 0L) customTo else now)
        }
    }

    private fun dayBuckets(from: Long, to: Long): List<Pair<Long, Long>> {
        val days = mutableListOf<Pair<Long, Long>>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= to) {
            val s = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            days.add(s to minOf(cal.timeInMillis - 1, to))
        }
        return days
    }

    // ───────────────────────── Dialogs ─────────────────────────

    private fun chooseNetwork(after: () -> Unit) {
        val known = db.knownSsids()
        val current = WifiUsage.currentSsid(this)
        val all = (known + listOfNotNull(current)).distinct()
        val labels = listOf("كل الشبكات") + all.map { if (it == current) "$it  (متصل الآن)" else it }
        AlertDialog.Builder(this)
            .setTitle("اختر الشبكة")
            .setItems(labels.toTypedArray()) { _, i ->
                selectedSsid = if (i == 0) null else all[i - 1]
                after()
            }
            .show()
    }

    private fun inputDialog(title: String, initial: String, decimal: Boolean, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setText(initial)
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(MATCH, WRAP)) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("تمام") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showThemeDialog() {
        AlertDialog.Builder(this)
            .setTitle("المظهر")
            .setSingleChoiceItems(arrayOf("تلقائي (حسب الموبايل)", "فاتح", "داكن"), Prefs.themeMode(this)) { d, which ->
                Prefs.of(this).edit().putInt("theme", which).apply()
                d.dismiss()
                recreate()
            }
            .show()
    }

    private fun pickCustomRange(onChange: () -> Unit, bounds: Pair<Long, Long>? = null) {
        val c = Calendar.getInstance()
        if (bounds != null) c.timeInMillis = bounds.first
        DatePickerDialog(this, { _, y, m, d ->
            val start = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }.timeInMillis
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val end = Calendar.getInstance().apply { set(y2, m2, d2, 23, 59, 59) }.timeInMillis
                customFrom = start; customTo = end; mode = 4
                onChange()
            }, y, m, d).apply {
                setTitle("تاريخ النهاية")
                if (bounds != null) { datePicker.minDate = bounds.first; datePicker.maxDate = bounds.second }
            }.show()
        }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH]).apply {
            setTitle("تاريخ البداية")
            if (bounds != null) { datePicker.minDate = bounds.first; datePicker.maxDate = bounds.second }
        }.show()
    }

    /** (start, end) of the renewal period being viewed. Defaults to the latest one. */
    private fun cycleBounds(ssid: String?): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val rs = db.renewals(Prefs.key(ssid))
        if (rs.isEmpty()) return Cycles.start(this, ssid, now) to now
        val st = selectedRenewal?.takeIf { it in rs } ?: rs.last()
        val i = rs.indexOf(st)
        return st to (if (i + 1 < rs.size) rs[i + 1] - 1 else now)
    }

    /** Logs a renewal right now (date + time). Asks once so it isn't pressed by accident. */
    private fun addRenewalDialog(ssid: String?, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("تجديد الباقة")
            .setMessage("هيتسجل تاريخ وساعة التجديد دلوقتي، والعداد هيبدأ من الصفر.\nالاستهلاك القديم هيفضل محفوظ في السجل.")
            .setPositiveButton("جددت الباقة") { _, _ ->
                val ts = System.currentTimeMillis()
                db.addRenewal(Prefs.key(ssid), ts)
                selectedRenewal = ts
                renewCustom = false
                Toast.makeText(this, "تم تسجيل التجديد", Toast.LENGTH_SHORT).show()
                after()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pickRenewal(onChange: () -> Unit) {
        val ssid = selectedSsid
        val rs = db.renewals(Prefs.key(ssid)).reversed()
        val f = SimpleDateFormat("d/M/yyyy HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val labels = mutableListOf<String>()
        rs.forEachIndexed { i, r ->
            val end = if (i == 0) now else rs[i - 1] - 1
            labels.add("${f.format(r)}  ·  ${Fmt.text(db.total(ssid, r, end))}")
        }
        labels.add("＋ تسجيل تجديد جديد")
        AlertDialog.Builder(this)
            .setTitle("اختر فترة التجديد")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < rs.size) { selectedRenewal = rs[i]; renewCustom = false; onChange() }
                else addRenewalDialog(ssid) { onChange() }
            }
            .show()
    }

    /** Date filter vs renewal filter, plus the matching options. */
    private fun filterBlock(onChange: () -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(false to "حسب التاريخ", true to "حسب التجديد").forEachIndexed { i, (v, l) ->
            val on = byRenewal == v
            toggle.addView(
                tv(l, 13f, if (on) Color.WHITE else Palette.text, on).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    background = roundBg(if (on) Palette.primary else Palette.card, 14)
                    setOnClickListener { byRenewal = v; renewCustom = false; onChange() }
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) marginEnd = dp(8) }
            )
        }
        col.addView(toggle, matchWrap(10))

        if (!byRenewal) {
            col.addView(chipsRow(onChange))
            return col
        }

        val ssid = selectedSsid
        val rs = db.renewals(Prefs.key(ssid))
        val sel = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { if (rs.isEmpty()) addRenewalDialog(ssid) { onChange() } else pickRenewal(onChange) }
        }
        val f = SimpleDateFormat("d/M/yyyy", Locale.US)
        val txt = if (rs.isEmpty()) "مفيش تجديد متسجل — اضغط لتسجيل تجديد دلوقتي" else {
            val (cs, ce) = cycleBounds(ssid)
            "فترة التجديد: ${f.format(cs)}  ←  ${f.format(ce)}"
        }
        sel.addView(tv(txt, 14f, Palette.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        sel.addView(tv("▾", 16f, Palette.muted))
        col.addView(sel, matchWrap(10))

        if (rs.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(false to "كل الفترة", true to "تاريخ داخل الفترة").forEachIndexed { i, (custom, l) ->
                val on = renewCustom == custom
                row.addView(
                    tv(l, 13f, if (on) Color.WHITE else Palette.text, on).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(10), 0, dp(10))
                        background = roundBg(if (on) Palette.primary else Palette.card, 14)
                        setOnClickListener {
                            if (!custom) { renewCustom = false; onChange() }
                            else pickCustomRange({ renewCustom = true; onChange() }, cycleBounds(ssid))
                        }
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) marginEnd = dp(8) }
                )
            }
            col.addView(row)
        }
        return col
    }

    // ───────────────────────── Export ─────────────────────────

    private fun exportCsv() {
        val (from, to) = range()
        val rows = db.dailyRows(selectedSsid, from, to)
        if (rows.isEmpty()) {
            Toast.makeText(this, "مفيش بيانات للفترة دي", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("﻿date,network,usage_mb\n")
        for ((day, ssid, bytes) in rows) {
            sb.append(day).append(",\"").append(ssid.replace("\"", "\"\"")).append("\",")
                .append("%.2f".format(Locale.US, bytes / 1_048_576.0)).append('\n')
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "wifi_usage.csv")
        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "تصدير الاستهلاك"))
    }

    // ───────────────────────── WhatsApp ─────────────────────────

    private fun sendToWhatsApp(ssid: String?, from: Long, to: Long) {
        val saved = Prefs.whatsapp(this, ssid)
        if (saved == null) askNumber(ssid, "حفظ وإرسال") { sendTo(ssid, it, from, to) }
        else sendTo(ssid, saved, from, to)
    }

    private fun changeWhatsAppNumber(ssid: String?) {
        askNumber(ssid, "حفظ") { Toast.makeText(this, "تم حفظ الرقم", Toast.LENGTH_SHORT).show() }
    }

    private fun askNumber(ssid: String?, positive: String, then: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "مثال: 01012345678"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setText(Prefs.whatsapp(this@MainActivity, ssid) ?: "")
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(MATCH, WRAP)) }
        AlertDialog.Builder(this)
            .setTitle("رقم واتساب اللي هيستلم الاستهلاك")
            .setMessage("الشبكة: ${ssid ?: "كل الشبكات"}")
            .setView(box)
            .setPositiveButton(positive) { _, _ ->
                val n = normalizeNumber(input.text.toString())
                if (n.length < 8) {
                    Toast.makeText(this, "الرقم غير صحيح", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setWhatsapp(this, ssid, n)
                    then(n)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** Digits only, with the country code. A local Egyptian number (01xxxxxxxxx) becomes 201xxxxxxxxx. */
    private fun normalizeNumber(raw: String): String {
        var n = raw.filter { it.isDigit() }
        if (n.startsWith("00")) n = n.drop(2)
        if (n.startsWith("01") && n.length == 11) n = "2$n"
        return n
    }

    private fun sendTo(ssid: String?, number: String, from: Long, to: Long) {
        val usage = Fmt.text(db.total(ssid, from, to))
        val f = SimpleDateFormat("d/M HH:mm", Locale.US)
        val msg = "استهلاك ${ssid ?: "كل الشبكات"}\n" +
            "من ${f.format(from)} إلى ${f.format(to)}\n" +
            usage
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(msg))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "واتساب غير متاح على الجهاز", Toast.LENGTH_LONG).show()
        }
    }
}
