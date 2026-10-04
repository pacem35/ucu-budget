package com.pacemckinney.tally.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class Cadence(val days: Int, val tolerance: Int, val label: String) {
    WEEKLY(7, 1, "weekly"),
    BIWEEKLY(14, 2, "every 2 weeks"),
    SEMIMONTHLY(15, 3, "twice a month"),
    MONTHLY(30, 4, "monthly"),
    ;

    fun next(from: LocalDate): LocalDate = when (this) {
        MONTHLY -> from.plusMonths(1)
        else -> from.plusDays(days.toLong())
    }
}

data class RecurringStream(
    val key: String,
    val name: String,
    val isIncome: Boolean,
    val cadence: Cadence,
    val typicalAmount: Double,
    val lastAmount: Double,
    val previousAmount: Double,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    val category: String,
    val occurrences: Int,
) {
    /** Roughly what this costs (or pays) per month. */
    val monthlyAmount: Double get() = typicalAmount * 30.4 / cadence.days
}

object Recurring {
    /** Collapse "NETFLIX.COM 866-579 #4421" and "Netflix.com 4419" into one key. */
    fun key(t: Txn): String {
        val base = (t.merchant?.takeIf { it.isNotBlank() } ?: t.name).lowercase()
        return base
            .replace(Regex("[0-9#*]+"), " ")
            .replace(Regex("[^a-z& ]"), " ")
            .split(' ')
            .filter { it.length > 1 && it !in NOISE }
            .take(3)
            .joinToString(" ")
            .ifBlank { base.trim() }
    }

    private val NOISE = setOf("pos", "debit", "purchase", "ach", "card", "recurring", "payment", "www", "com", "inc", "llc", "co")

    fun detect(txns: List<Txn>, classifier: Classifier, today: LocalDate): List<RecurringStream> {
        val window = today.minusDays(200)
        val relevant = txns.filter {
            !it.pending && it.date >= window && it.date <= today &&
                classifier.kind(it).let { k -> k == Kind.SPEND || k == Kind.INCOME }
        }
        val groups = relevant.groupBy { key(it) to (it.amount < 0) }
        val out = ArrayList<RecurringStream>()
        for ((k, list) in groups) {
            val (name, isIncome) = k
            if (list.size < 3) continue
            val sorted = list.sortedBy { it.date }
            // Two charges on the same day are one billing event (e.g. split payments).
            val events = sorted.groupBy { it.date }.map { (d, ts) -> d to ts.sumOf { abs(it.amount) } }
            if (events.size < 3) continue
            val gaps = events.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.first, b.first).toInt() }
            val cadence = Cadence.entries.firstOrNull { c ->
                gaps.count { abs(it - c.days) <= c.tolerance } >= (gaps.size * 2 + 2) / 3
            } ?: continue
            val amounts = events.map { it.second }
            val median = amounts.sorted()[amounts.size / 2]
            if (!isIncome) {
                // Bills can wobble (utilities), but a coffee shop you visit weekly isn't a bill.
                val spread = amounts.count { abs(it - median) <= median * 0.25 + 1.0 }
                if (spread < amounts.size * 2 / 3.0) continue
            }
            val last = events.last()
            var next = cadence.next(last.first)
            // A stream that has missed two cycles has probably stopped.
            if (ChronoUnit.DAYS.between(last.first, today) > cadence.days * 2 + cadence.tolerance) continue
            while (next < today) next = cadence.next(next)
            out += RecurringStream(
                key = name,
                name = sorted.last().displayName,
                isIncome = isIncome,
                cadence = cadence,
                typicalAmount = median,
                lastAmount = last.second,
                previousAmount = events[events.size - 2].second,
                lastDate = last.first,
                nextDate = next,
                category = sorted.last().effectiveCategory,
                occurrences = events.size,
            )
        }
        return out.sortedBy { it.nextDate }
    }

    /** Every date a stream is expected to hit in [from, until). */
    fun occurrencesBetween(s: RecurringStream, from: LocalDate, until: LocalDate): List<LocalDate> {
        val dates = ArrayList<LocalDate>()
        var d = s.nextDate
        while (d < from) d = s.cadence.next(d)
        while (d < until) {
            dates += d
            d = s.cadence.next(d)
        }
        return dates
    }
}
