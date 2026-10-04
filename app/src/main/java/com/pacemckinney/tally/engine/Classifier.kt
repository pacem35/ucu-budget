package com.pacemckinney.tally.engine

import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class Kind(val label: String) {
    /** Money spent (outflow in a spending category). */
    SPEND("Spending"),
    /** Money back in a spending category (a refund); reduces spending. */
    REFUND("Refund"),
    /** Paychecks, deposits, interest and outside money coming in. */
    INCOME("Income"),
    /** Money moved into savings (positive) or pulled out of savings (negative). */
    SAVINGS("Savings"),
    /** Payments toward a loan or an outside credit card. */
    LOAN_PAYMENT("Loan payment"),
    /** Money moving between your own accounts with no budget effect (e.g. paying your tracked card). */
    INTERNAL("Between your accounts"),
    /** Excluded by hand. */
    EXCLUDED("Excluded"),
    /** On an account you've chosen not to track. */
    HIDDEN("Hidden account"),
}

/**
 * Decides what each transaction means for the budget, using the roles of your accounts.
 *
 * Transfers between two tracked accounts are matched up and counted once, on the side that
 * matters: checking -> savings is "saved", savings -> checking is "pulled from savings",
 * checking -> loan is a "loan payment", checking -> tracked credit card is ignored (the card's
 * own purchases are already counted as spending).
 */
class Classifier(txns: List<Txn>, accounts: List<Account> = emptyList()) {
    private val accountsById = accounts.associateBy { it.id }
    private val decided = HashMap<String, Kind>()
    /** For a matched transfer, the account on the other side. */
    private val counterpart = HashMap<String, String>()

    init {
        matchTransfers(txns.filter { included(it.accountId) })
    }

    private fun included(accountId: String) = accountsById[accountId]?.included ?: true
    private fun role(accountId: String) = accountsById[accountId]?.role ?: AccountRole.SPENDING

    /** The other account in a matched transfer, if any (e.g. which loan a payment went to). */
    fun counterpartOf(t: Txn): Account? = counterpart[t.id]?.let { accountsById[it] }

    fun kind(t: Txn): Kind {
        if (!included(t.accountId)) return Kind.HIDDEN
        val user = t.userCategory
        if (user != null) return userKind(t, user)
        decided[t.id]?.let { return it }

        val role = role(t.accountId)
        val c = t.category
        val d = t.detailed ?: ""
        // Activity on a loan account is the lender's side of things (payments posting, interest);
        // what counts is the payment leaving your checking.
        if (role == AccountRole.LOAN) return Kind.INTERNAL

        return if (t.amount > 0) {
            when {
                c == Categories.LOAN_PAYMENTS -> Kind.LOAN_PAYMENT
                role != AccountRole.SAVINGS && (d == "TRANSFER_OUT_SAVINGS" || d == "TRANSFER_OUT_INVESTMENT_AND_RETIREMENT_FUNDS") -> Kind.SAVINGS
                else -> Kind.SPEND
            }
        } else {
            when {
                // A payment or credit landing on a credit card that didn't match a tracked account.
                role == AccountRole.CREDIT && (c == Categories.LOAN_PAYMENTS || c == Categories.TRANSFER_IN) -> Kind.INTERNAL
                c == Categories.INCOME || c == Categories.TRANSFER_IN -> Kind.INCOME
                // A refund mis-labelled as a transfer out still isn't income.
                c == Categories.TRANSFER_OUT -> Kind.REFUND
                looksLikePaycheck(t) -> Kind.INCOME
                else -> Kind.REFUND
            }
        }
    }

    private fun userKind(t: Txn, user: String): Kind = when (user) {
        Categories.EXCLUDED -> Kind.EXCLUDED
        Categories.INTERNAL -> Kind.INTERNAL
        Categories.SAVINGS -> Kind.SAVINGS
        Categories.LOAN_PAYMENTS -> if (t.amount > 0) Kind.LOAN_PAYMENT else Kind.INTERNAL
        Categories.INCOME, Categories.TRANSFER_IN -> if (t.amount < 0) Kind.INCOME else Kind.SPEND
        else -> if (t.amount > 0) Kind.SPEND else Kind.REFUND
    }

    private fun looksLikePaycheck(t: Txn): Boolean {
        val n = t.name.lowercase()
        return listOf("payroll", "direct dep", "dir dep", "salary", "paycheck").any { it in n }
    }

    private fun matchTransfers(txns: List<Txn>) {
        val candidates = txns.filter {
            it.category in TRANSFERISH || it.detailed?.contains("TRANSFER") == true ||
                role(it.accountId) == AccountRole.LOAN || role(it.accountId) == AccountRole.CREDIT
        }
        val outs = candidates.filter { it.amount > 0 }.sortedBy { it.date }
        val ins = candidates.filter { it.amount < 0 }.sortedBy { it.date }.toMutableList()
        for (o in outs) {
            val match = ins.firstOrNull { i ->
                i.accountId != o.accountId &&
                    abs(abs(i.amount) - o.amount) < 0.005 &&
                    abs(ChronoUnit.DAYS.between(o.date, i.date)) <= 4 &&
                    // A purchase or refund on the card isn't a transfer; only card payments are.
                    !(role(o.accountId) == AccountRole.CREDIT && o.category !in TRANSFERISH) &&
                    !(role(i.accountId) == AccountRole.CREDIT && i.category !in TRANSFERISH)
            } ?: continue
            ins.remove(match)
            val (outKind, inKind) = pairKinds(role(o.accountId), role(match.accountId))
            decided[o.id] = outKind
            decided[match.id] = inKind
            counterpart[o.id] = match.accountId
            counterpart[match.id] = o.accountId
        }
    }

    companion object {
        private val TRANSFERISH = setOf(
            Categories.TRANSFER_IN, Categories.TRANSFER_OUT, Categories.LOAN_PAYMENTS,
        )

        /** (kind of the outflow side, kind of the inflow side) for a transfer from -> to. */
        fun pairKinds(from: AccountRole, to: AccountRole): Pair<Kind, Kind> = when (to) {
            AccountRole.LOAN -> Kind.LOAN_PAYMENT to Kind.INTERNAL
            AccountRole.CREDIT -> Kind.INTERNAL to Kind.INTERNAL
            AccountRole.SAVINGS -> if (from == AccountRole.SAVINGS) Kind.INTERNAL to Kind.INTERNAL else Kind.SAVINGS to Kind.INTERNAL
            AccountRole.SPENDING -> if (from == AccountRole.SAVINGS) Kind.INTERNAL to Kind.SAVINGS else Kind.INTERNAL to Kind.INTERNAL
        }
    }
}
