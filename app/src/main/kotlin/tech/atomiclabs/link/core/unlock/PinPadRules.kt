// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.unlock

enum class DigitField { TEXT, DESCRIPTION, VIEW_ID }
data class DigitRule(val field: DigitField, val prefix: String = "")

/** Layout labels and suffixes stay in one table so another keypad needs only a rule row. */
object PinPadRules {
    const val systemUiPackage = "com.android.systemui"
    val bouncerViewIdSuffixes = listOf("/keyguard_pin_view", "/keyguard_security_container",
        "/keyguard_security_view_flipper", "/keyguard_host_view")
    val simViewIdFragments = listOf("sim_pin", "sim_puk", "simpin", "simpuk")
    val simLabels = listOf("SIM PIN", "SIM PUK", "unlock SIM", "SIM card")
    val sensitiveViewIdFragments = bouncerViewIdSuffixes + simViewIdFragments +
        listOf("/key", "pinentry", "pin_entry", "passwordentry", "password_entry", "keyguard_password")
    val sensitiveClassFragments = listOf("NumPadKey", "KeyguardPin", "KeyguardSim", "KeyguardPassword")
    val digitRules = listOf(
        DigitRule(DigitField.TEXT),
        DigitRule(DigitField.DESCRIPTION),
        DigitRule(DigitField.VIEW_ID, "/key"),
    )
    val confirmLabels = listOf("OK", "Enter", "Done", "Confirm", "✓", "✔")
    val confirmViewIdSuffixes = listOf("/key_enter")
}
