package com.pacemckinney.tally.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class Severity { GOOD, INFO, WARN, ALERT }

data class Insight(
    val id: String,
    val severity: Severity,
    val title: String,
    val body: String,
    val category: String? = null,
)

data class CategorySpend(val category: String, val amount: Double, val count: Int)

data class BudgetStatus(
    val budget: Budget,
    val spent: Double,
    val projected: Double,
) {
    val fraction: Double get() = if (budget.monthlyLimit <= 0) 0.0 else spent / budget.monthlyLimit
    val remaining: Double get() = budget.monthlyLimit - spent
}

data class SafeToSpend(
    val spendableNow: Double,
    val billsBeforeHorizon: Double,
    val bills: List<Pair<RecurringStream, LocalDate>>,
    val horizon: LocalDate,
    val horizonIsPayday: Boolean,
) {
    val amount: Double get() = spendableNow - billsBeforeHorizon
    fun perDay(today: LocalDate): Double = amount / max(1, ChronoUnit.DAYS.between(today, horizon).toInt())
}

/** Everything the screens need, computed in one pass from the stored data. */
data class Snapshot(
    val today: LocalDate,
    val month: YearMonth,
    val incomeMtd: Double,
    val spendMtd: Double,
    val spendLastMonthSamePoint: Double,
    val spendLastMonthTotal: Double,
    val projectedMonthSpend: Double,
    val byCategory: List<CategorySpend>,
    /** Cumulative spend for each day of this month so far (index 0 = day 1). */
    val cumulativeThisMonth: List<Double>,
    /** Cumulative spend for every day of last month. */
    val cumulativeLastMonth: List<Double>,
    val budgets: List<BudgetStatus>,
    val recurring: List<RecurringStream>,
    val nextPayday: RecurringStream?,
    val safeToSpend: SafeToSpend?,
    val insights: List<Insight>,
    val totalSpendable: Double,
) {
    val net: Double get() = incomeMtd - spendMtd
}

object InsightEngine {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    fun money(v: Double): String {
        val r = abs(v)
        val s = if (r >= 1000) String.format(Locale.US, "%,.0f", r) else String.format(Locale.US, "%,.2f", r)
        return (if (v < 0) "-$" else "$") + s
    }

    /** Net spending per category over a date range (spending minus refunds), largest first. */
    fun spendByCategory(txns: List<Txn>, c: Classifier, from: LocalDate, toInclusive: LocalDate): List<CategorySpend> {
        val map = HashMap<String, Pair<Double, Int>>()
        for (t in txns) {
            if (t.date < from || t.date > toInclusive) continue
            val k = c.kind(t)
            if (k != Kind.SPEND && k != Kind.REFUND) continue
            val cur = map[t.effectiveCategory] ?: (0.0 to 0)
            map[t.effectiveCategory] = (cur.first + t.amount) to (cur.second + if (k == Kind.SPEND) 1 else 0)
        }
        return map.map { (cat, v) -> CategorySpend(cat, v.first, v.second) }
            .filter { it.amount > 0.005 }
            .sortedByDescending { it.amount }
    }

    fun income(txns: List<Txn>, c: Classifier, from: LocalDate, toInclusive: LocalDate): Double =
        txns.filter { it.date in from..toInclusive && c.kind(it) == Kind.INCOME }.sumOf { -it.amount }

    private fun cumulative(txns: List<Txn>, c: Classifier, month: YearMonth, throughDay: Int): List<Double> {
        val daily = DoubleArray(throughDay)
        for (t in txns) {
            if (YearMonth.from(t.date) != month || t.date.dayOfMonth > throughDay) continue
            val k = c.kind(t)
            if (k == Kind.SPEND || k == Kind.REFUND) daily[t.date.dayOfMonth - 1] += t.amount
        }
        var run = 0.0
        return daily.map { run += it; run }
    }

