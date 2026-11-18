package app.helpmestocks.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import app.helpmestocks.data.AppSettings
import app.helpmestocks.data.Content
import app.helpmestocks.data.QuoteSet
import app.helpmestocks.data.Store
import app.helpmestocks.data.TapAction
import app.helpmestocks.data.Ticker
import app.helpmestocks.data.WidgetConfig
import app.helpmestocks.ui.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

data class WidgetState(
    val app: AppSettings,
    val cfg: WidgetConfig,
    val quotes: QuoteSet,
    val refreshing: Boolean,
    val appWidgetId: Int,
) {
    /** What this widget shows, in order. */
    val tickers: List<Ticker>
        get() = if (cfg.content == Content.SINGLE) {
            listOfNotNull(app.watchlist.firstOrNull { it.symbol == cfg.single } ?: app.watchlist.firstOrNull())
        } else app.watchlist
}

class StocksWidget : GlanceAppWidget() {

    // Exact: we lay out continuously from the real size (row counts, chart widths).
    override val sizeMode: SizeMode = SizeMode.Exact

    // Generated picker previews at real Pixel spans (~97dp per column, ~105dp per row).
    override val previewSizeMode = SizeMode.Responsive(
        setOf(DpSize(190.dp, 210.dp), DpSize(387.dp, 105.dp), DpSize(387.dp, 212.dp), DpSize(387.dp, 316.dp)),
    )

    /** Called by [WidgetRenderer] via compose(): read state once, render once. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = stateFlow(Store(context), appWidgetId).first()
        provideContent { StocksContent(state) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = SampleData.state()
        provideContent { StocksContent(sample) }
    }

    companion object {
        fun stateFlow(store: Store, appWidgetId: Int): Flow<WidgetState> =
            combine(store.app, store.widget(appWidgetId), store.refreshing, store.quotes) { a, c, r, q ->
                WidgetState(a, c, q, r, appWidgetId)
            }.distinctUntilChanged()
    }
}

/** Root: background, corner radius, and the tap action. Layout is chosen from the real size. */
@Composable
fun StocksContent(s: WidgetState) {
    val ctx = androidx.glance.LocalContext.current
    val colors = widgetColors(s.cfg)
    val tap = rootTap(ctx, s)
    var mod = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(colors.bg)
    if (tap != null) mod = mod.clickable(tap)
    Box(modifier = mod) {
        WidgetLayouts(s, colors)
    }
}

private fun openApp(ctx: Context, s: WidgetState) = actionStartActivity(
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_WIDGET_ID, s.appWidgetId),
)

fun rootTap(ctx: Context, s: WidgetState): Action? {
    if (s.tickers.isEmpty()) return openApp(ctx, s)
    if (s.quotes.quotes.isEmpty() && !s.refreshing) return refreshAction()
    return when (s.cfg.tapAction) {
        TapAction.NONE -> null
        TapAction.REFRESH -> refreshAction()
        TapAction.APP -> openApp(ctx, s)
        TapAction.YAHOO, TapAction.GOOGLE -> actionStartActivity(
            // A single-stock widget opens that stock; a watchlist opens the provider's markets page.
            if (s.cfg.content == Content.SINGLE) s.tickers.firstOrNull()?.let { quoteIntent(ctx, s.cfg.tapAction, it) } ?: homeIntent(ctx, s.cfg.tapAction)
            else homeIntent(ctx, s.cfg.tapAction),
        )
    }
}

/** Tapping a row opens that stock (only for the finance-app tap actions). */
fun rowTap(ctx: Context, s: WidgetState, t: Ticker): Action? = when (s.cfg.tapAction) {
    TapAction.YAHOO, TapAction.GOOGLE -> actionStartActivity(quoteIntent(ctx, s.cfg.tapAction, t))
    else -> null
}

/**
 * Yahoo Finance's app handles its own quote links (the same pages Apple's Stocks widget links
 * to); without the app they open in the browser. Google opens its finance card in the Google
 * app or browser.
 */
fun quoteIntent(ctx: Context, provider: TapAction, t: Ticker): Intent {
    val enc = Uri.encode(t.symbol)
    return if (provider == TapAction.GOOGLE) {
        view(ctx, Uri.parse("https://www.google.com/search?q=${Uri.encode(t.symbol.removePrefix("^") + " " + if (t.type == "INDEX") t.name else "stock")}"), GOOGLE_APP_PKG)
    } else {
        view(ctx, Uri.parse("https://finance.yahoo.com/quote/$enc/"), YAHOO_FINANCE_PKG)
    }
}

fun homeIntent(ctx: Context, provider: TapAction): Intent {
    if (provider == TapAction.YAHOO) {
        ctx.packageManager.getLaunchIntentForPackage(YAHOO_FINANCE_PKG)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return view(ctx, Uri.parse("https://finance.yahoo.com/markets/"), null)
    }
    return view(ctx, Uri.parse("https://www.google.com/finance/"), null)
}

private fun view(ctx: Context, uri: Uri, preferPkg: String?): Intent {
    val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (preferPkg != null) {
        val pinned = Intent(i).setPackage(preferPkg)
        if (ctx.packageManager.resolveActivity(pinned, 0) != null) return pinned
    }
    return i
}

const val YAHOO_FINANCE_PKG = "com.yahoo.mobile.client.android.finance"
const val GOOGLE_APP_PKG = "com.google.android.googlequicksearchbox"
