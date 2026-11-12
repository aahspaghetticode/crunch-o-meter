package com.henw.crunchometer.data

data class DonationAddress(
    /** What people see, e.g. "Gridcoin (GRC)". */
    val label: String,
    val address: String,
)

object Donations {
    /**
     * Fill these in before release. While the list is empty the reminder never
     * appears and Settings hides the "Donation addresses" button.
     */
    val ADDRESSES: List<DonationAddress> = listOf(
        // DonationAddress("Gridcoin (GRC)", "S..."),
        // DonationAddress("Bitcoin (BTC)", "bc1..."),
        // DonationAddress("Monero (XMR)", "4..."),
    )

    /**
     * Yes, this is the code. If you dug through the source to find it, you earned it.
     * Entering it in the reminder or in Settings turns reminders off for good.
     */
    const val DEV_CODE = "stfu-bro"

    const val CODE_EMAIL = "shutup@clowds.net"

    /** Don't nag on the first couple of opens; let the app prove itself first. */
    const val MIN_LAUNCHES = 3

    /** At most one reminder this often. */
    const val INTERVAL_MS = 4L * 24 * 60 * 60 * 1000

    fun isDevCode(input: String): Boolean = input.trim().equals(DEV_CODE, ignoreCase = true)
}
