package app.phoneassistant

/** Turns API/network failures into one short sentence the user can act on (spoken and shown). */
object Errors {
    fun explain(raw: String, dutch: Boolean): String {
        val m = raw.lowercase()
        fun t(nl: String, en: String) = if (dutch) nl else en
        return when {
            m.contains("network:") || m.contains("unknownhost") || m.contains("timeout") || m.contains("timed out") -> t("Ik heb geen verbinding met internet.", "I can't reach the internet.")
            m.contains("api 401") || m.contains("authentication") || m.contains("invalid x-api-key") -> t("Je API-sleutel werkt niet. Controleer hem in de instellingen.", "Your API key doesn't work. Check it in settings.")
            m.contains("credit balance") || m.contains("api 402") || m.contains("billing") -> t("Je tegoed bij Anthropic is op. Vul het aan en probeer opnieuw.", "Your Anthropic credit has run out. Top it up and try again.")
            m.contains("api 403") || m.contains("permission") -> t("Je sleutel heeft geen toegang tot dit model. Kies een ander model in de instellingen.", "Your key can't use this model. Pick another model in settings.")
            m.contains("api 404") || m.contains("not_found") -> t("Dat model bestaat niet. Kies een ander model in de instellingen.", "That model doesn't exist. Pick another model in settings.")
            m.contains("api 429") || m.contains("rate_limit") -> t("Te veel verzoeken tegelijk. Probeer het over een minuutje opnieuw.", "Too many requests. Try again in a minute.")
            m.contains("api 5") || m.contains("overloaded") -> t("Claude is even overbelast. Probeer het zo opnieuw.", "Claude is overloaded for a moment. Try again shortly.")
            m.contains("no api key") -> t("Voeg eerst je API-sleutel toe in de instellingen.", "Add your API key in settings first.")
            else -> t("Er ging iets mis: ", "Something went wrong: ") + raw.take(120)
        }
    }
}
