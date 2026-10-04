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

    // ---- Account roles: savings, loans, credit cards, hidden accounts ----

    private val fullAccounts = listOf(
        Account("chk", "item", "Main-Checking", "0009", "depository", "checking", 1005.0, 866.79),
        Account("sav", "item", "Share/Savings", "0001", "depository", "savings", 4860.0, 4859.0),
        Account("visa", "item", "******8720 3701 Visa Platinum", "3701", "credit", "credit card", 346.28, 153.72),
        Account("car", "item", "******8720 1201 2010 Honda Accord", "1201", "loan", "auto", 3086.0, 0.0),
        Account("other", "item", "Checking - Reward", "0079", "depository", "checking", 1545.0, 1481.0, included = false),
    )

    @Test fun savingsTransfersCountOnceAsSaved() {
        val out = t(today, 300.0, "TRANSFER TO SHARE", Categories.TRANSFER_OUT)
        val inn = t(today, -300.0, "TRANSFER FROM CHECKING", Categories.TRANSFER_IN, account = "sav")
        val back = t(today, 50.0, "TRANSFER TO CHECKING", Categories.TRANSFER_OUT, account = "sav")
        val backIn = t(today, -50.0, "TRANSFER FROM SHARE", Categories.TRANSFER_IN)
        val c = Classifier(listOf(out, inn, back, backIn), fullAccounts)
        assertEquals(Kind.SAVINGS, c.kind(out))
        assertEquals(Kind.INTERNAL, c.kind(inn))
        assertEquals(Kind.INTERNAL, c.kind(back))
        assertEquals(Kind.SAVINGS, c.kind(backIn))
        val f = InsightEngine.flow(listOf(out, inn, back, backIn), c, java.time.YearMonth.from(today))
        assertEquals(250.0, f.saved, 0.001)
        assertEquals(0.0, f.spending, 0.001)
        assertEquals(0.0, f.income, 0.001)
    }

    @Test fun loanPaymentGoesToLoanBucket() {
        val pay = t(today, 285.0, "LOAN PMT 1201", Categories.LOAN_PAYMENTS)
        val post = t(today.plusDays(1), -285.0, "PAYMENT RECEIVED", Categories.LOAN_PAYMENTS, account = "car")
        val interest = t(today, 12.0, "INTEREST", Categories.BANK_FEES, account = "car")
        val c = Classifier(listOf(pay, post, interest), fullAccounts)
        assertEquals(Kind.LOAN_PAYMENT, c.kind(pay))
        assertEquals("car", c.counterpartOf(pay)?.id)
        assertEquals(Kind.INTERNAL, c.kind(post))
        assertEquals(Kind.INTERNAL, c.kind(interest))
    }

    @Test fun creditCardPurchasesCountAndPaymentsDoNot() {
        val buy = t(today, 42.0, "WALMART", Categories.GENERAL_MERCHANDISE, account = "visa")
        val pay = t(today, 200.0, "VISA PAYMENT", Categories.LOAN_PAYMENTS)
        val got = t(today, -200.0, "PAYMENT THANK YOU", Categories.LOAN_PAYMENTS, account = "visa")
        val refund = t(today, -42.0, "WALMART RETURN", Categories.GENERAL_MERCHANDISE, account = "visa")
        val c = Classifier(listOf(buy, pay, got, refund), fullAccounts)
        assertEquals(Kind.SPEND, c.kind(buy))
        assertEquals(Kind.INTERNAL, c.kind(pay))
        assertEquals(Kind.INTERNAL, c.kind(got))
        assertEquals(Kind.REFUND, c.kind(refund))
    }

    @Test fun paymentToUntrackedCardIsDebtPayment() {
        val pay = t(today, 150.0, "CAPITAL ONE PAYMENT", Categories.LOAN_PAYMENTS)
        assertEquals(Kind.LOAN_PAYMENT, Classifier(listOf(pay), fullAccounts).kind(pay))
    }

    @Test fun hiddenAccountsAreIgnored() {
        val theirs = t(today, 900.0, "SOMEONE ELSES RENT", Categories.RENT_AND_UTILITIES, account = "other")
        val toThem = t(today, 100.0, "TRANSFER TO 0079", Categories.TRANSFER_OUT)
        val atThem = t(today, -100.0, "TRANSFER FROM 0009", Categories.TRANSFER_IN, account = "other")
        val txns = listOf(theirs, toThem, atThem)
        val c = Classifier(txns, fullAccounts)
        assertEquals(Kind.HIDDEN, c.kind(theirs))
        assertEquals(Kind.HIDDEN, c.kind(atThem))
        // Money you send to an account you don't track has left your world: it's spending.
        assertEquals(Kind.SPEND, c.kind(toThem))
        val s = InsightEngine.build(txns, fullAccounts, emptyList(), today)
        assertEquals(100.0, s.spendMtd, 0.001)
        assertEquals(866.79, s.totalSpendable, 0.001) // the hidden checking isn't counted
    }

    @Test fun balancesLoansAndUtilization() {
        val txns = ArrayList<Txn>()
        for (m in 7..10) {
            val d = LocalDate.of(2026, m, 5)
            txns += t(d, 285.0, "LOAN PMT 1201", Categories.LOAN_PAYMENTS)
            txns += t(d, -285.0, "PAYMENT RECEIVED", Categories.LOAN_PAYMENTS, account = "car")
        }
        val s = InsightEngine.build(txns, fullAccounts, emptyList(), today)
        assertEquals(866.79, s.balances.cash, 0.001)
        assertEquals(4860.0, s.balances.savings, 0.001)
        assertEquals(346.28, s.balances.creditOwed, 0.001)
        assertEquals(500.0, s.balances.creditLimit, 0.001)
        assertEquals(3086.0, s.balances.loansOwed, 0.001)
        val loan = s.loans.single()
        assertEquals(285.0, loan.paidThisMonth, 0.001)
        assertEquals(285.0, loan.monthlyPayment, 0.001)
        assertEquals(11, loan.monthsLeft)
        assertEquals(285.0, s.loanPaidMtd, 0.001)
        assertEquals(0.0, s.spendMtd, 0.001)
        val ids = s.insights.map { it.id }
        assertTrue("utilization warning expected: $ids", ids.contains("util-visa"))
        assertTrue(ids.contains("loan-car"))
        // The loan payment is set aside in safe-to-spend once it's a detected monthly payment.
        assertTrue(s.recurring.any { it.kind == Kind.LOAN_PAYMENT })
        assertEquals("2010 Honda Accord", fullAccounts[3].cleanName)
        assertEquals("Visa Platinum", fullAccounts[2].cleanName)
    }

    @Test fun userCanRelabelAsSavingsOrInternal() {
        val a = t(today, 100.0, "ZELLE TO ME", Categories.TRANSFER_OUT, user = Categories.SAVINGS)
        val b = t(today, 60.0, "VENMO", Categories.TRANSFER_OUT, user = Categories.INTERNAL)
        val c = Classifier(listOf(a, b), fullAccounts)
        assertEquals(Kind.SAVINGS, c.kind(a))
        assertEquals(Kind.INTERNAL, c.kind(b))
    }

    @Test fun monthFlowSplitsFourWays() {
        val txns = listOf(
            t(today, -2000.0, "PAYROLL", Categories.INCOME),
            t(today, 400.0, "GROCERY", Categories.FOOD_AND_DRINK),
            t(today, 300.0, "TO SAVINGS", Categories.TRANSFER_OUT),
            t(today, -300.0, "FROM CHECKING", Categories.TRANSFER_IN, account = "sav"),
            t(today, 285.0, "LOAN PMT", Categories.LOAN_PAYMENTS),
            t(today, -285.0, "PAYMENT", Categories.LOAN_PAYMENTS, account = "car"),
        )
        val f = InsightEngine.build(txns, fullAccounts, emptyList(), today, savingsGoal = 500.0).thisMonth
        assertEquals(2000.0, f.income, 0.001)
        assertEquals(400.0, f.spending, 0.001)
        assertEquals(300.0, f.saved, 0.001)
        assertEquals(285.0, f.loanPayments, 0.001)
        assertEquals(1015.0, f.leftOver, 0.001)
        assertEquals(0.15, f.savingsRate!!, 0.0001)
    }

    // ---- UCU account codes in descriptions ----

    private val ucuAccounts = listOf(
        Account("chk", "item", "Main-Checking", "0009", "depository", "checking", 1000.0, 900.0),
        Account("share", "item", "Share - Regular", "0001", "depository", "savings", 1.0, 0.0),
        Account("sav", "item", "Share/Savings", "0001", "depository", "savings", 2900.0, 2900.0),
        Account("visa", "item", "******8720 3701 Visa", "3701", "credit", "credit card", 300.0, 200.0),
        Account("car", "item", "******8720 1201 2010 Honda Civic", "1201", "loan", "auto", 3200.0, 0.0),
        Account("promo", "item", "******8720 2350 Promo - Unsecured", "2350", "loan", "consumer", 600.0, 0.0),
    )

    private fun u(amount: Double, original: String, account: String = "chk", cat: String = Categories.TRANSFER_OUT) =
        Txn("u${n++}", account, amount, today, original.substringBefore("  "), null, cat, null, false, null, null, original)

    @Test fun ucuLoanTransfersAreLoanPaymentsEvenWithoutTheLoanSide() {
        val honda = u(150.0, "Withdrawal Transfer to L1201")
        val promo = u(40.0, "Withdrawal Transfer to L2350")
        val visa = u(30.0, "Withdrawal Transfer to L3701")
        val c = Classifier(listOf(honda, promo, visa), ucuAccounts)
        assertEquals(Kind.LOAN_PAYMENT, c.kind(honda))
        assertEquals("car", c.counterpartOf(honda)?.id)
        assertEquals(Kind.LOAN_PAYMENT, c.kind(promo))
        assertEquals("promo", c.counterpartOf(promo)?.id)
        // Paying the tracked Visa isn't counted; its purchases already are.
        assertEquals(Kind.INTERNAL, c.kind(visa))
    }

    @Test fun ucuOverdraftAndManualTransfers() {
        // Savings covers a small overdraft: savings side "to S0009", checking side "from S0001".
        val odOut = u(23.87, "Withdrawal Transfer to S0009 overdraft transfer", account = "sav", cat = Categories.BANK_FEES)
        val odIn = u(-23.87, "Deposit Transfer from S0001 overdraft transfer", cat = Categories.BANK_FEES)
        val toSav = u(800.0, "Mobile banking Withdrawal Transfer to S0001 Online Banking[1818166]")
        val atSav = u(-800.0, "Mobile banking Deposit Transfer from S0009 Online Banking[1818166]", account = "sav", cat = Categories.TRANSFER_IN)
        val txns = listOf(odOut, odIn, toSav, atSav)
        val c = Classifier(txns, ucuAccounts)
        assertEquals(Kind.INTERNAL, c.kind(odOut))
        assertEquals(Kind.SAVINGS, c.kind(odIn))      // pulled from savings
        assertEquals("sav", c.counterpartOf(odIn)?.id) // the real savings, not the $1 share
        assertEquals(Kind.SAVINGS, c.kind(toSav))
        assertEquals(Kind.INTERNAL, c.kind(atSav))
        val f = InsightEngine.flow(txns, c, java.time.YearMonth.from(today))
        assertEquals(800.0 - 23.87, f.saved, 0.001)
        assertEquals(0.0, f.spending, 0.001)
        assertEquals(0.0, f.income, 0.001)
    }

    @Test fun buyNowPayLaterIsDebtNotSpending() {
        val c1 = u(800.0, "ACH Withdrawal PAYPAL INST XFER CREDIT REPAYMEN", cat = Categories.GENERAL_SERVICES)
        val c2 = u(31.36, "ACH Withdrawal PAYPAL INST XFER PYPL PAYMTHLY", cat = Categories.TRANSFER_OUT)
        val c3 = u(57.0, "ACH Withdrawal PAYPAL INST XFER PAYPAL_CREDIT_C", cat = Categories.TRANSFER_OUT)
        val shop = u(26.99, "Card purchase PAYPAL *NETFLIX.COM", cat = Categories.ENTERTAINMENT)
        val c = Classifier(listOf(c1, c2, c3, shop), ucuAccounts)
        assertEquals(Kind.LOAN_PAYMENT, c.kind(c1))
        assertEquals(Kind.LOAN_PAYMENT, c.kind(c2))
        assertEquals(Kind.LOAN_PAYMENT, c.kind(c3))
        assertEquals(Kind.SPEND, c.kind(shop))
    }

    @Test fun transferToHiddenAccountStillCountsAsSpending() {
        val accts = ucuAccounts + Account("reward", "item", "Checking - Reward", "0079", "depository", "checking", 1500.0, 1400.0, included = false)
        val t = u(100.0, "Withdrawal Transfer to S0079")
        assertEquals(Kind.SPEND, Classifier(listOf(t), accts).kind(t))
    }
}
