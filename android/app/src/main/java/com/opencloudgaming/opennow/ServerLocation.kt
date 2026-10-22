package com.opencloudgaming.opennow

import java.util.Locale

internal data class ServerLocation(
    val city: String,
    val countryCode: String,
    val flag: String,
)

/**
 * Static map of GFN zone prefixes to a human-readable city/country.
 *
 * Zone ids look like `NP-AMS-06`, `NP-SJC6-04` or `NPA-TWM-TPE-02`. The
 * selector only needs the city token, so callers pass the full id and the
 * lookup tries every dash-separated token after the operator prefix.
 * Country names are localized at render time: the map stores an ISO country
 * code and [serverLocationLabel] chooses Indonesian or English from the active
 * app language. City names stay as stored so the location remains compact.
 */
private val SERVER_LOCATIONS = mapOf(
    // North America
    "ASH" to ServerLocation("Ashburn", "US", "🇺🇸"),
    "ATL" to ServerLocation("Atlanta", "US", "🇺🇸"),
    "CHI" to ServerLocation("Chicago", "US", "🇺🇸"),
    "DAL" to ServerLocation("Dallas", "US", "🇺🇸"),
    "DEN" to ServerLocation("Denver", "US", "🇺🇸"),
    "HNL" to ServerLocation("Honolulu", "US", "🇺🇸"),
    "LAX" to ServerLocation("Los Angeles", "US", "🇺🇸"),
    "MIA" to ServerLocation("Miami", "US", "🇺🇸"),
    "NWK" to ServerLocation("Newark", "US", "🇺🇸"),
    "PDX" to ServerLocation("Portland", "US", "🇺🇸"),
    "PHX" to ServerLocation("Phoenix", "US", "🇺🇸"),
    "SEA" to ServerLocation("Seattle", "US", "🇺🇸"),
    "SJC" to ServerLocation("San Jose", "US", "🇺🇸"),
    "SJC6" to ServerLocation("San Jose", "US", "🇺🇸"),
    "MON" to ServerLocation("Montreal", "CA", "🇨🇦"),
    "YUL" to ServerLocation("Montreal", "CA", "🇨🇦"),
    "YVR" to ServerLocation("Vancouver", "CA", "🇨🇦"),
    "YYZ" to ServerLocation("Toronto", "CA", "🇨🇦"),
    // Europe
    "AMS" to ServerLocation("Amsterdam", "NL", "🇳🇱"),
    "ATH" to ServerLocation("Athens", "GR", "🇬🇷"),
    "BCN" to ServerLocation("Barcelona", "ES", "🇪🇸"),
    "BER" to ServerLocation("Berlin", "DE", "🇩🇪"),
    "BRU" to ServerLocation("Brussels", "BE", "🇧🇪"),
    "BUD" to ServerLocation("Budapest", "HU", "🇭🇺"),
    "BUH" to ServerLocation("Bucharest", "RO", "🇷🇴"),
    "CPH" to ServerLocation("Copenhagen", "DK", "🇩🇰"),
    "DUB" to ServerLocation("Dublin", "IE", "🇮🇪"),
    "DUS" to ServerLocation("Düsseldorf", "DE", "🇩🇪"),
    "FRA" to ServerLocation("Frankfurt", "DE", "🇩🇪"),
    "FRK" to ServerLocation("Frankfurt", "DE", "🇩🇪"),
    "HEL" to ServerLocation("Helsinki", "FI", "🇫🇮"),
    "IST" to ServerLocation("Istanbul", "TR", "🇹🇷"),
    "LIS" to ServerLocation("Lisbon", "PT", "🇵🇹"),
    "LON" to ServerLocation("London", "GB", "🇬🇧"),
    "MAD" to ServerLocation("Madrid", "ES", "🇪🇸"),
    "MIL" to ServerLocation("Milan", "IT", "🇮🇹"),
    "MUC" to ServerLocation("Munich", "DE", "🇩🇪"),
    "OSL" to ServerLocation("Oslo", "NO", "🇳🇴"),
    "PAR" to ServerLocation("Paris", "FR", "🇫🇷"),
    "PRG" to ServerLocation("Prague", "CZ", "🇨🇿"),
    "ROM" to ServerLocation("Roma", "IT", "🇮🇹"),
    "SOF" to ServerLocation("Sofia", "BG", "🇧🇬"),
    "STH" to ServerLocation("Stockholm", "SE", "🇸🇪"),
    "STO" to ServerLocation("Stockholm", "SE", "🇸🇪"),
    "VIE" to ServerLocation("Wina", "AT", "🇦🇹"),
    "WAW" to ServerLocation("Warsaw", "PL", "🇵🇱"),
    "ZRH" to ServerLocation("Zurich", "CH", "🇨🇭"),
    // Middle East and Africa
    "CAI" to ServerLocation("Kairo", "EG", "🇪🇬"),
    "CPT" to ServerLocation("Cape Town", "ZA", "🇿🇦"),
    "DXB" to ServerLocation("Dubai", "AE", "🇦🇪"),
    "EVN" to ServerLocation("Yerevan", "AM", "🇦🇲"),
    "JED" to ServerLocation("Jeddah", "SA", "🇸🇦"),
    "JNB" to ServerLocation("Johannesburg", "ZA", "🇿🇦"),
    "RUH" to ServerLocation("Riyadh", "SA", "🇸🇦"),
    "TLV" to ServerLocation("Tel Aviv", "IL", "🇮🇱"),
    "ESB" to ServerLocation("Ankara", "TR", "🇹🇷"),
    // Asia
    "BKK" to ServerLocation("Bangkok", "TH", "🇹🇭"),
    "BLR" to ServerLocation("Bengaluru", "IN", "🇮🇳"),
    "BOM" to ServerLocation("Mumbai", "IN", "🇮🇳"),
    "CCU" to ServerLocation("Kolkata", "IN", "🇮🇳"),
    "CGK" to ServerLocation("Jakarta", "ID", "🇮🇩"),
    "DEL" to ServerLocation("New Delhi", "IN", "🇮🇳"),
    "HAN" to ServerLocation("Hanoi", "VN", "🇻🇳"),
    "HKG" to ServerLocation("Hong Kong", "HK", "🇭🇰"),
    "ICN" to ServerLocation("Seoul", "KR", "🇰🇷"),
    "KUL" to ServerLocation("Kuala Lumpur", "MY", "🇲🇾"),
    "MAA" to ServerLocation("Chennai", "IN", "🇮🇳"),
    "MNL" to ServerLocation("Manila", "PH", "🇵🇭"),
    "SEL" to ServerLocation("Seoul", "KR", "🇰🇷"),
    "SGN" to ServerLocation("Ho Chi Minh City", "VN", "🇻🇳"),
    "SIN" to ServerLocation("Singapura", "SG", "🇸🇬"),
    "TPE" to ServerLocation("Taipei", "TW", "🇹🇼"),
    "TYO" to ServerLocation("Tokyo", "JP", "🇯🇵"),
    // Oceania
    "ADL" to ServerLocation("Adelaide", "AU", "🇦🇺"),
    "AKL" to ServerLocation("Auckland", "NZ", "🇳🇿"),
    "BNE" to ServerLocation("Brisbane", "AU", "🇦🇺"),
    "MEL" to ServerLocation("Melbourne", "AU", "🇦🇺"),
    "PER" to ServerLocation("Perth", "AU", "🇦🇺"),
    "SYD" to ServerLocation("Sydney", "AU", "🇦🇺"),
    // South America
    "BOG" to ServerLocation("Bogotá", "CO", "🇨🇴"),
    "EZE" to ServerLocation("Buenos Aires", "AR", "🇦🇷"),
    "GIG" to ServerLocation("Rio de Janeiro", "BR", "🇧🇷"),
    "GRU" to ServerLocation("São Paulo", "BR", "🇧🇷"),
    "LIM" to ServerLocation("Lima", "PE", "🇵🇪"),
    "MEX" to ServerLocation("Mexico City", "MX", "🇲🇽"),
    "MVD" to ServerLocation("Montevideo", "UY", "🇺🇾"),
    "SCL" to ServerLocation("Santiago", "CL", "🇨🇱"),
    "UIO" to ServerLocation("Quito", "EC", "🇪🇨"),
)

