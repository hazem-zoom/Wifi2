package com.hazem.wifiusage

import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Local storage: one row per sample = (ssid, time, bytes used since the previous sample). */
class UsageDb private constructor(context: Context) : SQLiteOpenHelper(context, "usage.db", null, 2) {

    init { setWriteAheadLoggingEnabled(true) }

    companion object {
        @Volatile private var instance: UsageDb? = null

        /** One shared database object for the whole app (safe to use from any thread). */
        fun get(context: Context): UsageDb =
            instance ?: synchronized(this) {
                instance ?: UsageDb(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (ts INTEGER NOT NULL, ssid TEXT NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_ts ON samples(ts)")
        createRenewals(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        // Old data is never touched; we only add the renewals table.
        if (o < 2) createRenewals(db)
    }

    private fun createRenewals(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS renewals (ssid TEXT NOT NULL, ts INTEGER NOT NULL)")
    }

    /** Logs a plan renewal ("reset") for a network key (Prefs.key). Usage history is not changed. */
    fun addRenewal(key: String, ts: Long) {
        writableDatabase.insert("renewals", null, ContentValues().apply { put("ssid", key); put("ts", ts) })
    }

    fun deleteRenewal(key: String, ts: Long) {
        writableDatabase.delete("renewals", "ssid = ? AND ts = ?", arrayOf(key, "$ts"))
    }

    /** All renewal times for a network, oldest first. */
    fun renewals(key: String): List<Long> =
        readableDatabase.rawQuery("SELECT ts FROM renewals WHERE ssid = ? ORDER BY ts", arrayOf(key)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun insert(ts: Long, ssid: String, bytes: Long) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("ts", ts); put("ssid", ssid); put("bytes", bytes)
        })
    }

    /** Total bytes for a network (or all networks when ssid == null) inside [from, to]. */
    fun total(ssid: String?, from: Long, to: Long): Long {
        val sql = "SELECT COALESCE(SUM(bytes),0) FROM samples WHERE ts BETWEEN ? AND ?" +
            if (ssid != null) " AND ssid = ?" else ""
        val args = if (ssid != null) arrayOf("$from", "$to", ssid) else arrayOf("$from", "$to")
        readableDatabase.rawQuery(sql, args).use { c -> c.moveToFirst(); return c.getLong(0) }
    }

    fun knownSsids(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT ssid FROM samples ORDER BY ssid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Time of the oldest saved sample, or 0 when there is none yet. */
    fun firstSampleTime(): Long =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM samples", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }

    /** Total per network for the period, biggest first. */
    fun ssidTotals(from: Long, to: Long): List<Pair<String, Long>> =
        readableDatabase.rawQuery(
            "SELECT ssid, SUM(bytes) AS s FROM samples WHERE ts BETWEEN ? AND ? GROUP BY ssid ORDER BY s DESC",
            arrayOf("$from", "$to")
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
        }

    /** (day, network, bytes) rows for CSV export. */
    fun dailyRows(ssid: String?, from: Long, to: Long): List<Triple<String, String, Long>> {
        val sql = "SELECT strftime('%Y-%m-%d', ts/1000, 'unixepoch', 'localtime') AS d, ssid, SUM(bytes) " +
            "FROM samples WHERE ts BETWEEN ? AND ?" +
            (if (ssid != null) " AND ssid = ?" else "") +
            " GROUP BY d, ssid ORDER BY d, ssid"
        val args = if (ssid != null) arrayOf("$from", "$to", ssid) else arrayOf("$from", "$to")
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) }
        }
    }
}

object WifiUsage {

    /** Used when we're on Wi-Fi but the system won't tell us the network name. */
    const val UNKNOWN_SSID = "شبكة غير معروفة"

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Total Wi-Fi bytes (rx + tx) on the device between two timestamps, straight from the system. */
    fun systemWifiBytes(context: Context, from: Long, to: Long): Long {
        var total = 0L
        for (b in appBytes(context, from, to).values) total += b
        return total
    }

    /** Wi-Fi bytes per app (keyed by uid) between two timestamps. */
    fun appBytes(context: Context, from: Long, to: Long): Map<Int, Long> {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val bucket = NetworkStats.Bucket()
        val result = HashMap<Int, Long>()
        val stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, from, to)
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            result[bucket.uid] = (result[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        return result
    }

    /**
     * Takes one sample now: the Wi-Fi bytes used since the previous sample,
     * attributed to the network we're connected to. If the network name can't be read
     * (background, or Wi-Fi just dropped) we fall back to the last network we saw.
     */
    @Synchronized
    fun sampleNow(context: Context) {
        val prefs = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        // We track the running total since a fixed day boundary and store the difference between
        // two samples. (Asking the system for short windows is inaccurate: it counts the whole
        // current hour for every window that touches it.)
        var base = prefs.getLong("base_start", 0L)
        if (base == 0L) {
            base = now - now % day
            prefs.edit().putLong("base_start", base).remove("last_cum").apply()
        }

        val cum = try {
            systemWifiBytes(context, base, now)
        } catch (e: Exception) {
            return
        }
        val lastCum = prefs.getLong("last_cum", -1L)
        val delta = if (lastCum < 0) 0L else maxOf(cum - lastCum, 0L)

        // The bytes we just measured were used BEFORE this moment, so they belong to the network
        // we were on at the previous sample. At a network switch this keeps them on the old one.
        val live = currentSsid(context)
        val ssid = prefs.getString("last_ssid", null) ?: live ?: UNKNOWN_SSID

        if (delta > 0) UsageDb.get(context).insert(now, ssid, delta)
        if (live != null) prefs.edit().putString("last_ssid", live).apply()

        // Move the baseline forward every couple of weeks so old history can be trimmed safely
        var newCum = cum
        if (now - base > 14 * day) {
            val newBase = now - now % day
            try {
                newCum = systemWifiBytes(context, newBase, now)
                prefs.edit().putLong("base_start", newBase).apply()
            } catch (e: Exception) { }
        }
        prefs.edit().putLong("last_cum", newCum).apply()

        try { LimitChecker.check(context) } catch (e: Exception) { }
        try { UsageWidget.refreshAll(context) } catch (e: Exception) { }
    }

    /** Current Wi-Fi network name, or null when not on Wi-Fi. Needs location permission. */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid?.trim('"') ?: return null
        return if (ssid == "<unknown ssid>" || ssid.isBlank()) null else ssid
    }
}

/**
 * Runs every ~15 min (WorkManager minimum). Takes the Wi-Fi bytes used since the last run
 * and attributes them to the network we're connected to right now.
 */
class SampleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try {
            WifiUsage.sampleNow(applicationContext)
            NetworkWatcher.register(applicationContext) // also re-registers after a reboot
        } catch (e: Exception) { }
        return Result.success()
    }
}

/**
 * Wakes the app whenever a Wi-Fi network connects or disconnects, so the usage up to that
 * moment is saved on the right network (not only every 15 minutes).
 */
object NetworkWatcher {
    fun register(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, 77, Intent(context, NetworkChangeReceiver::class.java), flags
            )
            cm.registerNetworkCallback(request, pi)
        } catch (e: Exception) { }
    }
}

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                WifiUsage.sampleNow(context.applicationContext)
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
