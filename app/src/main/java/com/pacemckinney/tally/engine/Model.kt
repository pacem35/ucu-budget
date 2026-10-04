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
) {
    val effectiveCategory: String get() = userCategory ?: category
    val displayName: String get() = merchant?.takeIf { it.isNotBlank() } ?: name
    val isOutflow: Boolean get() = amount > 0
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
) {
    val isDepository get() = type == "depository"
    val isChecking get() = isDepository && subtype == "checking"
    /** What you can actually spend right now from this account. */
    val spendable: Double get() = available ?: current ?: 0.0
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

    val labels = linkedMapOf(
        FOOD_AND_DRINK to "Food & drink",
        GENERAL_MERCHANDISE to "Shopping",
        TRANSPORTATION to "Gas & transportation",
        RENT_AND_UTILITIES to "Rent & utilities",
        LOAN_PAYMENTS to "Loan payments",
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
    val budgetable = labels.keys.filter { it !in setOf(INCOME, TRANSFER_IN, EXCLUDED) }

    fun label(c: String) = labels[c] ?: c.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
