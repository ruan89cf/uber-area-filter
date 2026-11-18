package com.ruan.uberareafilter.engine

/**
 * Local bloqueado pelo ENDERECO, nao so pelo nome.
 *
 * Problema real (17/11): "caxias shopping" estava bloqueado, mas a 99 mandou o embarque como
 * "Entrada norte, Rod. Washington Luíz, 2895" (a entrada do shopping, com outro nome) e a corrida passou.
 * Quando um nome bloqueado aparece como NOME DO LOCAL de um endereco com rua e numero
 * ("Caxias Shopping, Rodovia Washington Luiz, 2895"), o app aprende esse endereco e passa a
 * bloquear qualquer corrida nele, seja qual for o nome que o app de corrida usar.
 *
 * [lat]/[lng] (quando o Geocoder acha o local) bloqueiam tambem enderecos a ate 150 m dele.
 */
data class BlockedPlace(
    val term: String,
    val street: String,
    val number: String,
    val lat: Double? = null,
    val lng: Double? = null
) {
    val label: String get() = if (number.isBlank()) street else "$street nº $number"
}

object BlockedMatcher {

    const val NEAR_METERS = 150.0

    fun normNumber(n: String): String = n.trim().trimStart('0').ifEmpty { if (n.isBlank()) "" else "0" }

    /**
     * Palavra da lista que e um ENDERECO com numero ("Rodovia Washington Luiz nº 2895", "Rua X 123"):
     * bloqueia so esse numero daquela rua. null se a palavra nao for endereco com numero.
     */
    fun addressKeyword(keyword: String): Pair<StreetKey, String>? {
        val p = AddressParser.parse(keyword)
        if (p.poi != null) return null
        val key = p.street ?: return null
        val num = p.number?.let { normNumber(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return key to num
    }

    fun sameAddress(parts: AddressParts, key: StreetKey, number: String): Boolean {
        if (number.isBlank()) return false
        val s = parts.street ?: return false
        val n = parts.number ?: return false
        return normNumber(n) == number && AddressParser.looseMatch(s, key)
    }

    /**
     * Aprende o endereco de um nome bloqueado quando ele e o NOME DO LOCAL (POI) de um endereco com rua e numero.
     * Nome de bairro ou de rua nao vira local (so o POI conta).
     */
    fun learn(term: String, address: String?): BlockedPlace? {
        if (address.isNullOrBlank()) return null
        val nt = TextNormalizer.normalize(term).trim()
        if (nt.length < 3) return null
        val p = AddressParser.parse(address)
        val poi = p.poi ?: return null
        val street = p.streetRaw?.trim()?.takeIf { it.length >= 3 } ?: return null
        val num = p.number?.let { normNumber(it) }?.takeIf { it.isNotEmpty() } ?: return null
        if (!AreaEvaluator.wordRegex(nt).containsMatchIn(TextNormalizer.normalize(poi))) return null
        return BlockedPlace(term.trim(), street, num)
    }

    /** Mesmo local (mesmo termo + mesma rua + mesmo numero). */
    fun samePlace(a: BlockedPlace, b: BlockedPlace): Boolean {
        if (!a.term.equals(b.term, ignoreCase = true)) return false
        if (normNumber(a.number) != normNumber(b.number)) return false
        val ka = AddressParser.streetKeyOf(a.street)
        val kb = AddressParser.streetKeyOf(b.street)
        return if (ka != null && kb != null) AddressParser.strictMatch(ka, kb) else a.street.equals(b.street, ignoreCase = true)
    }

    /**
     * Procura bloqueio pelo endereco (palavra-endereco da lista ou local aprendido) no embarque/destino.
     * @return texto do motivo ("caxias shopping (mesmo endereço: Rodovia Washington Luiz nº 2895)") ou null.
     */
    fun matchAddress(addresses: List<String?>, keywords: List<String>, places: List<BlockedPlace>): String? {
        val parsed = addresses.filterNotNull().filter { it.isNotBlank() }.map { AddressParser.parse(it) }
        if (parsed.isEmpty()) return null
        for (kw in keywords) {
            val (key, num) = addressKeyword(kw) ?: continue
            if (parsed.any { sameAddress(it, key, num) }) return kw
        }
        for (pl in places) {
            val key = AddressParser.streetKeyOf(pl.street) ?: continue
            if (parsed.any { sameAddress(it, key, normNumber(pl.number)) }) {
                return "${pl.term} (mesmo endereço: ${pl.label})"
            }
        }
        return null
    }

    /** Local bloqueado com coordenada a ate 150 m do ponto. */
    fun nearPlace(lat: Double, lng: Double, places: List<BlockedPlace>): BlockedPlace? =
        places.firstOrNull { p ->
            val pl = p.lat
            val pn = p.lng
            pl != null && pn != null && AreaEvaluator.haversineKm(lat, lng, pl, pn) * 1000.0 <= NEAR_METERS
        }
}

/**
 * "Mesma corrida" quando ela toca de novo: a 99 reenvia a mesma oferta com pequenas diferencas de OCR
 * ("SouZa"/"Souza", virgula que some: "201 Parque Santa Marta") e preco/distancia que mudam.
 * Compara o texto normalizado do embarque e do destino (tolera 3 letras) exigindo os MESMOS numeros.
 */
data class OfferSignature(val app: String, val pickup: String, val dropoff: String) {

    fun matches(other: OfferSignature): Boolean =
        app.equals(other.app, ignoreCase = true) && same(pickup, other.pickup) && same(dropoff, other.dropoff)

    companion object {
        private val digitsRegex = Regex("""\d+""")

        fun of(app: String, pickup: String?, dropoff: String?): OfferSignature? {
            val p = key(pickup) ?: return null
            val d = key(dropoff) ?: return null
            return OfferSignature(app.uppercase(), p, d)
        }

        internal fun key(address: String?): String? {
            if (address.isNullOrBlank()) return null
            return AddressParser.normalizeLoose(address).take(48).trim().takeIf { it.length >= 6 }
        }

        private fun numbers(s: String): Set<String> =
            digitsRegex.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()

        private fun same(a: String, b: String): Boolean {
            if (a == b) return true
            val len = minOf(a.length, b.length, 40)
            if (len < 10) return false
            val ca = a.take(len)
            val cb = b.take(len)
            if (numbers(ca) != numbers(cb)) return false
            return GeoMath.boundedLevenshtein(ca, cb, 3) <= 3
        }
    }
}
