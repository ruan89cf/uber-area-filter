package com.ruan.uberareafilter.engine

/**
 * Endereco do card (Uber/99) quebrado em partes:
 *   "CAPS III Fernando Diniz, Rua Leopoldina Rego, 754 - Penha"
 *     -> poi = "CAPS III Fernando Diniz", rua = "Rua Leopoldina Rego", numero = "754", bairro = "penha"
 *   "Rua Cuba, Penha, Rio de Janeiro" -> rua = "Rua Cuba", numero = null, bairro = "penha"
 *   "Olaria, Rio de Janeiro"           -> so bairro = "olaria"
 *
 * Tudo aqui e Kotlin puro (testavel no JVM sem Android).
 */
data class AddressParts(
    val raw: String,
    val poi: String?,
    val streetRaw: String?,
    val street: StreetKey?,
    val number: String?,
    val bairroRaw: String?,
    val bairro: String?,
    val cityRaw: String?,
    val city: String?
) {
    val hasStreet: Boolean get() = street != null
    val hasNumber: Boolean get() = !number.isNullOrBlank()

    /** Consulta canonica para o Geocoder: "Rua X, 123, Bairro, Cidade, RJ" (sem POI). */
    fun canonicalQuery(withNumber: Boolean = true): String? {
        val streetTxt = streetRaw?.let { AddressParser.expandAbbreviations(it) } ?: return null
        val parts = mutableListOf(streetTxt)
        if (withNumber && hasNumber) parts.add(number!!)
        bairroRaw?.let { parts.add(it) }
        parts.add(cityRaw ?: "Rio de Janeiro")
        parts.add("RJ")
        return parts.joinToString(", ")
    }

    /** Consulta para enderecos sem rua (POI e/ou bairro). */
    fun placeQuery(): String {
        val parts = mutableListOf<String>()
        poi?.let { parts.add(AddressParser.expandAbbreviations(it)) }
        bairroRaw?.let { parts.add(it) }
        parts.add(cityRaw ?: "Rio de Janeiro")
        parts.add("RJ")
        return parts.joinToString(", ")
    }
}

/** Chave comparavel de logradouro: tipo canonico ("rua", "avenida"...) + nucleo normalizado. */
data class StreetKey(val type: String?, val core: String) {
    fun display(): String = listOfNotNull(type, core).joinToString(" ")
}

object AddressParser {

    private val streetTypeCanon = linkedMapOf(
        "rua" to "rua", "r" to "rua",
        "avenida" to "avenida", "av" to "avenida", "avda" to "avenida",
        "estrada" to "estrada", "estr" to "estrada", "est" to "estrada",
        "travessa" to "travessa", "tv" to "travessa", "trav" to "travessa",
        "praca" to "praca", "pca" to "praca", "pc" to "praca",
        "largo" to "largo", "lgo" to "largo",
        "alameda" to "alameda", "al" to "alameda",
        "rodovia" to "rodovia", "rod" to "rodovia",
        "beco" to "beco",
        "viela" to "viela",
        "via" to "via",
        "ladeira" to "ladeira", "ld" to "ladeira",
        "servidao" to "servidao",
        "caminho" to "caminho",
        "vila" to null // "Vila da Penha" e bairro; nunca tratar como tipo de via
    )

    // Tipos que podem aparecer abreviados com ponto ("R.", "Av.", "Est.") ou por extenso
    private val streetPrefixRegex = Regex(
        """^(rua|r\.|avenida|av\.?|avda\.?|estrada|estr\.?|est\.|travessa|tv\.?|trav\.?|praca|pca\.?|pc\.|largo|lgo\.?|alameda|al\.|rodovia|rod\.?|beco|viela|via|ladeira|ld\.|servidao|caminho)(\s|$).*"""
    )

