package com.flick.sender.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class SupportCatalogTest {
    @Test fun completeValidCatalogPreservesTierOrderAndUrls() {
        val urls = listOf(validUrl(3), validUrl(8), validUrl(15))

        val catalog = SupportCatalog.fromUrls(urls[0], urls[1], urls[2])!!

        assertEquals(listOf(3, 8, 15), catalog.options.map { it.amountDollars })
        assertEquals(urls, catalog.options.map { it.checkoutUrl })
    }

    @Test fun anyMissingOrInvalidTierMakesTheWholeCatalogUnavailable() {
        val valid = validUrl(3)
        val invalidCandidates = listOf(
            "",
            "   ",
            URI("http", HOST, "/3", null).toASCIIString(),
            URI("https", "example.invalid", "/3", null).toASCIIString(),
            "https://user@$HOST/3",
            "https://$HOST:443/3",
            "https://$HOST/3?source=test",
            "https://$HOST/3#fragment",
            "https://$HOST",
            "https://$HOST/",
            "not a uri",
        )

        invalidCandidates.forEach { invalid ->
            assertNull("accepted invalid checkout address: $invalid", SupportCatalog.fromUrls(invalid, valid, valid))
            assertNull("accepted invalid checkout address: $invalid", SupportCatalog.fromUrls(valid, invalid, valid))
            assertNull("accepted invalid checkout address: $invalid", SupportCatalog.fromUrls(valid, valid, invalid))
        }
    }

    @Test fun canonicalHostAndSchemeAreExact() {
        val valid = validUrl(3)

        assertNull(SupportCatalog.fromUrls("HTTPS://$HOST/3", valid, valid))
        assertNull(SupportCatalog.fromUrls("https://BUY.STRIPE.COM/3", valid, valid))
        assertNull(SupportCatalog.fromUrls("https://$HOST./3", valid, valid))
    }

    @Test fun surroundingWhitespaceIsRemovedBeforeValidation() {
        val valid = validUrl(3)

        val catalog = SupportCatalog.fromUrls("  $valid  ", valid, valid)!!

        assertEquals(valid, catalog.options.first().checkoutUrl)
    }

    private fun validUrl(tier: Int): String = URI("https", HOST, "/$tier", null).toASCIIString()

    private companion object {
        const val HOST = "buy.stripe.com"
    }
}
