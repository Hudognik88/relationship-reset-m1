package ru.poslesorry.backend

import java.nio.file.Files
import java.nio.file.Path

/** This release can only create demo checkouts; there is deliberately no live mode. */
class DemoPaymentConfig(val secret: String, val sys: String? = null) {
    val payformUrl = "https://relationshipreset.payform.ru/"

    init {
        require(secret.matches(Regex("[!-~]{16,256}"))) { "Payment configuration unavailable" }
        require(sys == null || sys.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Payment configuration unavailable" }
    }

    override fun toString() = "DemoPaymentConfig(redacted)"

    companion object {
        fun fromEnvironment(env: Map<String, String>): DemoPaymentConfig? {
            require("RR_PRODAMUS_SECRET" !in env) { "Use a private payment secret file" }
            val mode = env["RR_PRODAMUS_MODE"] ?: "disabled"
            require(mode in setOf("disabled", "demo")) { "Live payments are not supported" }
            if (mode == "disabled") {
                require(listOf("RR_PRODAMUS_SECRET_FILE", "RR_PRODAMUS_SYS", "RR_PRODAMUS_FORM_URL").none { it in env }) { "Partial payment configuration" }
                return null
            }
            require(env["RR_PRODAMUS_FORM_URL"] in setOf(null, "https://relationshipreset.payform.ru/")) { "Unexpected payment page" }
            val path = Path.of(env["RR_PRODAMUS_SECRET_FILE"] ?: error("Payment secret file unavailable"))
            require(Files.isRegularFile(path) && Files.size(path) in 16..512) { "Payment secret file unavailable" }
            return DemoPaymentConfig(Files.readString(path).trim(), env["RR_PRODAMUS_SYS"])
        }
    }
}
