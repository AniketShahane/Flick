package com.flick.sender.support

import com.flick.sender.BuildConfig
import java.net.URI

data class SupportOption(
    val amountDollars: Int,
    val checkoutUrl: String,
)

/**
 * The complete set of hosted support choices, or no catalog at all. Keeping creation
 * atomic prevents a partially configured build from presenting a dead checkout tier.
 */
class SupportCatalog private constructor(
    val options: List<SupportOption>,
) {
    companion object {
        fun configured(): SupportCatalog? = fromUrls(
            threeDollarUrl = BuildConfig.SUPPORT_STRIPE_3_URL,
            eightDollarUrl = BuildConfig.SUPPORT_STRIPE_8_URL,
            fifteenDollarUrl = BuildConfig.SUPPORT_STRIPE_15_URL,
        )

        fun fromUrls(
            threeDollarUrl: String,
            eightDollarUrl: String,
            fifteenDollarUrl: String,
        ): SupportCatalog? {
            val configured = listOf(
                3 to threeDollarUrl,
                8 to eightDollarUrl,
                15 to fifteenDollarUrl,
            ).map { (amount, candidate) ->
                SupportOption(amount, validatedCheckoutUrl(candidate) ?: return null)
            }
            return SupportCatalog(configured)
        }

        private fun validatedCheckoutUrl(candidate: String): String? {
            val value = candidate.trim()
            if (value.isEmpty()) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val path = uri.rawPath.orEmpty()
            return value.takeIf {
                uri.scheme == "https" &&
                    uri.host == "buy.stripe.com" &&
                    uri.rawUserInfo == null &&
                    uri.port == -1 &&
                    path.startsWith('/') &&
                    path.drop(1).isNotBlank() &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            }
        }
    }
}
