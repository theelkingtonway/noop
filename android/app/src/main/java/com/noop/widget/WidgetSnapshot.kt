package com.noop.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll

/**
 * The handful of numbers the home-screen widget shows, persisted to SharedPreferences so the
 * widget can render after a process restart (Glance recomposes from disk, not from app memory).
 */
data class WidgetSnapshot(
    /** Today's recovery 0–100, null until NOOP has scored enough nights (honest-blank). */
    val recoveryPct: Int? = null,
    /** Live heart rate, null when not streaming. */
    val heartRate: Int? = null,
    /** Strap battery 0–100, null until the strap reports it. */
    val batteryPct: Int? = null,
    val connected: Boolean = false,
    /** Wall-clock millis of the last push, so the widget can show honest staleness. */
    val updatedAtMs: Long = 0L,
)

/**
 * Persists snapshots and tells Glance to recompose. Both producers funnel through [push]:
 * [com.noop.ble.WhoopConnectionService] (long-lived — the widget's heartbeat while the app UI is
 * closed) and [com.noop.ui.AppViewModel] (covers foreground use with the background service off).
 *
 * Throttled: producers emit per HR sample (~1/s), but a Glance update re-composes and re-inflates
 * RemoteViews — far heavier than a notification post. We push immediately when a *meaningful* field
 * changes (recovery, battery bucket, connection), and otherwise at most once per [HR_REFRESH_MS] so
 * the displayed heart rate still ticks along.
 */
object WidgetSnapshotStore {
    private const val FILE = "noop_widget"
    private const val HR_REFRESH_MS = 60_000L

    private var lastKey: String? = null
    private var lastPushAtMs = 0L

    suspend fun push(context: Context, snap: WidgetSnapshot) {
        val app = context.applicationContext
        // No widget placed → skip all work. (getGlanceIds is a cheap lookup, not an IPC storm.)
        val ids = runCatching {
            GlanceAppWidgetManager(app).getGlanceIds(NoopGlanceWidget::class.java)
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return

        // Battery in 5% buckets so trickle-discharge doesn't defeat the throttle.
        val key = "${snap.recoveryPct}|${snap.batteryPct?.div(5)}|${snap.connected}"
        val hrRefreshDue = snap.updatedAtMs - lastPushAtMs >= HR_REFRESH_MS
        if (key == lastKey && !hrRefreshDue) return
        lastKey = key
        lastPushAtMs = snap.updatedAtMs

        save(app, snap)
        runCatching { NoopGlanceWidget().updateAll(app) }
    }

    fun save(context: Context, snap: WidgetSnapshot) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("recovery", snap.recoveryPct ?: -1)
            .putInt("hr", snap.heartRate ?: -1)
            .putInt("battery", snap.batteryPct ?: -1)
            .putBoolean("connected", snap.connected)
            .putLong("updatedAt", snap.updatedAtMs)
            .apply()
    }

    fun load(context: Context): WidgetSnapshot {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return WidgetSnapshot(
            recoveryPct = p.getInt("recovery", -1).takeIf { it >= 0 },
            heartRate = p.getInt("hr", -1).takeIf { it > 0 },
            batteryPct = p.getInt("battery", -1).takeIf { it >= 0 },
            connected = p.getBoolean("connected", false),
            updatedAtMs = p.getLong("updatedAt", 0L),
        )
    }
}