    private val titleExpansions = mapOf(
        "dr" to "doutor", "dra" to "doutora", "eng" to "engenheiro", "engo" to "engenheiro",
        "prof" to "professor", "profa" to "professora", "cel" to "coronel", "gen" to "general",
        "mal" to "marechal", "alm" to "almirante", "brig" to "brigadeiro", "brg" to "brigadeiro",
        "cmte" to "comandante", "pres" to "presidente", "sen" to "senador", "dep" to "deputado",
        "des" to "desembargador", "cap" to "capitao", "ten" to "tenente", "sgt" to "sargento",
        "srg" to "sargento", "sarg" to "sargento", "maj" to "major", "pe" to "padre",
        "sta" to "santa", "sto" to "santo", "sra" to "senhora", "nsa" to "nossa", "n" to "nossa",
        "visc" to "visconde", "cons" to "conselheiro", "min" to "ministro", "gov" to "governador",
        "dom" to "dom", "frei" to "frei", "irma" to "irma"
    )

    private val phonetic = listOf(
        "souza" to "sousa", "luiz" to "luis", "braz" to "bras", "teresa" to "tereza",
        "walter" to "valter", "waldir" to "valdir", "ypiranga" to "ipiranga", "rodriguez" to "rodrigues"
    )

    private val connectors = setOf("da", "de", "do", "das", "dos", "e", "d")

    private val cityTokensNoSpace = setOf(
        "riodejaneiro", "riode", "janeiro", "rj", "brasil", "brazil", "estadodoriodejaneiro", "riodejaneirorj"
    )

    /** Municipios da Regiao Metropolitana (para separar cidade de bairro). */
    private val metroCities = listOf(
        "duque de caxias", "nova iguacu", "belford roxo", "sao joao de meriti", "nilopolis",
        "mesquita", "queimados", "japeri", "seropedica", "itaguai", "mage", "guapimirim",
        "niteroi", "sao goncalo", "itaborai", "marica", "tangua", "rio bonito", "paracambi",
        "petropolis", "cachoeiras de macacu"
    )

    private val cepRegex = Regex("""^\d{5}-?\d{3}$""")
    private val numberSegmentRegex = Regex("""^(?:n[o°º]?\.?\s*)?(\d{1,5})\s*[a-z]?$""")
    private val inlineNumberRegex = Regex("""^(.*?\D)[\s,]+(?:[nN][oO°º]?\.?\s*)?(\d{1,5})\s*[a-zA-Z]?$""")
    private val semNumeroRegex = Regex("""^s\s*/?\s*n[o°º]?$""")

