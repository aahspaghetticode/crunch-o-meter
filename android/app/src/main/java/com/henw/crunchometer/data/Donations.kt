package com.henw.crunchometer.data

data class DonationAddress(
    /** What people see, e.g. "Gridcoin (GRC)". */
    val label: String,
    val address: String,
)

object Donations {
    /** If this list is ever emptied, the reminder and the Settings button disappear. */
    val ADDRESSES: List<DonationAddress> = listOf(
        // One EVM address works on all of these networks.
        DonationAddress("Ethereum, Arbitrum, Base, BNB Chain, Linea", "0x109e12c6D24423534434cFC541B79D4EE4B4f85a"),
        DonationAddress("Bitcoin", "bc1qt9zcg6keenpdjkw667ztrecyd2t4nraxp0430t"),
        DonationAddress("Solana", "HoAidBZ8AB55hF3MwnPwPdHoGHdL9YHfGSXhC48hJvNk"),
        DonationAddress("Tron", "TN7Ge6i8xcEbDJxLHoiihrxJ5AS3oepWec"),
        DonationAddress("Stellar", "GDJPDKUU52WW6IJFY2RFRIVQBKMPREH7V24IT4VBRTGLVCCJJIGIX5YQ"),
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
