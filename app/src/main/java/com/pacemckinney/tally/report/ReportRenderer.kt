package com.pacemckinney.tally.report

import com.pacemckinney.tally.engine.AccountRole
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.InsightEngine
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.ObligationGroup
import com.pacemckinney.tally.engine.Report
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Text style for the report. Colours are ARGB ints so this file has no Android dependency. */
data class Style(val size: Float, val bold: Boolean = false, val color: Int = INK)

const val INK = 0xFF1B1F1D.toInt()
const val MUTED = 0xFF5F6A64.toInt()
const val RULE = 0xFFD5DBD7.toInt()
const val ACCENT = 0xFF14724A.toInt()
const val TINT = 0xFFEEF5F0.toInt()
const val WARN = 0xFFA15C00.toInt()
const val BAD = 0xFFB3261E.toInt()

/** The few drawing operations the report needs; implemented by Android's PdfDocument. */
interface Surface {
    val width: Float
    val height: Float
    fun text(s: String, x: Float, y: Float, style: Style)
    fun measure(s: String, style: Style): Float
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.75f)
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    /** Finish the current page and start a new one. */
    fun newPage()
}

data class ReportOptions(
    val name: String = "",
    val institution: String = "United Credit Union",
    val includeTransactions: Boolean = false,
)

/** Lays out a [Report] onto a series of Letter-sized pages. */
class ReportRenderer(private val s: Surface, private val r: Report, private val o: ReportOptions) {
    private val m = 46f
    private val right get() = s.width - m
    private val contentW get() = s.width - 2 * m
    private var y = m
    private var page = 1

    private val h1 = Style(20f, bold = true)
    private val h2 = Style(12.5f, bold = true, color = ACCENT)
    private val body = Style(9.5f)
    private val bodyB = Style(9.5f, bold = true)
    private val small = Style(8f, color = MUTED)
    private val smallB = Style(8f, bold = true, color = MUTED)

    private val dFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val mFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private fun money(v: Double) = InsightEngine.money(v)
    private fun pct(v: Double?) = if (v == null) "—" else "${(v * 100).toInt()}%"

    fun render() {
        header()
        keyFigures()
        r.purchase?.let { purchase() }
        income()
        obligations()
        debtsAndAssets()
        monthByMonth()
        categories()
        if (o.includeTransactions) transactions()
        notes()
        footer()
    }

    // ---- layout helpers ----

    private fun need(h: Float) {
        if (y + h > s.height - m - 18) {
            footer(); s.newPage(); page++; y = m
        }
    }

    private fun footer() {
        val fy = s.height - m + 14
        s.line(m, fy - 12, right, fy - 12, RULE)
        s.text("Prepared by the account holder with Tally · not a bank-issued statement", m, fy, small)
        val p = "Page $page"
        s.text(p, right - s.measure(p, small), fy, small)
    }

    private fun textRight(t: String, xRight: Float, yy: Float, st: Style) = s.text(t, xRight - s.measure(t, st), yy, st)

    private fun fit(t: String, maxW: Float, st: Style): String {
        if (s.measure(t, st) <= maxW) return t
        var e = t
        while (e.isNotEmpty() && s.measure("$e…", st) > maxW) e = e.dropLast(1)
        return "$e…"
    }

    private fun wrap(t: String, maxW: Float, st: Style): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (w in t.split(' ')) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (s.measure(next, st) > maxW && cur.isNotEmpty()) { out += cur; cur = w } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun paragraph(t: String, st: Style = body, gap: Float = 4f) {
        for (l in wrap(t, contentW, st)) { need(st.size + 4); y += st.size + 3; s.text(l, m, y, st) }
        y += gap
    }

    private fun section(title: String) {
        need(60f)
        y += 18
        s.text(title, m, y, h2)
        y += 6
        s.line(m, y, right, y, ACCENT, 1f)
        y += 4
    }

