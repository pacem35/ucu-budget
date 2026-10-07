package com.pacemckinney.tally.engine

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.pow

/** Something you're pricing out: a car, a TV on a payment plan, an apartment. */
data class PlannedPurchase(
    val label: String,
    val price: Double,
    val downPayment: Double = 0.0,
    val aprPercent: Double = 0.0,
    /** 0 = paying cash, no monthly payment. */
    val termMonths: Int = 0,
    /** Other new monthly costs that come with it (insurance, utilities, upkeep). */
    val extraMonthly: Double = 0.0,
) {
    val financed: Double get() = max(0.0, price - downPayment)

    /** Standard amortized payment. */
    val monthlyPayment: Double
        get() {
            if (termMonths <= 0 || financed <= 0) return 0.0
            val r = aprPercent / 100.0 / 12.0
            return if (r == 0.0) financed / termMonths else financed * r / (1 - (1 + r).pow(-termMonths))
        }

    val totalInterest: Double get() = max(0.0, monthlyPayment * termMonths - financed)
    val monthlyCost: Double get() = monthlyPayment + extraMonthly
}

enum class ObligationGroup(val label: String) { HOUSING("Housing"), DEBT("Debt payments"), BILLS("Other recurring bills") }

data class ObligationLine(
    val group: ObligationGroup,
    val name: String,
    val monthly: Double,
    val note: String,
)

data class Report(
    val generated: LocalDate,
    val months: List<MonthFlow>,
    val avgIncome: Double,
    val avgSpending: Double,
    val avgLoanPayments: Double,
    val avgSaved: Double,
    val avgLeftOver: Double,
    val incomeSources: List<RecurringStream>,
    val obligations: List<ObligationLine>,
    val accounts: List<Account>,
    val balances: Balances,
    val loans: List<LoanStatus>,
    /** Average per month over the report period. */
    val spendingByCategory: List<CategorySpend>,
    val purchase: PlannedPurchase?,
    /** Transactions in the period with what each counted as (for the optional appendix). */
    val transactions: List<Pair<Txn, Kind>>,
) {
    val from: LocalDate get() = months.firstOrNull()?.month?.atDay(1) ?: generated
    val to: LocalDate get() = months.lastOrNull()?.month?.atEndOfMonth() ?: generated
    val housingMonthly: Double get() = obligations.filter { it.group == ObligationGroup.HOUSING }.sumOf { it.monthly }
    val debtMonthly: Double get() = obligations.filter { it.group == ObligationGroup.DEBT }.sumOf { it.monthly }
    val billsMonthly: Double get() = obligations.filter { it.group == ObligationGroup.BILLS }.sumOf { it.monthly }
    val fixedMonthly: Double get() = housingMonthly + debtMonthly + billsMonthly

    /** Debt-to-income: housing + debt payments as a share of take-home income. */
    val dti: Double? get() = if (avgIncome > 0) (housingMonthly + debtMonthly) / avgIncome else null
    val housingRatio: Double? get() = if (avgIncome > 0) housingMonthly / avgIncome else null

    val dtiWithPurchase: Double?
        get() = if (avgIncome > 0 && purchase != null) (housingMonthly + debtMonthly + purchase.monthlyPayment) / avgIncome else null
    val leftOverWithPurchase: Double? get() = purchase?.let { avgLeftOver - it.monthlyCost }
    val downPaymentCovered: Boolean? get() = purchase?.let { it.downPayment <= balances.cash + balances.savings }
}

object ReportBuilder {
    private val HOUSING = Regex("""(?i)\brent\b|mortgage|apartment|apts?\b|property|manage|realty|leasing|hoa\b""")

