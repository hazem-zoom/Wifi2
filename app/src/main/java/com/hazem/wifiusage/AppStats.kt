package com.hazem.wifiusage

import android.app.usage.NetworkStats
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Per-app and per-category Wi-Fi usage, built from the system's usage statistics. */
object AppStats {

    class AppRow(val label: String, val pkg: String?, val category: Int, val bytes: Long)

    class Cat(val name: String, val glyph: String, val color: Int)

    /** Wi-Fi usage per app (all Wi-Fi networks) for the period, biggest first. */
    fun perApp(context: Context, from: Long, to: Long): List<AppRow> {
        val pm = context.packageManager
        val byLabel = HashMap<String, AppRow>()
        for ((uid, b) in WifiUsage.appBytes(context, from, to)) {
            if (b <= 0L) continue
            val row = rowFor(pm, uid, b)
            val prev = byLabel[row.label]
            byLabel[row.label] =
                if (prev == null) row
                else AppRow(row.label, row.pkg ?: prev.pkg, row.category, prev.bytes + row.bytes)
        }
        return byLabel.values.sortedByDescending { it.bytes }
    }

    /** Usage grouped by the category each app declares (apps that declare none go to "other"). */
    fun categories(rows: List<AppRow>): List<Pair<Cat, Long>> {
        val totals = LinkedHashMap<String, Pair<Cat, Long>>()
        for (r in rows) {
            val cat = categoryOf(r.category)
            val prev = totals[cat.name]
            totals[cat.name] = cat to ((prev?.second ?: 0L) + r.bytes)
        }
        return totals.values.sortedByDescending { it.second }
    }

    fun categoryOf(category: Int): Cat = when (category) {
        ApplicationInfo.CATEGORY_VIDEO -> Cat("فيديو وبث", "▶", 0xFF7C3AED.toInt())
        ApplicationInfo.CATEGORY_AUDIO -> Cat("موسيقى وصوت", "♪", 0xFF2F6BFF.toInt())
        ApplicationInfo.CATEGORY_SOCIAL -> Cat("سوشيال ميديا", "★", 0xFF22C55E.toInt())
        ApplicationInfo.CATEGORY_GAME -> Cat("ألعاب", "◆", 0xFFF59E0B.toInt())
        else -> Cat("أخرى", "•••", 0xFF64748B.toInt())
    }

    private fun rowFor(pm: PackageManager, uid: Int, bytes: Long): AppRow {
        val none = ApplicationInfo.CATEGORY_UNDEFINED
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return AppRow("تطبيقات محذوفة", null, none, bytes)
            NetworkStats.Bucket.UID_TETHERING -> return AppRow("مشاركة الإنترنت (Hotspot)", null, none, bytes)
            0, 1000 -> return AppRow("النظام", null, none, bytes)
        }
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            ?: return AppRow("تطبيقات النظام", null, none, bytes)
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            AppRow(pm.getApplicationLabel(info).toString(), pkg, info.category, bytes)
        } catch (e: Exception) {
            AppRow(pkg, pkg, none, bytes)
        }
    }
}