    /** One table row. Columns are (text, width fraction, right-aligned). */
    private fun row(cols: List<Triple<String, Float, Boolean>>, st: Style = body, shade: Boolean = false, rule: Boolean = true) {
        val rh = st.size + 9
        need(rh)
        if (shade) s.rect(m, y, contentW, rh, TINT)
        var x = m + 4
        val base = y + rh - 6
        for ((t, frac, alignRight) in cols) {
            val w = contentW * frac - 8
            val shown = fit(t, w, st)
            if (alignRight) textRight(shown, x + w, base, st) else s.text(shown, x, base, st)
            x += contentW * frac
        }
        y += rh
        if (rule) s.line(m, y, right, y, RULE, 0.5f)
    }

    private fun c(t: String, f: Float, r: Boolean = false) = Triple(t, f, r)

    // ---- sections ----

    private fun header() {
        s.rect(0f, 0f, s.width, 6f, ACCENT)
        y = m + 8
        s.text("Financial Summary", m, y, h1)
        val gen = "Prepared ${r.generated.format(dFmt)}"
        textRight(gen, right, y, small)
        y += 16
        val who = listOfNotNull(o.name.takeIf { it.isNotBlank() }, o.institution).joinToString(" · ")
        s.text(who, m, y, bodyB)
        y += 13
        val span = if (r.months.isEmpty()) "No complete months of history yet"
        else "Covers ${r.from.format(dFmt)} – ${r.to.format(dFmt)} (${r.months.size} full month${if (r.months.size == 1) "" else "s"}), " +
            "with balances as of ${r.generated.format(dFmt)}"
        s.text(span, m, y, small)
        y += 10
    }

    private fun keyFigures() {
        y += 12
        val boxes = listOf(
            "Avg. monthly take-home" to money(r.avgIncome),
            "Fixed obligations / mo" to money(r.fixedMonthly),
            "Debt-to-income" to pct(r.dti),
            "Avg. left over / mo" to money(r.avgLeftOver),
        )
        val gap = 8f
        val w = (contentW - gap * 3) / 4
        val bh = 50f
        need(bh)
        boxes.forEachIndexed { i, (label, value) ->
            val x = m + i * (w + gap)
            s.rect(x, y, w, bh, TINT)
            s.text(label, x + 9, y + 16, small)
            val vs = Style(15f, bold = true, color = if (i == 3 && r.avgLeftOver < 0) BAD else INK)
            s.text(value, x + 9, y + 38, vs)
        }
        y += bh + 4
        s.text("Take-home = deposits after taxes and payroll deductions. Debt-to-income = housing + debt payments ÷ take-home.",
            m, y + 9, small)
        y += 12
    }

