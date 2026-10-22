package com.opencloudgaming.opennow

import java.util.Locale

internal data class ServerLocation(
    val city: String,
    val country: String,
    val flag: String,
)

/**
 * Static map of GFN zone prefixes to a human-readable city/country.
 *
 * Zone ids look like `NP-AMS-06`, `NP-SJC6-04` or `NPA-TWM-TPE-02`. The
 * selector only needs the city token, so callers pass the full id and the
 * lookup tries every dash-separated token after the operator prefix.
 * Country names are Indonesian on purpose (NanaPlay's primary UI language).
 */
private val SERVER_LOCATIONS = mapOf(
    // North America
    "ASH" to ServerLocation("Ashburn", "Amerika Serikat", "🇺🇸"),
    "ATL" to ServerLocation("Atlanta", "Amerika Serikat", "🇺🇸"),
    "CHI" to ServerLocation("Chicago", "Amerika Serikat", "🇺🇸"),
    "DAL" to ServerLocation("Dallas", "Amerika Serikat", "🇺🇸"),
    "DEN" to ServerLocation("Denver", "Amerika Serikat", "🇺🇸"),
    "HNL" to ServerLocation("Honolulu", "Amerika Serikat", "🇺🇸"),
    "LAX" to ServerLocation("Los Angeles", "Amerika Serikat", "🇺🇸"),
    "MIA" to ServerLocation("Miami", "Amerika Serikat", "🇺🇸"),
    "NWK" to ServerLocation("Newark", "Amerika Serikat", "🇺🇸"),
    "PDX" to ServerLocation("Portland", "Amerika Serikat", "🇺🇸"),
    "PHX" to ServerLocation("Phoenix", "Amerika Serikat", "🇺🇸"),
    "SEA" to ServerLocation("Seattle", "Amerika Serikat", "🇺🇸"),
    "SJC" to ServerLocation("San Jose", "Amerika Serikat", "🇺🇸"),
    "SJC6" to ServerLocation("San Jose", "Amerika Serikat", "🇺🇸"),
    "MON" to ServerLocation("Montreal", "Kanada", "🇨🇦"),
    "YUL" to ServerLocation("Montreal", "Kanada", "🇨🇦"),
    "YVR" to ServerLocation("Vancouver", "Kanada", "🇨🇦"),
    "YYZ" to ServerLocation("Toronto", "Kanada", "🇨🇦"),
    // Europe
    "AMS" to ServerLocation("Amsterdam", "Belanda", "🇳🇱"),
    "ATH" to ServerLocation("Athens", "Yunani", "🇬🇷"),
    "BCN" to ServerLocation("Barcelona", "Spanyol", "🇪🇸"),
    "BER" to ServerLocation("Berlin", "Jerman", "🇩🇪"),
    "BRU" to ServerLocation("Brussels", "Belgia", "🇧🇪"),
    "BUD" to ServerLocation("Budapest", "Hungaria", "🇭🇺"),
    "BUH" to ServerLocation("Bucharest", "Rumania", "🇷🇴"),
    "CPH" to ServerLocation("Copenhagen", "Denmark", "🇩🇰"),
    "DUB" to ServerLocation("Dublin", "Irlandia", "🇮🇪"),
    "DUS" to ServerLocation("Düsseldorf", "Jerman", "🇩🇪"),
    "FRA" to ServerLocation("Frankfurt", "Jerman", "🇩🇪"),
    "FRK" to ServerLocation("Frankfurt", "Jerman", "🇩🇪"),
    "HEL" to ServerLocation("Helsinki", "Finlandia", "🇫🇮"),
    "IST" to ServerLocation("Istanbul", "Turki", "🇹🇷"),
    "LIS" to ServerLocation("Lisbon", "Portugal", "🇵🇹"),
    "LON" to ServerLocation("London", "Inggris", "🇬🇧"),
    "MAD" to ServerLocation("Madrid", "Spanyol", "🇪🇸"),
    "MIL" to ServerLocation("Milan", "Italia", "🇮🇹"),
    "MUC" to ServerLocation("Munich", "Jerman", "🇩🇪"),
    "OSL" to ServerLocation("Oslo", "Norwegia", "🇳🇴"),
    "PAR" to ServerLocation("Paris", "Prancis", "🇫🇷"),
    "PRG" to ServerLocation("Prague", "Ceko", "🇨🇿"),
    "ROM" to ServerLocation("Roma", "Italia", "🇮🇹"),
    "SOF" to ServerLocation("Sofia", "Bulgaria", "🇧🇬"),
    "STH" to ServerLocation("Stockholm", "Swedia", "🇸🇪"),
    "STO" to ServerLocation("Stockholm", "Swedia", "🇸🇪"),
    "VIE" to ServerLocation("Wina", "Austria", "🇦🇹"),
    "WAW" to ServerLocation("Warsaw", "Polandia", "🇵🇱"),
    "ZRH" to ServerLocation("Zurich", "Swiss", "🇨🇭"),
    // Middle East and Africa
    "CAI" to ServerLocation("Kairo", "Mesir", "🇪🇬"),
    "CPT" to ServerLocation("Cape Town", "Afrika Selatan", "🇿🇦"),
    "DXB" to ServerLocation("Dubai", "Uni Emirat Arab", "🇦🇪"),
    "EVN" to ServerLocation("Yerevan", "Armenia", "🇦🇲"),
    "JED" to ServerLocation("Jeddah", "Arab Saudi", "🇸🇦"),
    "JNB" to ServerLocation("Johannesburg", "Afrika Selatan", "🇿🇦"),
    "RUH" to ServerLocation("Riyadh", "Arab Saudi", "🇸🇦"),
    "TLV" to ServerLocation("Tel Aviv", "Israel", "🇮🇱"),
    "ESB" to ServerLocation("Ankara", "Turki", "🇹🇷"),
    // Asia
    "BKK" to ServerLocation("Bangkok", "Thailand", "🇹🇭"),
    "BLR" to ServerLocation("Bengaluru", "India", "🇮🇳"),
    "BOM" to ServerLocation("Mumbai", "India", "🇮🇳"),
    "CCU" to ServerLocation("Kolkata", "India", "🇮🇳"),
    "CGK" to ServerLocation("Jakarta", "Indonesia", "🇮🇩"),
    "DEL" to ServerLocation("New Delhi", "India", "🇮🇳"),
    "HAN" to ServerLocation("Hanoi", "Vietnam", "🇻🇳"),
    "HKG" to ServerLocation("Hong Kong", "Hong Kong", "🇭🇰"),
    "ICN" to ServerLocation("Seoul", "Korea Selatan", "🇰🇷"),
    "KUL" to ServerLocation("Kuala Lumpur", "Malaysia", "🇲🇾"),
    "MAA" to ServerLocation("Chennai", "India", "🇮🇳"),
    "MNL" to ServerLocation("Manila", "Filipina", "🇵🇭"),
    "SEL" to ServerLocation("Seoul", "Korea Selatan", "🇰🇷"),
    "SGN" to ServerLocation("Ho Chi Minh City", "Vietnam", "🇻🇳"),
    "SIN" to ServerLocation("Singapura", "Singapura", "🇸🇬"),
    "TPE" to ServerLocation("Taipei", "Taiwan", "🇹🇼"),
    "TYO" to ServerLocation("Tokyo", "Jepang", "🇯🇵"),
    // Oceania
    "ADL" to ServerLocation("Adelaide", "Australia", "🇦🇺"),
    "AKL" to ServerLocation("Auckland", "Selandia Baru", "🇳🇿"),
    "BNE" to ServerLocation("Brisbane", "Australia", "🇦🇺"),
    "MEL" to ServerLocation("Melbourne", "Australia", "🇦🇺"),
    "PER" to ServerLocation("Perth", "Australia", "🇦🇺"),
    "SYD" to ServerLocation("Sydney", "Australia", "🇦🇺"),
    // South America
    "BOG" to ServerLocation("Bogotá", "Kolombia", "🇨🇴"),
    "EZE" to ServerLocation("Buenos Aires", "Argentina", "🇦🇷"),
    "GIG" to ServerLocation("Rio de Janeiro", "Brasil", "🇧🇷"),
    "GRU" to ServerLocation("São Paulo", "Brasil", "🇧🇷"),
    "LIM" to ServerLocation("Lima", "Peru", "🇵🇪"),
    "MEX" to ServerLocation("Mexico City", "Meksiko", "🇲🇽"),
    "MVD" to ServerLocation("Montevideo", "Uruguay", "🇺🇾"),
    "SCL" to ServerLocation("Santiago", "Chili", "🇨🇱"),
    "UIO" to ServerLocation("Quito", "Ekuador", "🇪🇨"),
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

internal fun serverLocationLabel(zoneId: String?): String? =
    serverLocationForZoneId(zoneId)?.let { location ->
        "${location.city}, ${location.country} ${location.flag}"
    }
