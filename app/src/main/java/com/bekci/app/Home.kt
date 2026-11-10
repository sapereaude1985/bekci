package com.bekci.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.util.UUID

/** "Did you leave home?" — a geofence around the saved home location (watcher phone only). */
object HomeFence {
    private const val ID = "ev"
    const val RADIUS = 200f

    fun enabled(ctx: Context) = Prefs.p(ctx).getBoolean("evHatirlat", false)

    fun hasPerms(ctx: Context): Boolean {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, FenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context, done: (Boolean) -> Unit = {}) {
        val p = Prefs.p(ctx)
        if (!hasPerms(ctx) || !p.contains("evLat")) { done(false); return }
        val lat = java.lang.Double.longBitsToDouble(p.getLong("evLat", 0L))
        val lng = java.lang.Double.longBitsToDouble(p.getLong("evLng", 0L))
        try {
            val fence = Geofence.Builder()
                .setRequestId(ID)
                .setCircularRegion(lat, lng, RADIUS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(30_000)
                .build()
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
            LocationServices.getGeofencingClient(ctx).addGeofences(req, pi(ctx))
                .addOnSuccessListener { done(true) }
                .addOnFailureListener { done(false) }
        } catch (e: Exception) {
            done(false)
        }
    }

    fun unregister(ctx: Context) {
        try { LocationServices.getGeofencingClient(ctx).removeGeofences(pi(ctx)) } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun captureHome(ctx: Context, done: (Boolean) -> Unit) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc == null) done(false) else {
                        Prefs.p(ctx).edit()
                            .putLong("evLat", java.lang.Double.doubleToRawLongBits(loc.latitude))
                            .putLong("evLng", java.lang.Double.doubleToRawLongBits(loc.longitude))
                            .apply()
                        done(true)
                    }
                }
                .addOnFailureListener { done(false) }
        } catch (e: Exception) {
            done(false)
        }
    }
}

class FenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError() || ev.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val p = Prefs.p(ctx)
        if (!p.getBoolean("evHatirlat", false) || p.getString("role", null) != "izleyici") return
        // Only ask when motion detection is known to be off at home.
        if (p.getBoolean("camMotion", true)) return
        Notif.leftHome(ctx)
    }
}

/** "Hareket algılamayı aç" button on the reminder: nothing changes unless the user taps it. */
class ActionReceiver : BroadcastReceiver() {
    companion object { const val ACTION_MOTION_ON = "com.bekci.app.MOTION_ON" }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_MOTION_ON) return
        val app = ctx.applicationContext
        val code = Prefs.code(app) ?: return
        Notif.cancel(app, Notif.ID_REMIND)
        val pr = goAsync()
        Thread {
            try {
                val ok = Signal.sendSync(
                    Pairing.camInbox(code), code,
                    JSONObject().put("t", "motion").put("sid", UUID.randomUUID().toString().take(8)).put("on", true)
                )
                if (ok) {
                    Prefs.p(app).edit().putBoolean("camMotion", true)
                        .putLong("camMotionPending", System.currentTimeMillis()).apply()
                    Notif.alert(app, "Hareket algılama açılıyor", "Evdeki kamera onaylayınca bildirim gelecek.", null, false)
                } else {
                    Notif.alert(app, "Komut gönderilemedi", "İnternet bağlantını kontrol edip Bekçi’den aç.", null, false)
                }
            } finally {
                pr.finish()
            }
        }.start()
    }
}

/** Geofences are cleared on reboot and on app update; put ours back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (HomeFence.enabled(ctx)) HomeFence.register(ctx.applicationContext)
    }
}