    fun normalizeLoose(input: String): String =
        TextNormalizer.normalize(input)
            .replace(Regex("""[^a-z0-9/ ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun parse(raw: String): AddressParts {
        val prepared = raw
            .replace("\n", " ")
            .replace(Regex("""\s+[-–—]\s+"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        var segments = prepared.split(",")
            .map { it.trim().trim('.', ';', ':') }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // 1) Remove cidade/estado/pais/CEP do fim (e captura cidade da Baixada/Niteroi quando houver)
        var cityRaw: String? = null
        var city: String? = null
        while (segments.isNotEmpty()) {
            val last = segments.last()
            val norm = normalizeLoose(last)
            val noSpace = norm.replace(" ", "")
            if (noSpace in cityTokensNoSpace || cepRegex.matches(norm.replace(" ", ""))) {
                segments.removeAt(segments.size - 1)
                if (city == null && noSpace.startsWith("riode")) {
                    city = "rio de janeiro"
                    cityRaw = "Rio de Janeiro"
                }
                continue
            }
            val metro = metroCities.firstOrNull { norm == it }
            if (metro != null && city == null) {
                city = metro
                cityRaw = last
                segments.removeAt(segments.size - 1)
                continue
            }
            // "Penha Circular Rio de Janeiro" (OCR sem virgula) -> corta o sufixo de cidade
            val suffixCut = Regex("""^(.*\S)\s+rio\s*de\s*janeiro$""").find(norm)
            if (suffixCut != null && suffixCut.groupValues[1].length >= 3) {
                val idx = last.lowercase().lastIndexOf("rio")
                segments[segments.size - 1] = if (idx > 0) last.substring(0, idx).trim() else last
                city = city ?: "rio de janeiro"
                cityRaw = cityRaw ?: "Rio de Janeiro"
            }
            break
        }

        // 2) Logradouro
        var streetIdx = -1
        for ((i, seg) in segments.withIndex()) {
            val norm = TextNormalizer.normalize(seg)
            if (streetPrefixRegex.matches(norm) && !norm.startsWith("estacao")) {
                streetIdx = i
                break
            }
        }

        var streetRaw: String? = null
        var number: String? = null
        var poi: String? = null
        var bairroRaw: String? = null

        if (streetIdx >= 0) {
            var streetSeg = segments[streetIdx]
            val inline = inlineNumberRegex.find(streetSeg)
            if (inline != null) {
                // "Rua Montevideu 782" -> numero 782 (so se sobrar nome de rua com pelo menos 2 palavras)
                val namePart = inline.groupValues[1].trim()
                if (namePart.split(Regex("""\s+""")).size >= 2) {
                    number = inline.groupValues[2]
                    streetSeg = namePart
                }
            }
            streetRaw = streetSeg
            if (streetIdx > 0) {
                poi = segments.subList(0, streetIdx).joinToString(", ").ifBlank { null }
            }
            val rest = segments.drop(streetIdx + 1).toMutableList()
            if (number == null && rest.isNotEmpty()) {
                val n = TextNormalizer.normalize(rest.first())
                val m = numberSegmentRegex.find(n)
                if (m != null) {
                    number = m.groupValues[1]
                    rest.removeAt(0)
                } else if (semNumeroRegex.matches(n)) {
                    rest.removeAt(0)
                }
            }
            bairroRaw = rest.lastOrNull { seg ->
                val n = TextNormalizer.normalize(seg)
                numberSegmentRegex.find(n) == null && !semNumeroRegex.matches(n) && n.length >= 3
            }
        } else {
            val usable = segments.filter { seg ->
                val n = TextNormalizer.normalize(seg)
                numberSegmentRegex.find(n) == null && n.length >= 3
            }
            if (usable.isNotEmpty()) {
                bairroRaw = usable.last()
                if (usable.size > 1) poi = usable.dropLast(1).joinToString(", ")
            }
        }

        // Se a cidade capturada e igual ao "bairro" (ex.: "Belford Roxo, Belford Roxo"), mantem como bairro tambem
        val bairroNorm = bairroRaw?.let { normalizeLoose(it) }?.takeIf { it.length >= 3 }
        if (bairroNorm == null && city != null && city != "rio de janeiro") {
            bairroRaw = cityRaw
        }

        return AddressParts(
            raw = raw,
            poi = poi,
            streetRaw = streetRaw,
            street = streetRaw?.let { streetKeyOf(it) },
            number = number,
            bairroRaw = bairroRaw,
            bairro = bairroRaw?.let { normalizeLoose(it) }?.takeIf { it.length >= 3 },
            cityRaw = cityRaw,
            city = city
        )
    }

    /** Chave canonica de logradouro (vale para o texto do card e para os nomes do OpenStreetMap/Google). */
    fun streetKeyOf(name: String): StreetKey? {
        val tokens = normalizeLoose(name.replace(".", ". ")).split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var type: String? = null
        var startIdx = 0
        val first = tokens[0]
        if (streetTypeCanon.containsKey(first) && streetTypeCanon[first] != null && tokens.size >= 2) {
            type = streetTypeCanon[first]
            startIdx = 1
        }
        val coreTokens = tokens.drop(startIdx)
            .map { titleExpansions[it] ?: it }
            .map { tok -> phonetic.fold(tok) { acc, (a, b) -> if (acc == a) b else acc } }
            .filter { it !in connectors }
        val core = coreTokens.joinToString(" ").trim()
        if (core.length < 3) return null
        return StreetKey(type, core)
    }

    private fun typesCompatible(a: StreetKey, b: StreetKey): Boolean =
        a.type == null || b.type == null || a.type == b.type

    /**
     * Comparacao ESTRITA (usada para aceitar so pelo nome): mesmo tipo e MESMO nucleo
     * (depois de normalizar acentos, abreviacoes "Dr."/"Sra." e grafias "Luiz"/"Luis").
     * Nao tolera erro de letra: "Santa Lucia" (interna) nunca aprova "Santa Luzia" (outra rua).
     * Nome lido com erro de OCR cai na comparacao frouxa -> vira "rua de divisa" (exige numero).
     */
    fun strictMatch(a: StreetKey, b: StreetKey): Boolean {
        if (!typesCompatible(a, b)) return false
        return a.core == b.core
    }

    /**
     * Comparacao FROUXA (usada so para saber se a rua "toca" a zona): tolera mais erro de OCR
     * e nomes truncados. Nunca aprova sozinha.
     */
    fun looseMatch(a: StreetKey, b: StreetKey): Boolean {
        if (!typesCompatible(a, b)) return false
        if (strictMatch(a, b)) return true
        val len = minOf(a.core.length, b.core.length)
        if (len >= 8 && GeoMath.boundedLevenshtein(a.core, b.core, 2) <= 2) return true
        if (len >= 6) {
            val pa = " ${a.core} "
            val pb = " ${b.core} "
            if (pa.contains(pb) || pb.contains(pa)) return true
        }
        return false
    }

    /** Bairro do card x bairro do Geocoder ("Penha" ~ "Penha Circular" contam como compativeis). */
    fun bairroCompatible(cardBairro: String?, geoText: String?): Boolean? {
        if (cardBairro.isNullOrBlank() || geoText.isNullOrBlank()) return null
        val a = normalizeLoose(cardBairro)
        val b = normalizeLoose(geoText)
        if (a.isEmpty() || b.isEmpty()) return null
        return a == b || " $b ".contains(" $a ") || " $a ".contains(" $b ")
    }

    fun expandAbbreviations(raw: String): String {
        return raw
            .replace(Regex("""\bR\.\s*""", RegexOption.IGNORE_CASE), "Rua ")
            .replace(Regex("""\bAv\.\s*""", RegexOption.IGNORE_CASE), "Avenida ")
            .replace(Regex("""\bEstr?\.\s*""", RegexOption.IGNORE_CASE), "Estrada ")
            .replace(Regex("""\bTv\.\s*""", RegexOption.IGNORE_CASE), "Travessa ")
            .replace(Regex("""\bP[çc]a?\.\s*""", RegexOption.IGNORE_CASE), "Praça ")
            .replace(Regex("""\bDr\.\s*""", RegexOption.IGNORE_CASE), "Doutor ")
            .replace(Regex("""\bDra\.\s*""", RegexOption.IGNORE_CASE), "Doutora ")
            .replace(Regex("""\bEng\.\s*""", RegexOption.IGNORE_CASE), "Engenheiro ")
            .replace(Regex("""\bProf\.\s*""", RegexOption.IGNORE_CASE), "Professor ")
            .replace(Regex("""\bSrg\.\s*""", RegexOption.IGNORE_CASE), "Sargento ")
            .replace(Regex("""\bCel\.\s*""", RegexOption.IGNORE_CASE), "Coronel ")
            .replace(Regex("""\bBrg\.\s*""", RegexOption.IGNORE_CASE), "Brigadeiro ")
            .replace(Regex("""\bGen\.\s*""", RegexOption.IGNORE_CASE), "General ")
            .replace(Regex("""\bMal\.\s*""", RegexOption.IGNORE_CASE), "Marechal ")
            .replace(Regex("""\bAlm\.\s*""", RegexOption.IGNORE_CASE), "Almirante ")
            .replace(Regex("""\bCmte\.\s*""", RegexOption.IGNORE_CASE), "Comandante ")
            .replace(Regex("""\bPres\.\s*""", RegexOption.IGNORE_CASE), "Presidente ")
            .replace(Regex("""\bSra\.\s*""", RegexOption.IGNORE_CASE), "Senhora ")
            .replace(Regex("""\bSta\.\s*""", RegexOption.IGNORE_CASE), "Santa ")
            .replace(Regex("""\bSto\.\s*""", RegexOption.IGNORE_CASE), "Santo ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