    private fun purchase() {
        val p = r.purchase ?: return
        section("Planned purchase: ${p.label.ifBlank { "estimate" }}")
        val cols = listOf(0.5f, 0.5f)
        val left = listOf(
            "Price" to money(p.price),
            "Down payment" to money(p.downPayment),
            "Amount financed" to money(p.financed),
            "Rate / term" to if (p.termMonths > 0) "${"%.2f".format(Locale.US, p.aprPercent)}% APR · ${p.termMonths} months" else "Paid in cash",
        )
        val rightCol = listOf(
            "Monthly payment" to money(p.monthlyPayment),
            "Other new monthly costs" to money(p.extraMonthly),
            "Total interest" to money(p.totalInterest),
            "Total new monthly cost" to money(p.monthlyCost),
        )
        for (i in left.indices) {
            row(listOf(c(left[i].first, 0.25f), c(left[i].second, 0.25f, true), c("   " + rightCol[i].first, 0.3f), c(rightCol[i].second, 0.2f, true)),
                if (i == 3) bodyB else body)
        }
        y += 6
        row(listOf(c("Effect on your budget", 0.4f), c("Today", 0.3f, true), c("With this purchase", 0.3f, true)), smallB, shade = true)
        row(listOf(c("Debt-to-income", 0.4f), c(pct(r.dti), 0.3f, true), c(pct(r.dtiWithPurchase), 0.3f, true)))
        row(listOf(c("Fixed obligations / month", 0.4f), c(money(r.fixedMonthly), 0.3f, true), c(money(r.fixedMonthly + p.monthlyCost), 0.3f, true)))
        val after = r.leftOverWithPurchase ?: 0.0
        row(listOf(c("Average left over / month", 0.4f), c(money(r.avgLeftOver), 0.3f, true), c(money(after), 0.3f, true)))
        row(listOf(c("Down payment from cash + savings", 0.4f), c(money(r.balances.cash + r.balances.savings) + " available", 0.3f, true),
            c(if (r.downPaymentCovered == true) "Covered" else "Short ${money(p.downPayment - r.balances.cash - r.balances.savings)}", 0.3f, true)))
        y += 4
        val dti = r.dtiWithPurchase
        val verdict = when {
            r.avgIncome <= 0 -> "Not enough income history to judge affordability."
            after < 0 -> "On recent averages this payment would leave you about ${money(-after)} short each month."
            dti != null && dti > 0.43 -> "Debt-to-income would be above 43%, a common lender cutoff; expect pushback or a higher rate."
            dti != null && dti > 0.36 -> "Debt-to-income would be 36–43%: usually approvable, but tight."
            else -> "Fits within common guidelines (debt-to-income at or under 36%), leaving about ${money(after)} a month on recent averages."
        }
        paragraph(verdict, Style(9.5f, bold = true, color = if (after < 0 || (dti ?: 0.0) > 0.43) BAD else if ((dti ?: 0.0) > 0.36) WARN else ACCENT))
    }

    private fun income() {
        section("Income")
        if (r.incomeSources.isEmpty()) paragraph("No regular paychecks detected in this period.", small)
        else {
            row(listOf(c("Source", 0.4f), c("Schedule", 0.2f), c("Typical deposit", 0.2f, true), c("Monthly equivalent", 0.2f, true)), smallB, shade = true)
            for (src in r.incomeSources) row(listOf(c(src.name, 0.4f), c(src.cadence.label, 0.2f), c(money(src.typicalAmount), 0.2f, true), c(money(src.monthlyAmount), 0.2f, true)))
        }
        row(listOf(c("Average total deposits per month (all income)", 0.8f), c(money(r.avgIncome), 0.2f, true)), bodyB, rule = false)
    }

    private fun obligations() {
        section("Monthly obligations")
        if (r.obligations.isEmpty()) { paragraph("No recurring bills or payments detected yet.", small); return }
        for (g in ObligationGroup.entries) {
            val lines = r.obligations.filter { it.group == g }
            if (lines.isEmpty()) continue
            row(listOf(c(g.label, 0.45f), c("", 0.35f), c(money(lines.sumOf { it.monthly }), 0.2f, true)), smallB, shade = true)
            for (l in lines) row(listOf(c(l.name, 0.45f), c(l.note, 0.35f), c(money(l.monthly), 0.2f, true)))
        }
        row(listOf(c("Total fixed obligations", 0.8f), c(money(r.fixedMonthly), 0.2f, true)), bodyB, rule = false)
    }

    private fun debtsAndAssets() {
        section("Balances")
        row(listOf(c("Account", 0.38f), c("Type", 0.16f), c("Balance", 0.2f, true), c("Details", 0.26f, true)), smallB, shade = true)
        val order = listOf(AccountRole.SPENDING, AccountRole.SAVINGS, AccountRole.CREDIT, AccountRole.LOAN)
        for (a in r.accounts.sortedBy { order.indexOf(it.role) }) {
            val name = a.displayName + (a.mask?.let { "  ••$it" } ?: "")
            val (bal, extra) = when (a.role) {
                AccountRole.CREDIT -> money(a.owed) + " owed" to (a.limit?.let { "${pct(a.utilization)} of ${money(it)} limit" } ?: "")
                AccountRole.LOAN -> money(a.owed) + " owed" to (r.loans.firstOrNull { it.account.id == a.id }?.monthsLeft?.let { "~$it payments left" } ?: "")
                else -> money(a.current ?: a.available ?: 0.0) to (a.available?.takeIf { it != a.current }?.let { "${money(it)} avail." } ?: "")
            }
            row(listOf(c(name, 0.38f), c(a.role.label.substringBefore(" ("), 0.16f), c(bal, 0.2f, true), c(extra, 0.26f, true)))
        }
        val b = r.balances
        row(listOf(c("Cash + savings ${money(b.cash + b.savings)}   ·   Owed ${money(b.creditOwed + b.loansOwed)}", 0.8f),
            c("Net ${money(b.netWorth)}", 0.2f, true)), bodyB, rule = false)
    }

