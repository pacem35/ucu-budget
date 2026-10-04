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

/** One calendar month of money flow, split the four ways that matter. */
data class MonthFlow(
    val month: YearMonth,
    val income: Double,
    val spending: Double,
    /** Net moved into savings (negative if you pulled more out than you put in). */
    val saved: Double,
    val loanPayments: Double,
) {
    /** Income not yet spent, saved or used to pay down debt. */
    val leftOver: Double get() = income - spending - saved - loanPayments
    val savingsRate: Double? get() = if (income > 0) saved / income else null
}

data class LoanStatus(
    val account: Account,
    val owed: Double,
    val paidThisMonth: Double,
    /** Average monthly payment over the last 3 months. */
    val monthlyPayment: Double,
) {
    /** Rough months to payoff, ignoring interest. */
    val monthsLeft: Int? get() = if (monthlyPayment > 1 && owed > 0) kotlin.math.ceil(owed / monthlyPayment).toInt() else null
}

data class Balances(
    /** Spendable cash across your tracked checking accounts. */
    val cash: Double,
    val savings: Double,
    val creditOwed: Double,
    val creditLimit: Double,
    val loansOwed: Double,
) {
    val creditUtilization: Double? get() = if (creditLimit > 0) creditOwed / creditLimit else null
    /** What you have minus what you owe. */
    val netWorth: Double get() = cash + savings - creditOwed - loansOwed
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
    val thisMonth: MonthFlow,
    /** Oldest first; includes this month. */
    val history: List<MonthFlow>,
    val balances: Balances,
    val loans: List<LoanStatus>,
    /** Income this month by category (paychecks, interest, transfers in...). */
    val incomeSources: List<CategorySpend>,
    val savingsGoal: Double?,
) {
    val net: Double get() = thisMonth.leftOver
    val savedMtd: Double get() = thisMonth.saved
    val loanPaidMtd: Double get() = thisMonth.loanPayments
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

    private fun sumKind(txns: List<Txn>, c: Classifier, kind: Kind, from: LocalDate, toInclusive: LocalDate): Double =
        txns.filter { it.date in from..toInclusive && c.kind(it) == kind }.sumOf { it.amount }

    fun flow(txns: List<Txn>, c: Classifier, month: YearMonth, through: LocalDate = month.atEndOfMonth()): MonthFlow {
        val from = month.atDay(1)
        return MonthFlow(
            month = month,
            income = income(txns, c, from, through),
            spending = spendByCategory(txns, c, from, through).sumOf { it.amount },
            saved = sumKind(txns, c, Kind.SAVINGS, from, through),
            loanPayments = sumKind(txns, c, Kind.LOAN_PAYMENT, from, through),
        )
    }

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

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
        savingsGoal: Double? = null,
    ): Snapshot {
        val c = Classifier(txns, accounts)
        // Everything below ignores accounts you've hidden.
        @Suppress("NAME_SHADOWING")
        val txns = txns.filter { c.kind(it) != Kind.HIDDEN }
        @Suppress("NAME_SHADOWING")
        val accounts = accounts.filter { it.included }
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
        // Everything that leaves checking on a schedule (bills, loan payments, auto-savings)…
        val outgoing = recurring.filter { it.isOutgoing }
        // …of which "bills" are the ones that count as spending.
        val bills = recurring.filter { it.kind == Kind.SPEND }
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

        val spendAccounts = accounts.filter { it.role == AccountRole.SPENDING }
        val totalSpendable = spendAccounts.sumOf { it.spendable }
        val credit = accounts.filter { it.role == AccountRole.CREDIT }
        val balances = Balances(
            cash = totalSpendable,
            savings = accounts.filter { it.role == AccountRole.SAVINGS }.sumOf { it.current ?: it.available ?: 0.0 },
            creditOwed = credit.sumOf { it.owed },
            creditLimit = credit.sumOf { it.limit ?: 0.0 },
            loansOwed = accounts.filter { it.role == AccountRole.LOAN }.sumOf { it.owed },
        )

        val thisMonth = flow(txns, c, month, today)
        val history = (5 downTo 1).map { flow(txns, c, month.minusMonths(it.toLong())) } + thisMonth
        val incomeSources = run {
            val m = HashMap<String, Double>()
            txns.filter { it.date in monthStart..today && c.kind(it) == Kind.INCOME }
                .forEach { m[it.effectiveCategory] = (m[it.effectiveCategory] ?: 0.0) - it.amount }
            m.map { CategorySpend(it.key, it.value, 0) }.sortedByDescending { it.amount }
        }

        val ninetyAgo = today.minusDays(90)
        val loans = accounts.filter { it.role == AccountRole.LOAN }.map { loan ->
            val payments = txns.filter { c.kind(it) == Kind.LOAN_PAYMENT && c.counterpartOf(it)?.id == loan.id }
            LoanStatus(
                account = loan,
                owed = loan.owed,
                paidThisMonth = payments.filter { it.date >= monthStart }.sumOf { it.amount },
                monthlyPayment = payments.filter { it.date > ninetyAgo }.sumOf { it.amount } / 3,
            )
        }.sortedByDescending { it.owed }

        val safe = if (spendAccounts.isEmpty()) null else {
            val horizon = nextPayday?.nextDate?.takeIf { it > today } ?: month.atEndOfMonth().plusDays(1)
            val due = outgoing.flatMap { s -> Recurring.occurrencesBetween(s, today, horizon).map { s to it } }
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
                "${money(safe.billsBeforeHorizon)} in expected bills, loan payments and savings transfers before $until, but only ${money(safe.spendableNow)} available. Short by ${money(-safe.amount)}."))
            else add(Insight("safe", if (perDay < 15) Severity.WARN else Severity.GOOD, "${money(perDay)}/day until $until",
                "${money(safe.spendableNow)} available minus ${money(safe.billsBeforeHorizon)} set aside for bills, loans and savings leaves ${money(safe.amount)} to spend."))
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
            "Low balance in ${a.displayName}", "${money(a.spendable)} available."))

        // 11. Subscriptions summary.
        val subs = bills.filter { it.cadence == Cadence.MONTHLY && it.category in setOf(Categories.ENTERTAINMENT, Categories.GENERAL_SERVICES, Categories.GENERAL_MERCHANDISE) }
        if (subs.size >= 2) add(Insight("subs", Severity.INFO, "${subs.size} subscriptions cost ${money(subs.sumOf { it.monthlyAmount })}/month",
            subs.sortedByDescending { it.typicalAmount }.take(5).joinToString(", ") { "${it.name} ${money(it.typicalAmount)}" }))

        // 12. Next payday.
        nextPayday?.let {
            add(Insight("payday", Severity.INFO, "Next paycheck around ${it.nextDate.format(dayFmt)}",
                "Usually ${money(it.typicalAmount)}, ${it.cadence.label}."))
        }

        // 13. Where this month's income went.
        if (incomeMtd > 0 && day >= 10) {
            val f = thisMonth
            val n = f.leftOver
            add(Insight("net", if (n >= 0) Severity.GOOD else Severity.WARN,
                if (n >= 0) "${money(n)} of this month's income is unassigned" else "Outflows are ${money(-n)} more than income",
                "${money(f.income)} in → ${money(f.spending)} spent, ${money(f.loanPayments)} to loans, ${money(f.saved)} saved."))
        }

        // 14. Savings.
        val saved = thisMonth.saved
        when {
            saved < -1 -> add(Insight("savings", Severity.WARN, "Pulled ${money(-saved)} out of savings this month",
                "Savings balance is ${money(balances.savings)}."))
            saved > 1 -> add(Insight("savings", Severity.GOOD, "Saved ${money(saved)} this month" +
                (thisMonth.savingsRate?.let { " (${pct(it)} of income)" } ?: ""),
                "Savings balance is ${money(balances.savings)}."))
        }
        if (savingsGoal != null && savingsGoal > 0) {
            val frac = saved / savingsGoal
            val expected = savingsGoal * day / month.lengthOfMonth()
            add(Insight("savings-goal", when {
                frac >= 1 -> Severity.GOOD
                saved < expected * 0.5 && day >= 15 -> Severity.WARN
                else -> Severity.INFO
            }, "Savings goal: ${money(maxOf(saved, 0.0))} of ${money(savingsGoal)}",
                if (frac >= 1) "Goal reached for ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }}."
                else "${money(savingsGoal - maxOf(saved, 0.0))} to go this month."))
        } else {
            // Compare with your own track record.
            val past = history.dropLast(1).filter { it.income > 0 }
            if (past.size >= 3 && saved <= 1 && day >= 20) {
                val avg = past.sumOf { it.saved } / past.size
                if (avg > 25) add(Insight("savings-behind", Severity.INFO, "Nothing saved yet this month",
                    "You've averaged ${money(avg)}/month into savings recently."))
            }
        }

        // 15. Credit cards.
        for (a in credit) {
            val u = a.utilization ?: continue
            if (u >= 0.3) add(Insight("util-${a.id}", if (u >= 0.9) Severity.ALERT else Severity.WARN,
                "${a.displayName} is at ${pct(u)} of its limit",
                "${money(a.owed)} owed of ${money(a.limit ?: 0.0)}. Keeping it under 30% helps your credit score."))
        }

        // 16. Loans.
        for (l in loans) {
            val months = l.monthsLeft
            if (months != null) add(Insight("loan-${l.account.id}", Severity.INFO,
                "${l.account.displayName}: ${money(l.owed)} left",
                "Paying about ${money(l.monthlyPayment)}/month — roughly $months more payment${if (months == 1) "" else "s"}, not counting interest" +
                    (if (l.paidThisMonth > 0) ". ${money(l.paidThisMonth)} paid this month." else ".")))
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
            thisMonth = thisMonth,
            history = history,
            balances = balances,
            loans = loans,
            incomeSources = incomeSources,
            savingsGoal = savingsGoal,
        )
    }
}
