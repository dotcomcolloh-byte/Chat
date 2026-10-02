package com.telefam.payments

/**
 * Server-side pricing and provider routing. This is the single source of truth for
 * what verification costs and which processor a user pays through. The client is
 * told the result; it never proposes an amount, currency, or provider.
 *
 * Provider rule (per product decision):
 *  - Users whose profile country is a Paystack-supported country pay via Paystack.
 *  - Everyone else pays via PayPal.
 * The app UI shows exactly ONE payment method — whichever the server returns —
 * with no provider choice offered to the user.
 *
 * Currency rule: prices are defined per currency below. The user's profile country
 * selects the currency; if the country is unknown/unset or has no price row, USD is
 * used. Amounts are in minor units (kobo/cents) to avoid float money bugs.
 */
object PricingCatalog {

    enum class Provider { PAYSTACK, PAYPAL }
    enum class Plan { MONTHLY, ANNUAL }

    /** Countries where Paystack processes local-currency card/mobile-money payments. */
    private val PAYSTACK_COUNTRIES = setOf(
        "NG", // Nigeria — NGN
        "GH", // Ghana — GHS
        "ZA", // South Africa — ZAR
        "KE"  // Kenya — KES
    )

    data class Price(val monthlyMinor: Long, val annualMinor: Long, val symbol: String)

    /** Curated per-currency pricing. KES matches the shipped UI (Ksh 700/mo, Ksh 6,720/yr = 20% off). */
    private val PRICES: Map<String, Price> = mapOf(
        "USD" to Price(monthlyMinor = 499, annualMinor = 4790, symbol = "$"),
        "KES" to Price(monthlyMinor = 70000, annualMinor = 672000, symbol = "Ksh "),
        "NGN" to Price(monthlyMinor = 750000, annualMinor = 7200000, symbol = "₦"),
        "GHS" to Price(monthlyMinor = 7500, annualMinor = 72000, symbol = "GH₵"),
        "ZAR" to Price(monthlyMinor = 8900, annualMinor = 85500, symbol = "R"),
        "EUR" to Price(monthlyMinor = 459, annualMinor = 4410, symbol = "€"),
        "GBP" to Price(monthlyMinor = 399, annualMinor = 3830, symbol = "£")
    )

    /** Broad country -> currency mapping. Unknown countries fall through to USD. */
    private val COUNTRY_CURRENCY: Map<String, String> = mapOf(
        "KE" to "KES", "UG" to "USD", "TZ" to "USD", "RW" to "USD",
        "NG" to "NGN", "GH" to "GHS", "ZA" to "ZAR",
        "US" to "USD", "CA" to "USD", "AU" to "USD",
        "GB" to "GBP", "IE" to "EUR",
        "DE" to "EUR", "FR" to "EUR", "IT" to "EUR", "ES" to "EUR", "NL" to "EUR",
        "BE" to "EUR", "PT" to "EUR", "AT" to "EUR", "FI" to "EUR", "GR" to "EUR"
    )

    fun providerFor(countryCode: String?): Provider =
        if (countryCode?.uppercase() in PAYSTACK_COUNTRIES) Provider.PAYSTACK else Provider.PAYPAL

    fun currencyFor(countryCode: String?): String =
        COUNTRY_CURRENCY[countryCode?.uppercase()] ?: "USD"

    fun priceFor(currency: String, plan: Plan): Pair<Long, String> {
        val p = PRICES[currency] ?: PRICES.getValue("USD")
        return (if (plan == Plan.MONTHLY) p.monthlyMinor else p.annualMinor) to p.symbol
    }

    fun monthlyEquivalentAnnual(currency: String): Long {
        val p = PRICES[currency] ?: PRICES.getValue("USD")
        return p.monthlyMinor * 12 // strikethrough "was" price for the annual row
    }

    fun discountPercent(currency: String): Int {
        val p = PRICES[currency] ?: PRICES.getValue("USD")
        val full = p.monthlyMinor * 12
        return if (full <= 0) 0 else (((full - p.annualMinor) * 100) / full).toInt()
    }

    /** Formats a minor-unit amount like the reference UI ("Ksh 6,720", "$4.99"). */
    fun format(currency: String, minor: Long): String {
        val symbol = (PRICES[currency] ?: PRICES.getValue("USD")).symbol
        val grouped = "%,d".format(minor / 100)
        val cents = minor % 100
        return if (cents == 0L) "$symbol$grouped" else "$symbol$grouped.${"%02d".format(cents)}"
    }
}
