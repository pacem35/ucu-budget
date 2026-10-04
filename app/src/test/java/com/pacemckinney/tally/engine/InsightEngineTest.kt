package com.pacemckinney.tally.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightEngineTest {
    private val today = LocalDate.of(2026, 10, 20)
    private var n = 0

    private fun t(
        date: LocalDate, amount: Double, name: String, cat: String,
        account: String = "chk", detailed: String? = null, user: String? = null,
    ) = Txn("t${n++}", account, amount, date, name, null, cat, detailed, false, user)

    private val accounts = listOf(
        Account("chk", "item", "Checking", "1234", "depository", "checking", 2600.0, 2500.0),
        Account("sav", "item", "Savings", "9999", "depository", "savings", 5000.0, 5000.0),
    )

    private fun history(): List<Txn> {
        val list = ArrayList<Txn>()
        // Biweekly paycheck on Fridays.
        var pay = LocalDate.of(2026, 6, 5)
        while (pay <= today) { list += t(pay, -1850.0, "CITY OF FULTON PAYROLL", Categories.INCOME); pay = pay.plusDays(14) }
        // Rent on the 1st, Netflix on the 12th (price rise in October).
        for (m in 6..10) {
            list += t(LocalDate.of(2026, m, 1), 750.0, "PROPERTY MGMT RENT", Categories.RENT_AND_UTILITIES)
            if (LocalDate.of(2026, m, 12) <= today)
                list += t(LocalDate.of(2026, m, 12), if (m == 10) 17.99 else 15.49, "NETFLIX.COM 866-579", Categories.ENTERTAINMENT)
            // Moving money to savings is not spending.
            list += t(LocalDate.of(2026, m, 2), 200.0, "TRANSFER TO SHARE 9999", Categories.TRANSFER_OUT)
            list += t(LocalDate.of(2026, m, 2), -200.0, "TRANSFER FROM SHARE 1234", Categories.TRANSFER_IN, account = "sav")
        }
        // Everyday food spending, more in October than before.
        var d = LocalDate.of(2026, 6, 1)
        var i = 0
        while (d <= today) {
            val base = if (d.monthValue == 10) 28.0 else 14.0
            list += t(d, base + (i % 5) * 3, "CASEYS #${3000 + i}", Categories.FOOD_AND_DRINK)
            d = d.plusDays(2); i++
        }
        return list
    }

    @Test fun internalTransfersAreIgnored() {
        val txns = history()
        val c = Classifier(txns)
        val transfers = txns.filter { it.category == Categories.TRANSFER_OUT || it.category == Categories.TRANSFER_IN }
        assertTrue(transfers.all { c.kind(it) == Kind.INTERNAL })
    }

    @Test fun unmatchedTransferOutCountsAsSpending() {
        val venmo = t(today, 40.0, "VENMO PAYMENT", Categories.TRANSFER_OUT)
        assertEquals(Kind.SPEND, Classifier(listOf(venmo)).kind(venmo))
    }

    @Test fun refundReducesSpending() {
        val buy = t(today, 100.0, "WALMART", Categories.GENERAL_MERCHANDISE)
        val back = t(today, -30.0, "WALMART RETURN", Categories.GENERAL_MERCHANDISE)
        val c = Classifier(listOf(buy, back))
        assertEquals(Kind.REFUND, c.kind(back))
        val s = InsightEngine.spendByCategory(listOf(buy, back), c, today, today)
        assertEquals(70.0, s.single().amount, 0.001)
    }

    @Test fun excludedIsIgnored() {
        val x = t(today, 500.0, "SOMETHING", Categories.GENERAL_MERCHANDISE, user = Categories.EXCLUDED)
        assertEquals(Kind.EXCLUDED, Classifier(listOf(x)).kind(x))
    }

    @Test fun detectsPaycheckRentAndNetflix() {
        val txns = history()
        val streams = Recurring.detect(txns, Classifier(txns), today)
        val pay = streams.single { it.isIncome }
        assertEquals(Cadence.BIWEEKLY, pay.cadence)
        assertEquals(1850.0, pay.typicalAmount, 0.01)
        assertTrue(pay.nextDate > today)
        val rent = streams.single { it.name.contains("RENT") }
        assertEquals(Cadence.MONTHLY, rent.cadence)
        assertEquals(LocalDate.of(2026, 11, 1), rent.nextDate)
        val nf = streams.single { it.name.contains("NETFLIX") }
        assertEquals(17.99, nf.lastAmount, 0.001)
        // Gas station runs vary too much in timing/amount to be a "bill".
        assertTrue(streams.none { it.name.contains("CASEYS") })
    }

    @Test fun snapshotNumbersAndInsights() {
        val txns = history()
        val s = InsightEngine.build(txns, accounts, listOf(Budget(Categories.FOOD_AND_DRINK, 250.0)), today)

        // October: rent 750 + netflix 17.99 + food; transfers excluded.
        val food = txns.filter { it.date.monthValue == 10 && it.category == Categories.FOOD_AND_DRINK }.sumOf { it.amount }
        assertEquals(750.0 + 17.99 + food, s.spendMtd, 0.01)
        // Paychecks land Oct 9 and Oct 23; only one is in by the 20th.
        assertEquals(1850.0, s.incomeMtd, 0.01)
        assertEquals(20, s.cumulativeThisMonth.size)
        assertEquals(s.spendMtd, s.cumulativeThisMonth.last(), 0.01)

        val safe = assertNotNullAndGet(s.safeToSpend)
        assertEquals(2500.0, safe.spendableNow, 0.01) // checking only
        assertTrue(safe.horizonIsPayday)

        val ids = s.insights.map { it.id }
        assertTrue("budget warning expected: $ids", ids.contains("budget-FOOD_AND_DRINK"))
        assertTrue("netflix price rise expected: $ids", ids.any { it.startsWith("price-") })
        assertTrue("food spike expected: $ids", ids.contains("spike-FOOD_AND_DRINK"))
        assertTrue(ids.contains("payday"))
        assertTrue(s.projectedMonthSpend > s.spendMtd)
        // Alerts/warnings sort ahead of info.
        assertTrue(s.insights.first().severity != Severity.INFO)
    }

    @Test fun duplicateAndLargeCharges() {
        val txns = history() + listOf(
            t(today, 64.12, "DOLLAR GENERAL", Categories.GENERAL_MERCHANDISE),
            t(today, 64.12, "DOLLAR GENERAL", Categories.GENERAL_MERCHANDISE),
            t(today.minusDays(1), 480.0, "O'REILLY AUTO", Categories.GENERAL_MERCHANDISE),
        )
        val ids = InsightEngine.build(txns, accounts, emptyList(), today).insights.map { it.id }
        assertTrue(ids.any { it.startsWith("dup-") })
        assertTrue(ids.any { it.startsWith("large-") })
    }

    @Test fun lowBalanceAlert() {
        val poor = listOf(Account("chk", "item", "Checking", "1234", "depository", "checking", 40.0, 35.0))
        val s = InsightEngine.build(history(), poor, emptyList(), today)
        assertEquals(Severity.ALERT, s.insights.first().severity)
        assertTrue(s.insights.any { it.id == "low-chk" })
    }

    @Test fun recurringKeyNormalizes() {
        assertEquals(
            Recurring.key(t(today, 1.0, "NETFLIX.COM 866-579 #4421", "X")),
            Recurring.key(t(today, 1.0, "Netflix.com 4419", "X")),
        )
    }

    @Test fun emptyDataDoesNotCrash() {
        val s = InsightEngine.build(emptyList(), emptyList(), emptyList(), today)
        assertEquals(0.0, s.spendMtd, 0.0)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
