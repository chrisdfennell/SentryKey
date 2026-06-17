package com.example.sentrykey

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * Home-screen widget listing accounts with tap-to-reveal codes. Codes are hidden
 * (•••) by default so a glance at the home screen doesn't expose them; tapping a
 * row reveals that account's current code (recomputed live), tapping again hides
 * it, and the ⟳ button re-hides + refreshes. Recomputing on tap also sidesteps
 * the battery cost of updating a widget every 30s.
 */
class TotpWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.sentrykey.widget.REFRESH"
        const val ACTION_REVEAL = "com.example.sentrykey.widget.REVEAL"
        const val EXTRA_INDEX = "widget_index"
        private const val PREFS = "widget_state"

        fun revealedIndex(context: Context, widgetId: Int): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("revealed_$widgetId", -1)

        fun setRevealedIndex(context: Context, widgetId: Int, index: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("revealed_$widgetId", index).apply()
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val mgr = AppWidgetManager.getInstance(context)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_REFRESH -> {
                setRevealedIndex(context, id, -1) // hide all on refresh
                mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
            ACTION_REVEAL -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, -1)
                val current = revealedIndex(context, id)
                setRevealedIndex(context, id, if (current == idx) -1 else idx) // toggle
                mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_totp)

        // List backed by TotpWidgetService; unique data so each widget id is distinct.
        val svcIntent = Intent(context, TotpWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        svcIntent.data = Uri.parse(svcIntent.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.widget_list, svcIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val mutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        // ⟳ refresh
        val refresh = Intent(context, TotpWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id, refresh, immutable))

        // title -> open app
        views.setOnClickPendingIntent(
            R.id.widget_title,
            PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), immutable)
        )

        // row click template -> REVEAL (fill-in carries the row index)
        val revealTemplate = Intent(context, TotpWidgetProvider::class.java).apply {
            action = ACTION_REVEAL
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getBroadcast(context, id, revealTemplate, mutable))

        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }
}
