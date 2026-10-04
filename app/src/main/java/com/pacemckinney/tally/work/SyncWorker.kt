package com.pacemckinney.tally.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pacemckinney.tally.MainActivity
import com.pacemckinney.tally.R
import com.pacemckinney.tally.TallyApp
import com.pacemckinney.tally.data.Repo
import com.pacemckinney.tally.engine.Classifier
import com.pacemckinney.tally.engine.InsightEngine.money
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.Severity
import com.pacemckinney.tally.engine.Txn
import java.time.YearMonth

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = TallyApp.repo(applicationContext)
        if (repo.store.items.isEmpty()) return Result.success()
        val r = repo.sync()
        Notifier(applicationContext, repo).afterSync(r.newTxns)
        return if (r.errors.isNotEmpty() && r.errors.size == repo.store.items.size) Result.retry() else Result.success()
    }
}

class Notifier(private val c: Context, private val repo: Repo) {
    private val nm = NotificationManagerCompat.from(c)

    private fun allowed() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(id: Int, channel: String, title: String, body: String) {
        if (!allowed()) return
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    fun afterSync(newTxns: List<Txn>) {
        val p = repo.prefs
        val all = repo.db.txns(java.time.LocalDate.now().minusDays(45))
        val classifier = Classifier(all)

        val spends = newTxns.filter { classifier.kind(it) == Kind.SPEND && (p.notifyEveryTxn || it.amount >= p.notifyThreshold) }
        if (spends.size == 1) {
            val t = spends[0]
            post(t.id.hashCode(), TallyApp.CH_TXN, "${money(t.amount)} at ${t.displayName}",
                if (t.pending) "Pending charge" else "Posted ${t.date}")
        } else if (spends.size > 1) {
            post(1001, TallyApp.CH_TXN, "${spends.size} new charges, ${money(spends.sumOf { it.amount })}",
                spends.take(6).joinToString("\n") { "${money(it.amount)}  ${it.displayName}" })
        }

        if (p.notifyIncome) newTxns.filter { classifier.kind(it) == Kind.INCOME && -it.amount >= 20 }.forEach { t ->
            post(t.id.hashCode(), TallyApp.CH_INCOME, "+${money(-t.amount)} deposited", t.displayName)
        }

        if (!p.notifyAlerts) return
        val snap = repo.snapshot()
        val month = YearMonth.now().toString()
        for (i in snap.insights) {
            val important = i.severity == Severity.ALERT ||
                (i.severity == Severity.WARN && (i.id.startsWith("budget-") || i.id.startsWith("dup-") || i.id.startsWith("fees")))
            if (!important) continue
            // Budget alerts: once at 80%, once when over. Others: once per month per id.
            val key = "$month:${i.id}:${i.severity}"
            if (p.alreadyNotified(key)) continue
            p.markNotified(key)
            post(key.hashCode(), TallyApp.CH_ALERT, i.title, i.body)
        }
    }
}
