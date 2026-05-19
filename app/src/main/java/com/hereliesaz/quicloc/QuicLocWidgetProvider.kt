package com.hereliesaz.quicloc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget. Every tap fires
 * `LocationReplyService.ACTION_WIDGET_TAP`; the service counts taps within
 * a 400 ms window and dispatches based on count:
 *
 *   - 1 tap → [WidgetHelpActivity]
 *   - 2 taps → Parking SMS to your own number
 *   - 3 taps → Safety Check SMS to starred contacts
 *   - 4 taps → Emergency SMS to the entire whitelist
 *
 * See [LocationReplyService.widgetTapRunnable] for the dispatch logic.
 */
class QuicLocWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, LocationReplyService::class.java).apply {
                action = LocationReplyService.ACTION_WIDGET_TAP
            }
            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_quicloc)
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