    private fun monthByMonth() {
        if (r.months.isEmpty()) return
        section("Month by month")
        val w = listOf(0.2f, 0.16f, 0.16f, 0.16f, 0.16f, 0.16f)
        row(listOf(c("Month", w[0]), c("Income", w[1], true), c("Spending", w[2], true), c("Debt payments", w[3], true),
            c("Saved", w[4], true), c("Left over", w[5], true)), smallB, shade = true)
        for (mf in r.months) row(listOf(c(mf.month.format(mFmt), w[0]), c(money(mf.income), w[1], true), c(money(mf.spending), w[2], true),
            c(money(mf.loanPayments), w[3], true), c(money(mf.saved), w[4], true), c(money(mf.leftOver), w[5], true)))
        row(listOf(c("Average", w[0]), c(money(r.avgIncome), w[1], true), c(money(r.avgSpending), w[2], true),
            c(money(r.avgLoanPayments), w[3], true), c(money(r.avgSaved), w[4], true), c(money(r.avgLeftOver), w[5], true)), bodyB, rule = false)
    }

    private fun categories() {
        if (r.spendingByCategory.isEmpty()) return
        section("Average monthly spending by category")
        val top = r.spendingByCategory.take(10)
        val max = top.first().amount
        for (cs in top) {
            need(16f)
            val yy = y + 11
            s.text(fit(Categories.label(cs.category), contentW * 0.32f, body), m + 4, yy, body)
            val bx = m + contentW * 0.34f
            val bw = contentW * 0.48f
            s.rect(bx, y + 4, bw, 8f, TINT)
            s.rect(bx, y + 4, (bw * (cs.amount / max)).toFloat(), 8f, ACCENT)
            textRight(money(cs.amount), right - 4, yy, body)
            y += 16
        }
    }

    private fun transactions() {
        if (r.transactions.isEmpty()) return
        section("Transactions (${r.transactions.size})")
        row(listOf(c("Date", 0.12f), c("Description", 0.48f), c("Counted as", 0.24f), c("Amount", 0.16f, true)), smallB, shade = true)
        val st = Style(8f)
        for ((t, k) in r.transactions) {
            val label = when (k) {
                Kind.SPEND, Kind.REFUND -> Categories.label(t.effectiveCategory)
                else -> k.label
            }
            val amt = (if (t.amount < 0) "+" else "−") + money(abs(t.amount))
            row(listOf(c(t.date.format(DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US)), 0.12f), c(t.displayName, 0.48f), c(label, 0.24f), c(amt, 0.16f, true)), st)
        }
    }

    private fun notes() {
        y += 14
        paragraph(
            "How this was prepared: figures come from ${o.institution} account activity retrieved through Plaid and are grouped by the account " +
                "holder's own categories. Transfers between the holder's accounts are not counted as income or spending. " +
                "Recurring items are detected from repeating payments; credit card minimums are estimated at 2% of the balance (min. \$25). " +
                "Averages use complete calendar months only. Verify against official statements before relying on these numbers.",
            small,
        )
    }

    companion object {
        fun fileName(today: LocalDate) = "Tally-Financial-Summary-$today.pdf"
    }
}