private data class CountryNames(
    val english: String,
    val indonesian: String,
)

private val COUNTRY_NAMES = mapOf(
    "US" to CountryNames("United States", "Amerika Serikat"),
    "CA" to CountryNames("Canada", "Kanada"),
    "NL" to CountryNames("Netherlands", "Belanda"),
    "GR" to CountryNames("Greece", "Yunani"),
    "ES" to CountryNames("Spain", "Spanyol"),
    "DE" to CountryNames("Germany", "Jerman"),
    "BE" to CountryNames("Belgium", "Belgia"),
    "HU" to CountryNames("Hungary", "Hungaria"),
    "RO" to CountryNames("Romania", "Rumania"),
    "DK" to CountryNames("Denmark", "Denmark"),
    "IE" to CountryNames("Ireland", "Irlandia"),
    "FI" to CountryNames("Finland", "Finlandia"),
    "TR" to CountryNames("Turkey", "Turki"),
    "PT" to CountryNames("Portugal", "Portugal"),
    "GB" to CountryNames("United Kingdom", "Inggris"),
    "IT" to CountryNames("Italy", "Italia"),
    "NO" to CountryNames("Norway", "Norwegia"),
    "FR" to CountryNames("France", "Prancis"),
    "CZ" to CountryNames("Czechia", "Ceko"),
    "BG" to CountryNames("Bulgaria", "Bulgaria"),
    "SE" to CountryNames("Sweden", "Swedia"),
    "AT" to CountryNames("Austria", "Austria"),
    "PL" to CountryNames("Poland", "Polandia"),
    "CH" to CountryNames("Switzerland", "Swiss"),
    "EG" to CountryNames("Egypt", "Mesir"),
    "ZA" to CountryNames("South Africa", "Afrika Selatan"),
    "AE" to CountryNames("United Arab Emirates", "Uni Emirat Arab"),
    "AM" to CountryNames("Armenia", "Armenia"),
    "SA" to CountryNames("Saudi Arabia", "Arab Saudi"),
    "IL" to CountryNames("Israel", "Israel"),
    "TH" to CountryNames("Thailand", "Thailand"),
    "IN" to CountryNames("India", "India"),
    "ID" to CountryNames("Indonesia", "Indonesia"),
    "VN" to CountryNames("Vietnam", "Vietnam"),
    "HK" to CountryNames("Hong Kong", "Hong Kong"),
    "KR" to CountryNames("South Korea", "Korea Selatan"),
    "MY" to CountryNames("Malaysia", "Malaysia"),
    "PH" to CountryNames("Philippines", "Filipina"),
    "SG" to CountryNames("Singapore", "Singapura"),
    "TW" to CountryNames("Taiwan", "Taiwan"),
    "JP" to CountryNames("Japan", "Jepang"),
    "AU" to CountryNames("Australia", "Australia"),
    "NZ" to CountryNames("New Zealand", "Selandia Baru"),
    "CO" to CountryNames("Colombia", "Kolombia"),
    "AR" to CountryNames("Argentina", "Argentina"),
    "BR" to CountryNames("Brazil", "Brasil"),
    "PE" to CountryNames("Peru", "Peru"),
    "MX" to CountryNames("Mexico", "Meksiko"),
    "UY" to CountryNames("Uruguay", "Uruguay"),
    "CL" to CountryNames("Chile", "Chili"),
    "EC" to CountryNames("Ecuador", "Ekuador"),
)

internal fun serverLocationForZoneId(zoneId: String?): ServerLocation? {
    val normalized = zoneId?.trim()?.uppercase(Locale.US).orEmpty()
    if (normalized.isBlank()) return null
    val body = normalized.removePrefix("NPA-").removePrefix("NP-")
    for (token in body.split('-', '_', ' ')) {
        val code = token.take(3)
        SERVER_LOCATIONS[code]?.let { return it }
        SERVER_LOCATIONS[token]?.let { return it }
    }
    return null
}

internal fun serverLocationLabel(zoneId: String?, appLanguage: String?): String? =
    serverLocationForZoneId(zoneId)?.let { location ->
        "${location.city}, ${countryDisplayName(location.countryCode, appLanguage)} ${location.flag}"
    }

private fun countryDisplayName(countryCode: String, appLanguage: String?): String {
    val names = COUNTRY_NAMES[countryCode] ?: return countryCode
    return if (appLanguage == "in") names.indonesian else names.english
}
