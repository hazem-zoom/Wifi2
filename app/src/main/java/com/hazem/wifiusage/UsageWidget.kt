package com.hazem.wifiusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/** Home-screen widget: today's Wi-Fi usage, and the plan progress when the current network has a limit. */
class UsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    companion object {

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))
            for (id in ids) render(context, mgr, id)
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val db = UsageDb.get(context)
            val now = System.currentTimeMillis()
            val today = db.total(null, Billing.startOfDay(0), now)
            val ssid = WifiUsage.currentSsid(context)

            var sub = ssid ?: "غير متصل بواي فاي"
            if (ssid != null) {
                val limit = Prefs.limitGb(context, ssid)
                if (limit > 0f) {
                    val start = Cycles.start(context, ssid, now)
                    val usedGb = db.total(ssid, start, now) / Fmt.GB
                    sub = "الباقة: %.1f / %.0f GB".format(Locale.US, usedGb, limit.toDouble())
                }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.w_value, Fmt.text(today))
            views.setTextViewText(R.id.w_sub, sub)

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.w_root, open)
            mgr.updateAppWidget(id, views)
        }
    }
}