    /**
     * Summary of the last [monthsBack] full calendar months (the current, unfinished month is
     * left out so averages aren't dragged down by a partial month).
     */
    fun build(
        txns: List<Txn>,
        accounts: List<Account>,
        today: LocalDate,
        monthsBack: Int = 3,
        purchase: PlannedPurchase? = null,
    ): Report {
        val snap = InsightEngine.build(txns, accounts, emptyList(), today)
        val c = Classifier(txns, accounts)
        val visible = txns.filter { c.kind(it) != Kind.HIDDEN }
        val earliest = visible.minOfOrNull { it.date }?.let { YearMonth.from(it) }
        val current = YearMonth.from(today)
        val months = (monthsBack downTo 1).map { current.minusMonths(it.toLong()) }
            .filter { earliest == null || it >= earliest }
            .map { InsightEngine.flow(visible, c, it) }
        val n = months.size.coerceAtLeast(1)
        fun avg(f: (MonthFlow) -> Double) = months.sumOf(f) / n

        val from = months.firstOrNull()?.month?.atDay(1) ?: today
        val to = months.lastOrNull()?.month?.atEndOfMonth() ?: today

        // Obligations: what you're committed to paying every month.
        val lines = ArrayList<ObligationLine>()
        // Frequent shopping, food and gas runs repeat too, but they aren't obligations. Monthly
        // charges in those categories (a subscription billed as "shopping") still count.
        val discretionary = setOf(Categories.FOOD_AND_DRINK, Categories.GENERAL_MERCHANDISE, Categories.TRANSPORTATION)
        val bills = snap.recurring.filter { it.kind == Kind.SPEND && (it.category !in discretionary || it.cadence == Cadence.MONTHLY) }
        for (b in bills) {
            val housing = b.detailed?.contains("RENT_AND_UTILITIES_RENT") == true ||
                (b.category == Categories.RENT_AND_UTILITIES && HOUSING.containsMatchIn(b.name)) ||
                (b.category == Categories.RENT_AND_UTILITIES && b.monthlyAmount >= 400 && b.detailed?.contains("UTILIT") != true)
            lines += ObligationLine(
                if (housing) ObligationGroup.HOUSING else ObligationGroup.BILLS,
                b.name, b.monthlyAmount, b.cadence.label,
            )
        }
        // Tracked loans: average actual payment over the last 3 months.
        for (l in snap.loans) if (l.monthlyPayment > 1) {
            lines += ObligationLine(ObligationGroup.DEBT, l.account.displayName, l.monthlyPayment,
                "${InsightEngine.money(l.owed)} balance")
        }
        // Outside debts paid on a schedule (PayPal Pay Monthly, other lenders' cards).
        val trackedLoanIds = snap.loans.map { it.account.id }.toSet()
        val externalKeys = visible.filter { c.kind(it) == Kind.LOAN_PAYMENT && c.counterpartOf(it)?.id !in trackedLoanIds }
            .map { Recurring.key(it) }.toSet()
        for (s in snap.recurring.filter { it.kind == Kind.LOAN_PAYMENT && it.key in externalKeys }) {
            lines += ObligationLine(ObligationGroup.DEBT, s.name, s.monthlyAmount, s.cadence.label)
        }
        // Credit cards: lenders count the minimum payment; estimate it if there's a balance.
        for (a in accounts.filter { it.included && it.role == AccountRole.CREDIT && it.owed > 0.5 }) {
            val min = minOf(a.owed, max(25.0, a.owed * 0.02))
            lines += ObligationLine(ObligationGroup.DEBT, a.displayName, min,
                "est. minimum on ${InsightEngine.money(a.owed)} balance")
        }

        val byCat = InsightEngine.spendByCategory(visible, c, from, to)
            .map { it.copy(amount = it.amount / n) }

        return Report(
            generated = today,
            months = months,
            avgIncome = avg { it.income },
            avgSpending = avg { it.spending },
            avgLoanPayments = avg { it.loanPayments },
            avgSaved = avg { it.saved },
            avgLeftOver = avg { it.leftOver },
            incomeSources = snap.recurring.filter { it.isIncome && it.typicalAmount >= 50 },
            obligations = lines.sortedWith(compareBy({ it.group.ordinal }, { -it.monthly })),
            accounts = accounts.filter { it.included },
            balances = snap.balances,
            loans = snap.loans,
            spendingByCategory = byCat,
            purchase = purchase,
            transactions = visible.filter { it.date in from..to }.sortedBy { it.date }.map { it to c.kind(it) },
        )
    }
}
