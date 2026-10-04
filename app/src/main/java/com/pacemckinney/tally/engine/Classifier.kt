package com.pacemckinney.tally.engine

import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class Kind {
    /** Money spent (outflow in a spending category). */
    SPEND,
    /** Money back in a spending category (a refund); reduces spending. */
    REFUND,
    /** Paychecks, deposits and outside money coming in. */
    INCOME,
    /** Money moving between two of your own connected accounts. Ignored in totals. */
    INTERNAL,
    /** Excluded by hand. Ignored in totals. */
    EXCLUDED,
}

/**
 * Decides what each transaction means for the budget. Built once per transaction list so the
 * internal-transfer matching (which needs the whole list) only runs once.
 */
class Classifier(txns: List<Txn>) {
    private val internalIds: Set<String> = findInternalTransfers(txns)

    fun kind(t: Txn): Kind {
        val c = t.effectiveCategory
        if (c == Categories.EXCLUDED) return Kind.EXCLUDED
        if (t.userCategory == null && t.id in internalIds) return Kind.INTERNAL
        return if (t.amount > 0) {
            Kind.SPEND
        } else when (c) {
            Categories.INCOME, Categories.TRANSFER_IN -> Kind.INCOME
            // A refund mis-labelled as a transfer out still isn't income.
            Categories.TRANSFER_OUT -> Kind.REFUND
            else -> if (t.userCategory == null && looksLikePaycheck(t)) Kind.INCOME else Kind.REFUND
        }
    }

    private fun looksLikePaycheck(t: Txn): Boolean {
        val n = t.name.lowercase()
        return listOf("payroll", "direct dep", "dir dep", "salary", "paycheck").any { it in n }
    }

    companion object {
        private val TRANSFERISH = setOf(
            Categories.TRANSFER_IN, Categories.TRANSFER_OUT, Categories.LOAN_PAYMENTS,
        )

        /**
         * A transfer-out on one of your accounts and an equal transfer-in on another within three
         * days is you moving your own money (checking -> savings, paying your UCU card or loan
         * from checking). Neither side is spending or income.
         */
        fun findInternalTransfers(txns: List<Txn>): Set<String> {
            val candidates = txns.filter { it.category in TRANSFERISH || it.detailed?.contains("TRANSFER") == true }
            val outs = candidates.filter { it.amount > 0 }.sortedBy { it.date }
            val ins = candidates.filter { it.amount < 0 }.sortedBy { it.date }.toMutableList()
            val matched = HashSet<String>()
            for (o in outs) {
                val match = ins.firstOrNull { i ->
                    i.accountId != o.accountId &&
                        abs(abs(i.amount) - o.amount) < 0.005 &&
                        abs(ChronoUnit.DAYS.between(o.date, i.date)) <= 3
                } ?: continue
                ins.remove(match)
                matched += o.id
                matched += match.id
            }
            return matched
        }
    }
}