    fun build(
        txns: List<Txn>,
        accounts: List<Account>,
        budgets: List<Budget>,
        today: LocalDate,
        largeTxnThreshold: Double = 200.0,
        lowBalanceThreshold: Double = 100.0,
    ): Snapshot {
        val c = Classifier(txns)
        val month = YearMonth.from(today)
        val day = today.dayOfMonth
        val monthStart = month.atDay(1)
        val lastMonth = month.minusMonths(1)
        val lastStart = lastMonth.atDay(1)
        val lastEnd = lastMonth.atEndOfMonth()
        val lastSamePoint = lastMonth.atDay(minOf(day, lastMonth.lengthOfMonth()))

        val byCat = spendByCategory(txns, c, monthStart, today)
        val spendMtd = byCat.sumOf { it.amount }
        val incomeMtd = income(txns, c, monthStart, today)
        val lastSame = spendByCategory(txns, c, lastStart, lastSamePoint).sumOf { it.amount }
        val lastTotal = spendByCategory(txns, c, lastStart, lastEnd).sumOf { it.amount }

        val recurring = Recurring.detect(txns, c, today)
        val bills = recurring.filter { !it.isIncome }
        val paydays = recurring.filter { it.isIncome && it.typicalAmount >= 100 }
        val nextPayday = paydays.minByOrNull { it.nextDate }

        // Projection: what's been spent + bills still to come this month + typical day-to-day
        // spending (last 60 days, bills removed) for the days that are left.
        val billKeys = bills.map { it.key }.toSet()
        val sixtyAgo = today.minusDays(60)
        val variable60 = txns.filter {
            it.date > sixtyAgo && it.date <= today && c.kind(it).let { k -> k == Kind.SPEND || k == Kind.REFUND } &&
                Recurring.key(it) !in billKeys
        }.sumOf { it.amount }
        val historyDays = txns.minOfOrNull { it.date }?.let { ChronoUnit.DAYS.between(it, today).toInt().coerceIn(1, 60) } ?: 1
        val dailyVariable = max(0.0, variable60 / historyDays)
        val remainingDays = month.lengthOfMonth() - day
        val billsLeftThisMonth = bills.sumOf { s ->
            Recurring.occurrencesBetween(s, today.plusDays(1), month.atEndOfMonth().plusDays(1)).size * s.typicalAmount
        }
        val projected = spendMtd + billsLeftThisMonth + dailyVariable * remainingDays

        val depository = accounts.filter { it.isDepository }
        val spendAccounts = depository.filter { it.isChecking }.ifEmpty { depository }
        val totalSpendable = spendAccounts.sumOf { it.spendable }

        val safe = if (spendAccounts.isEmpty()) null else {
            val horizon = nextPayday?.nextDate?.takeIf { it > today } ?: month.atEndOfMonth().plusDays(1)
            val due = bills.flatMap { s -> Recurring.occurrencesBetween(s, today, horizon).map { s to it } }
                .sortedBy { it.second }
            SafeToSpend(totalSpendable, due.sumOf { it.first.typicalAmount }, due, horizon, nextPayday != null)
        }

        val budgetStatus = budgets.map { b ->
            val spent = byCat.firstOrNull { it.category == b.category }?.amount ?: 0.0
            val proj = if (day == 0) spent else spent / day * month.lengthOfMonth()
            BudgetStatus(b, spent, proj)
        }.sortedByDescending { it.fraction }

        val insights = ArrayList<Insight>()
        fun add(i: Insight) { insights += i }

        // 1. Safe to spend.
        if (safe != null) {
            val until = if (safe.horizonIsPayday) "payday (${safe.horizon.format(dayFmt)})" else "the end of the month"
            val perDay = safe.perDay(today)
            if (safe.amount < 0) add(Insight("safe", Severity.ALERT, "Bills exceed your balance",
                "${money(safe.billsBeforeHorizon)} in expected bills before $until, but only ${money(safe.spendableNow)} available. Short by ${money(-safe.amount)}."))
            else add(Insight("safe", if (perDay < 15) Severity.WARN else Severity.GOOD, "${money(perDay)}/day until $until",
                "${money(safe.spendableNow)} available minus ${money(safe.billsBeforeHorizon)} in upcoming bills leaves ${money(safe.amount)} to spend."))
        }

        // 2. Pace vs last month.
        if (lastSame > 25 && day >= 3) {
            val diff = (spendMtd - lastSame) / lastSame
            val pct = (abs(diff) * 100).roundToInt()
            when {
                diff > 0.15 -> add(Insight("pace", Severity.WARN, "Spending is $pct% ahead of last month",
                    "${money(spendMtd)} so far vs ${money(lastSame)} by this day last month."))
                diff < -0.10 -> add(Insight("pace", Severity.GOOD, "Spending is $pct% below last month",
                    "${money(spendMtd)} so far vs ${money(lastSame)} by this day last month."))
                else -> add(Insight("pace", Severity.INFO, "Spending is on par with last month",
                    "${money(spendMtd)} so far vs ${money(lastSame)} by this day last month."))
            }
        }

        // 3. Month-end projection.
        if (day >= 5 && historyDays >= 14) {
            val sev = if (incomeMtd > 0 && projected > lastTotal * 1.15 && lastTotal > 0) Severity.WARN else Severity.INFO
            add(Insight("projection", sev, "On track to spend about ${money(projected)} this month",
                "Includes ${money(billsLeftThisMonth)} in bills still to come and about ${money(dailyVariable)}/day of everyday spending." +
                    if (lastTotal > 0) " Last month was ${money(lastTotal)}." else ""))
        }

        // 4. Budgets.
        for (b in budgetStatus) {
            val label = Categories.label(b.budget.category)
            when {
                b.fraction >= 1.0 -> add(Insight("budget-${b.budget.category}", Severity.ALERT, "$label budget is over",
                    "${money(b.spent)} of ${money(b.budget.monthlyLimit)} — ${money(-b.remaining)} over.", b.budget.category))
                b.fraction >= 0.8 -> add(Insight("budget-${b.budget.category}", Severity.WARN, "$label budget is at ${(b.fraction * 100).roundToInt()}%",
                    "${money(b.remaining)} left for the rest of the month.", b.budget.category))
                b.projected > b.budget.monthlyLimit * 1.05 && day >= 7 -> add(Insight("budget-${b.budget.category}", Severity.WARN,
                    "$label is on pace to go over", "At this rate you'll spend about ${money(b.projected)} against a ${money(b.budget.monthlyLimit)} budget.", b.budget.category))
            }
        }

        // 5. Category spikes vs your 3-month average for this point in the month.
        // Bills are left out: rent landing on the 1st isn't "running high".
        if (day >= 5) {
            val frac = day.toDouble() / month.lengthOfMonth()
            val everyday = txns.filter { Recurring.key(it) !in billKeys }
            for (cs in spendByCategory(everyday, c, monthStart, today)) {
                val hist = (1..3).map { m ->
                    val ym = month.minusMonths(m.toLong())
                    spendByCategory(everyday, c, ym.atDay(1), ym.atEndOfMonth()).firstOrNull { it.category == cs.category }?.amount ?: 0.0
                }
                val monthsWithData = (1..3).count { m -> txns.any { YearMonth.from(it.date) == month.minusMonths(m.toLong()) } }
                if (monthsWithData < 2) break
                val expected = hist.sum() / monthsWithData * frac
                if (cs.amount > expected * 1.4 && cs.amount - expected > 40) {
                    add(Insight("spike-${cs.category}", Severity.WARN, "${Categories.label(cs.category)} is running high",
                        "${money(cs.amount)} so far vs about ${money(expected)} usual by now.", cs.category))
                }
            }
        }

        // 6. Large transactions (last 7 days).
        val weekAgo = today.minusDays(7)
        val recentSpend = txns.filter { it.date > weekAgo && c.kind(it) == Kind.SPEND }
        for (t in recentSpend) {
            val history = txns.filter {
                it.effectiveCategory == t.effectiveCategory && it.id != t.id && c.kind(it) == Kind.SPEND &&
                    it.date > today.minusDays(120)
            }.map { it.amount }.sorted()
            val typical = if (history.size >= 5) history[history.size / 2] else null
            val big = t.amount >= largeTxnThreshold || (typical != null && t.amount >= typical * 4 && t.amount >= 75)
            if (big) add(Insight("large-${t.id}", Severity.INFO, "Large charge: ${t.displayName}",
                "${money(t.amount)} on ${t.date.format(dayFmt)}" + (typical?.let { " — usually about ${money(it)} for ${Categories.label(t.effectiveCategory).lowercase()}." } ?: "."),
                t.effectiveCategory))
        }

        // 7. Possible duplicate charges (last 14 days).
        val twoWeeks = txns.filter { it.date > today.minusDays(14) && c.kind(it) == Kind.SPEND }
            .sortedBy { it.date }
        val seen = HashSet<String>()
        for (i in twoWeeks.indices) for (j in i + 1 until twoWeeks.size) {
            val a = twoWeeks[i]; val b = twoWeeks[j]
            if (ChronoUnit.DAYS.between(a.date, b.date) > 1) break
            if (a.accountId == b.accountId && abs(a.amount - b.amount) < 0.005 && a.amount >= 5 &&
                Recurring.key(a) == Recurring.key(b) && a.pending == b.pending && seen.add(a.id + b.id)
            ) add(Insight("dup-${a.id}", Severity.WARN, "Possible double charge: ${a.displayName}",
                "Two charges of ${money(a.amount)} on ${a.date.format(dayFmt)} and ${b.date.format(dayFmt)}. Worth checking with the merchant or UCU.",
                a.effectiveCategory))
        }

        // 8. Price increases on bills/subscriptions.
        for (s in bills) {
            if (s.lastAmount > s.previousAmount * 1.05 && s.lastAmount - s.previousAmount >= 1 &&
                ChronoUnit.DAYS.between(s.lastDate, today) <= 35 && s.category != Categories.RENT_AND_UTILITIES
            ) add(Insight("price-${s.key}", Severity.WARN, "${s.name} went up",
                "Charged ${money(s.lastAmount)}, up from ${money(s.previousAmount)}.", s.category))
        }

        // 9. Bank fees this month.
        byCat.firstOrNull { it.category == Categories.BANK_FEES }?.let {
            add(Insight("fees", Severity.WARN, "${money(it.amount)} in bank fees this month",
                "${it.count} fee charge(s). UCU can sometimes waive these if you ask.", Categories.BANK_FEES))
        }

        // 10. Low balance.
        for (a in spendAccounts) if (a.spendable < lowBalanceThreshold) add(Insight("low-${a.id}", Severity.ALERT,
            "Low balance in ${a.name}", "${money(a.spendable)} available."))

        // 11. Subscriptions summary.
        val subs = bills.filter { it.cadence == Cadence.MONTHLY && it.category in setOf(Categories.ENTERTAINMENT, Categories.GENERAL_SERVICES, Categories.GENERAL_MERCHANDISE) }
        if (subs.size >= 2) add(Insight("subs", Severity.INFO, "${subs.size} subscriptions cost ${money(subs.sumOf { it.monthlyAmount })}/month",
            subs.sortedByDescending { it.typicalAmount }.take(5).joinToString(", ") { "${it.name} ${money(it.typicalAmount)}" }))

        // 12. Next payday.
        nextPayday?.let {
            add(Insight("payday", Severity.INFO, "Next paycheck around ${it.nextDate.format(dayFmt)}",
                "Usually ${money(it.typicalAmount)}, ${it.cadence.label}."))
        }

        // 13. Net for the month.
        if (incomeMtd > 0 && day >= 10) {
            val n = incomeMtd - spendMtd
            add(Insight("net", if (n >= 0) Severity.GOOD else Severity.WARN,
                if (n >= 0) "Up ${money(n)} this month" else "Down ${money(-n)} this month",
                "${money(incomeMtd)} in, ${money(spendMtd)} out."))
        }

        val order = listOf(Severity.ALERT, Severity.WARN, Severity.GOOD, Severity.INFO)
        return Snapshot(
            today = today,
            month = month,
            incomeMtd = incomeMtd,
            spendMtd = spendMtd,
            spendLastMonthSamePoint = lastSame,
            spendLastMonthTotal = lastTotal,
            projectedMonthSpend = projected,
            byCategory = byCat,
            cumulativeThisMonth = cumulative(txns, c, month, day),
            cumulativeLastMonth = cumulative(txns, c, lastMonth, lastMonth.lengthOfMonth()),
            budgets = budgetStatus,
            recurring = recurring,
            nextPayday = nextPayday,
            safeToSpend = safe,
            insights = insights.sortedBy { order.indexOf(it.severity) },
            totalSpendable = totalSpendable,
        )
    }
}
