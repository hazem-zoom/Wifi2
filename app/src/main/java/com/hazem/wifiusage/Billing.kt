package com.hazem.wifiusage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar
import java.util.Locale

/** Small helper around SharedPreferences. Settings are stored per network ("__all__" = all networks). */
object Prefs {
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun key(ssid: String?): String = ssid ?: "__all__"

    fun limitGb(ctx: Context, ssid: String?): Float = of(ctx).getFloat("limit_" + key(ssid), 0f)
    fun renewalDay(ctx: Context, ssid: String?): Int = of(ctx).getInt("renew_" + key(ssid), 1)

    fun setPlan(ctx: Context, ssid: String?, limitGb: Float, day: Int) {
        of(ctx).edit()
            .putFloat("limit_" + key(ssid), limitGb)
            .putInt("renew_" + key(ssid), day)
            .apply()
    }

    fun whatsapp(ctx: Context, ssid: String?): String? = of(ctx).getString("wa_" + key(ssid), null)

    fun setWhatsapp(ctx: Context, ssid: String?, number: String) {
        of(ctx).edit().putString("wa_" + key(ssid), number).apply()
    }

    /** When the app was first opened (millis), 0 if not recorded yet. */
    fun firstOpen(ctx: Context): Long = of(ctx).getLong("first_open", 0L)

    fun setFirstOpen(ctx: Context, time: Long) {
        of(ctx).edit().putLong("first_open", time).apply()
    }

    /** Always-on tracking (foreground service + starts after reboot). On by default. */
    fun keepAlive(ctx: Context): Boolean = of(ctx).getBoolean("keep_alive", true)

    fun setKeepAlive(ctx: Context, on: Boolean) {
        of(ctx).edit().putBoolean("keep_alive", on).apply()
    }

    /** 0 = follow the phone, 1 = light, 2 = dark (default) */
    fun themeMode(ctx: Context): Int = of(ctx).getInt("theme", 2)

    fun notify80(ctx: Context): Boolean = of(ctx).getBoolean("notify_80", true)
    fun notify100(ctx: Context): Boolean = of(ctx).getBoolean("notify_100", true)
}

object Fmt {
    const val GB = 1_073_741_824.0

    fun split(b: Long): Pair<String, String> =
        if (b >= 1_073_741_824L) "%.2f".format(Locale.US, b / GB) to "GB"
        else "%.0f".format(Locale.US, b / 1_048_576.0) to "MB"

    fun text(b: Long): String {
        val (n, u) = split(b)
        return "$n $u"
    }
}

/** Billing-cycle maths: the cycle starts on the renewal day of each month. */
object Billing {

    fun startOfDay(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Start of the cycle that contains `now`. */
    fun cycleStart(renewalDay: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val thisMonth = dateIn(cal, renewalDay)
        if (thisMonth <= now) return thisMonth
        cal.add(Calendar.MONTH, -1)
        return dateIn(cal, renewalDay)
    }

    /** Start of the next cycle (= when the plan renews). */
    fun nextCycleStart(renewalDay: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = cycleStart(renewalDay, now) }
        cal.add(Calendar.MONTH, 1)
        return dateIn(cal, renewalDay)
    }

    /** Midnight of `day` inside the month of `base` (clamped for short months). */
    private fun dateIn(base: Calendar, day: Int): Long {
        val c = base.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        val max = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        c.set(Calendar.DAY_OF_MONTH, minOf(day, max))
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

/** The current plan cycle of a network: from its last logged renewal, or from the renewal day if none. */
object Cycles {
    fun start(ctx: Context, ssid: String?, now: Long = System.currentTimeMillis()): Long {
        val last = UsageDb.get(ctx).renewals(Prefs.key(ssid)).lastOrNull { it <= now }
        return last ?: Billing.cycleStart(Prefs.renewalDay(ctx, ssid), now)
    }

    fun end(ctx: Context, ssid: String?, now: Long = System.currentTimeMillis()): Long {
        val last = UsageDb.get(ctx).renewals(Prefs.key(ssid)).lastOrNull { it <= now }
            ?: return Billing.nextCycleStart(Prefs.renewalDay(ctx, ssid), now)
        return Calendar.getInstance().apply { timeInMillis = last; add(Calendar.MONTH, 1) }.timeInMillis
    }
}

/** Sends a notification when a network reaches 80% / 100% of its plan. */
object LimitChecker {
    private const val CHANNEL = "limits"

    fun check(context: Context) {
        val db = UsageDb.get(context)
        val prefs = Prefs.of(context)
        val now = System.currentTimeMillis()

        for (ssid in db.knownSsids()) {
            val limit = Prefs.limitGb(context, ssid)
            if (limit <= 0f) continue

            val start = Cycles.start(context, ssid, now)
            val usedGb = db.total(ssid, start, now) / Fmt.GB
            val pct = usedGb / limit
            val level = when {
                pct >= 1.0 -> 100
                pct >= 0.8 -> 80
                else -> 0
            }
            if (level == 0) continue
            if (level == 80 && !Prefs.notify80(context)) continue
            if (level == 100 && !Prefs.notify100(context)) continue

            val key = "notified_$ssid"
            val parts = (prefs.getString(key, "") ?: "").split(":")
            val prevStart = parts.getOrNull(0)?.toLongOrNull()
            val prevLevel = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (prevStart == start && prevLevel >= level) continue

            if (notify(context, ssid, level, usedGb, limit)) {
                prefs.edit().putString(key, "$start:$level").apply()
            }
        }
    }

    private fun notify(context: Context, ssid: String, level: Int, usedGb: Double, limit: Float): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "تنبيهات الباقة", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (level >= 100) "خلصت باقة $ssid" else "وصلت 80% من باقة $ssid"
        val body = "استهلكت %.1f من %.0f GB".format(Locale.US, usedGb, limit.toDouble())
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ssid.hashCode() + level, n)
        return true
    }
}
