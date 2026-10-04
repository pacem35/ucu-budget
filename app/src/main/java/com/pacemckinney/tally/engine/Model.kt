package com.pacemckinney.tally.engine

import java.time.LocalDate

/**
 * A transaction as Plaid reports it. Plaid's sign convention is kept:
 * a POSITIVE amount is money leaving the account, a NEGATIVE amount is money coming in.
 */
data class Txn(
    val id: String,
    val accountId: String,
    val amount: Double,
    val date: LocalDate,
    val name: String,
    val merchant: String? = null,
    /** Plaid personal_finance_category.primary, e.g. FOOD_AND_DRINK. */
    val category: String = Categories.OTHER,
    /** Plaid personal_finance_category.detailed, e.g. FOOD_AND_DRINK_COFFEE. */
    val detailed: String? = null,
    val pending: Boolean = false,
    /** A category the user picked by hand; wins over Plaid's. */
    val userCategory: String? = null,
    val logoUrl: String? = null,
    /** The bank's raw description, e.g. "Withdrawal Transfer to L1201". */
    val original: String? = null,
) {
    val effectiveCategory: String get() = userCategory ?: category
    val displayName: String get() = merchant?.takeIf { it.isNotBlank() } ?: name
    val isOutflow: Boolean get() = amount > 0
}

/** What an account is for, which decides how money moving in and out of it is counted. */
enum class AccountRole(val label: String) {
    SPENDING("Spending (checking)"),
    SAVINGS("Savings"),
    CREDIT("Credit card"),
    LOAN("Loan"),
    ;

    companion object {
        fun default(type: String, subtype: String?): AccountRole = when (type) {
            "credit" -> CREDIT
            "loan" -> LOAN
            "investment" -> SAVINGS
            else -> when (subtype) {
                "savings", "money market", "cd", "hsa", "cash management" -> SAVINGS
                else -> SPENDING
            }
        }
    }
}

data class Account(
    val id: String,
    val itemId: String,
    val name: String,
    val mask: String?,
    val type: String,      // depository, credit, loan, investment
    val subtype: String?,  // checking, savings, credit card, auto ...
    val current: Double?,
    val available: Double?,
    val creditLimit: Double? = null,
    /** Your choice; null = use the default for this account type. */
    val roleOverride: AccountRole? = null,
    /** False = not yours / don't care. Its balance and transactions are ignored everywhere. */
    val included: Boolean = true,
    val nickname: String? = null,
    /** True once you've looked at this account in the account manager. */
    val reviewed: Boolean = false,
) {
    val role: AccountRole get() = roleOverride ?: AccountRole.default(type, subtype)
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: cleanName
    /** Plaid sometimes prefixes UCU loan/card names with the masked member number. */
    val cleanName: String get() = name.replace(Regex("^\\*+\\d*\\s*\\d{4}\\s+"), "").ifBlank { name }
    val isDepository get() = type == "depository"
    val isChecking get() = role == AccountRole.SPENDING
    /** What you can actually spend right now from this account. */
    val spendable: Double get() = available ?: current ?: 0.0
    /** For credit cards and loans: how much you owe. */
    val owed: Double get() = current ?: 0.0
    val limit: Double? get() = creditLimit ?: if (role == AccountRole.CREDIT && available != null && current != null) current + available else null
    val utilization: Double? get() = limit?.takeIf { it > 0 }?.let { owed / it }
}

data class Budget(val category: String, val monthlyLimit: Double)

object Categories {
    const val INCOME = "INCOME"
    const val TRANSFER_IN = "TRANSFER_IN"
    const val TRANSFER_OUT = "TRANSFER_OUT"
    const val LOAN_PAYMENTS = "LOAN_PAYMENTS"
    const val BANK_FEES = "BANK_FEES"
    const val ENTERTAINMENT = "ENTERTAINMENT"
    const val FOOD_AND_DRINK = "FOOD_AND_DRINK"
    const val GENERAL_MERCHANDISE = "GENERAL_MERCHANDISE"
    const val HOME_IMPROVEMENT = "HOME_IMPROVEMENT"
    const val MEDICAL = "MEDICAL"
    const val PERSONAL_CARE = "PERSONAL_CARE"
    const val GENERAL_SERVICES = "GENERAL_SERVICES"
    const val GOVERNMENT_AND_NON_PROFIT = "GOVERNMENT_AND_NON_PROFIT"
    const val TRANSPORTATION = "TRANSPORTATION"
    const val TRAVEL = "TRAVEL"
    const val RENT_AND_UTILITIES = "RENT_AND_UTILITIES"
    const val OTHER = "OTHER"
    /** User-only category: leave this transaction out of every total. */
    const val EXCLUDED = "EXCLUDED"
    /** User-only: money put into (or, if it came in, taken out of) savings. */
    const val SAVINGS = "SAVINGS"
    /** User-only: just moving your own money around; ignored. */
    const val INTERNAL = "INTERNAL"

    val labels = linkedMapOf(
        FOOD_AND_DRINK to "Food & drink",
        GENERAL_MERCHANDISE to "Shopping",
        TRANSPORTATION to "Gas & transportation",
        RENT_AND_UTILITIES to "Rent & utilities",
        LOAN_PAYMENTS to "Loan / debt payment",
        SAVINGS to "Savings",
        INTERNAL to "Between my accounts",
        ENTERTAINMENT to "Entertainment",
        GENERAL_SERVICES to "Services",
        PERSONAL_CARE to "Personal care",
        MEDICAL to "Medical",
        HOME_IMPROVEMENT to "Home",
        TRAVEL to "Travel",
        GOVERNMENT_AND_NON_PROFIT to "Gov & donations",
        BANK_FEES to "Bank fees",
        TRANSFER_OUT to "Transfers out",
        TRANSFER_IN to "Transfers in",
        INCOME to "Income",
        OTHER to "Other",
        EXCLUDED to "Excluded",
    )

    /** Categories a person might put a spending budget on. */
    val budgetable = labels.keys.filter { it !in setOf(INCOME, TRANSFER_IN, EXCLUDED, SAVINGS, INTERNAL, LOAN_PAYMENTS) }

    fun label(c: String) = labels[c] ?: c.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
