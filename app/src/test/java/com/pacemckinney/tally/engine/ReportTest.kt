package com.pacemckinney.tally.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReportTest {
    private val today = LocalDate.of(2026, 10, 7)
    private var n = 0
    private fun t(date: LocalDate, amount: Double, name: String, cat: String, account: String = "chk", detailed: String? = null) =
        Txn("t${n++}", account, amount, date, name, null, cat, detailed, false, null, null, name)

    private val accounts = listOf(
        Account("chk", "i", "Main-Checking", "0009", "depository", "checking", 1000.0, 900.0),
        Account("sav", "i", "Share/Savings", "0001", "depository", "savings", 2900.0, 2900.0),
        Account("visa", "i", "Visa", "3701", "credit", "credit card", 346.28, 153.72),
        Account("car", "i", "2010 Honda Civic", "1201", "loan", "auto", 3200.0, 0.0),
        Account("other", "i", "Checking - Reward", "0079", "depository", "checking", 1500.0, 1400.0, included = false),
    )

    private fun history(): List<Txn> {
        val l = ArrayList<Txn>()
        var pay = LocalDate.of(2026, 5, 1)
        while (pay <= today) { l += t(pay, -1650.0, "CITY OF FULTON PAYROLL", Categories.INCOME); pay = pay.plusDays(14) }
        for (m in 5..10) {
            val d1 = LocalDate.of(2026, m, 1)
            if (d1 <= today) {
                l += t(d1, 850.0, "Hawthorne Manage WEB PMTS", Categories.RENT_AND_UTILITIES, detailed = "RENT_AND_UTILITIES_RENT")
                l += t(d1.plusDays(7), 220.0, "Speedpay AmerenMO", Categories.RENT_AND_UTILITIES, detailed = "RENT_AND_UTILITIES_GAS_AND_ELECTRICITY")
                l += t(d1.plusDays(3), 300.0, "Withdrawal Transfer to L1201", Categories.TRANSFER_OUT)
                for (k in 0..3) l += t(d1.plusDays(k * 7L + 2), 100.0, "WALMART", Categories.GENERAL_MERCHANDISE)
            }
        }
        // Someone else's account: must not show up anywhere.
        l += t(LocalDate.of(2026, 9, 10), 5000.0, "NOT MINE", Categories.GENERAL_MERCHANDISE, account = "other")
        return l
    }

    @Test fun paymentMath() {
        val p = PlannedPurchase("Truck", price = 22000.0, downPayment = 2000.0, aprPercent = 6.0, termMonths = 60)
        assertEquals(20000.0, p.financed, 0.001)
        assertEquals(386.66, p.monthlyPayment, 0.01)
        assertEquals(3199.36, p.totalInterest, 0.5)
        assertEquals(100.0, PlannedPurchase("TV", 1200.0, termMonths = 12).monthlyPayment, 0.001)
        assertEquals(0.0, PlannedPurchase("Cash", 500.0).monthlyPayment, 0.0)
    }

    @Test fun usesFullMonthsOnly() {
        val r = ReportBuilder.build(history(), accounts, today, monthsBack = 3)
        assertEquals(listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9)), r.months.map { it.month })
        assertEquals(LocalDate.of(2026, 7, 1), r.from)
        assertEquals(LocalDate.of(2026, 9, 30), r.to)
        // Nothing from the hidden account.
        assertTrue(r.transactions.none { it.first.accountId == "other" })
        assertTrue(r.spendingByCategory.none { it.amount > 2000 })
    }

    @Test fun obligationsAndRatios() {
        val r = ReportBuilder.build(history(), accounts, today, monthsBack = 3)
        val housing = r.obligations.filter { it.group == ObligationGroup.HOUSING }
        assertEquals(listOf("Hawthorne Manage WEB PMTS"), housing.map { it.name })
        assertTrue(r.obligations.any { it.group == ObligationGroup.BILLS && it.name.contains("Ameren") })
        // Regular shopping isn't a fixed obligation.
        assertTrue(r.obligations.none { it.name == "WALMART" })
        val debt = r.obligations.filter { it.group == ObligationGroup.DEBT }
        assertTrue("loan line: $debt", debt.any { it.name == "2010 Honda Civic" && it.monthly > 250 })
        assertTrue("card min: $debt", debt.any { it.name == "Visa" && it.monthly == 25.0 })
        assertNotNull(r.dti)
        assertEquals((r.housingMonthly + r.debtMonthly) / r.avgIncome, r.dti!!, 1e-9)
        assertTrue(r.incomeSources.any { it.cadence == Cadence.BIWEEKLY })
        // Biweekly pay: 2-3 checks a month, so the average sits between.
        assertTrue(r.avgIncome in 3300.0..4950.0)
    }

    @Test fun purchaseImpact() {
        val p = PlannedPurchase("Truck", 22000.0, 2000.0, 6.0, 60, extraMonthly = 120.0)
        val r = ReportBuilder.build(history(), accounts, today, purchase = p)
        assertEquals(r.dti!! + p.monthlyPayment / r.avgIncome, r.dtiWithPurchase!!, 1e-9)
        assertEquals(r.avgLeftOver - p.monthlyPayment - 120.0, r.leftOverWithPurchase!!, 1e-9)
        assertEquals(true, r.downPaymentCovered)
    }

    @Test fun emptyDataStillBuilds() {
        val r = ReportBuilder.build(emptyList(), emptyList(), today)
        assertEquals(0.0, r.avgIncome, 0.0)
        assertEquals(null, r.dti)
    }
}
