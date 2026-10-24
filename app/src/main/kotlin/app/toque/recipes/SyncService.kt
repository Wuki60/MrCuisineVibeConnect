package app.toque.recipes

import android.app.AlarmManager
import android.app.IntentService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.machine.MachineService
import app.toque.ui.MainActivity
import java.util.Calendar

/** Synchro nocturne des recettes Lidl (03 h 30), uniquement en mode machine réelle. */
@Suppress("DEPRECATION")
class SyncService : IntentService("toque-synchro") {
    override fun onHandleIntent(intent: Intent?) {
        val app = ToqueApp.of(this)
        if (app.prefs.mode != Prefs.Mode.HARDWARE || !app.prefs.autoSync) return
        if (System.currentTimeMillis() - app.prefs.lastSyncMs < 6 * 3600_000L) return
        LidlSync(this, app.prefs, app.recipes, app.log).sync()
    }

    companion object {
        fun schedule(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 0, Intent(c, SyncAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }
}

class SyncAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        c.startService(Intent(c, SyncService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        SyncService.schedule(c)
        c.startService(Intent(c, MachineService::class.java))
        if (Prefs(c).bootLaunch) {
            // Le robot n'a pas de bouton Accueil : Toque s'ouvre tout seul au démarrage, puis repasse
            // devant 20 s plus tard au cas où l'appli d'origine (écran d'accueil) se serait ouverte après lui.
            val open = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(open)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000,
                PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}
