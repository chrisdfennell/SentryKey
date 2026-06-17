package com.example.sentrykey

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Supplies the account rows for the home-screen widget list. */
class TotpWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return TotpWidgetFactory(applicationContext, widgetId)
    }
}

private class TotpWidgetFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var accounts: List<TwoFactorAccount> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        // Reads the Keystore-encrypted vault (works in the widget process).
        accounts = VaultStorage(context).getAccounts()
    }

    override fun onDestroy() { accounts = emptyList() }
    override fun getCount(): Int = accounts.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_row)
        if (position !in accounts.indices) return rv

        val account = accounts[position]
        rv.setTextViewText(R.id.row_label, account.label)

        if (TotpWidgetProvider.revealedIndex(context, widgetId) == position) {
            val now = System.currentTimeMillis() / 1000
            val code = getTOTPCode(account.secret, now)
            rv.setTextViewText(R.id.row_code, code.take(3) + " " + code.drop(3))
            rv.setTextViewText(R.id.row_seconds, "${30 - (now % 30)}s")
        } else {
            rv.setTextViewText(R.id.row_code, "•••  •••")
            rv.setTextViewText(R.id.row_seconds, "")
        }

        // Fill-in for the provider's reveal template.
        rv.setOnClickFillInIntent(
            R.id.row_root,
            Intent().putExtra(TotpWidgetProvider.EXTRA_INDEX, position)
        )
        return rv
    }
}
