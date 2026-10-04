package com.pacemckinney.tally

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pacemckinney.tally.data.Repo
import com.pacemckinney.tally.work.SyncWorker
import java.util.concurrent.TimeUnit

class TallyApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TXN, "New transactions", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Budget & balance alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_INCOME, "Deposits", NotificationManager.IMPORTANCE_DEFAULT))
        if (repo.store.items.isNotEmpty()) schedule(this, repo.prefs.syncMinutes, replace = false)
    }

    companion object {
        const val CH_TXN = "txn"
        const val CH_ALERT = "alert"
        const val CH_INCOME = "income"
        private const val PERIODIC = "tally-sync"

        fun repo(c: Context) = (c.applicationContext as TallyApp).repo

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Background sync. Android won't run periodic work more often than every 15 minutes. */
        fun schedule(c: Context, minutes: Int, replace: Boolean = true) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(net).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /**
         * Right after connecting a bank Plaid is still pulling your history, so check back a few
         * times over the next ten minutes.
         */
        fun followUpSyncs(c: Context) {
            val wm = WorkManager.getInstance(c)
            listOf(1L, 4L, 10L).forEach { m ->
                wm.enqueueUniqueWork("followup-$m", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(m, TimeUnit.MINUTES).setConstraints(net).build())
            }
        }
    }
}
