package tech.atomiclabs.link.ui

/** The in-app kill switch as the status screen sees it. */
interface LinkSwitch {
    fun isKilled(): Boolean
    fun kill()
    fun turnOn()
}
