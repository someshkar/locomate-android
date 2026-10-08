package app.locomate.data

import org.json.JSONObject

data class StationSearchResult(val code: String, val name: String, val sourceLabel: String, val sourceUpdatedAt: String? = null)
data class StationTrainsResult(val station: StationSearchResult, val trains: List<TrainSearchResult>, val truncated: Boolean)
data class BetweenStationsResult(val from: StationSearchResult, val to: StationSearchResult,
    val trains: List<TrainSearchResult>, val truncated: Boolean)

internal fun decodeStation(row: JSONObject): StationSearchResult {
    val code = row.getString("code")
    val name = row.getString("name")
    require(RailStationCode.isValid(code) && name.isNotBlank()) { "Invalid station catalogue" }
    return StationSearchResult(code, name, row.getString("sourceLabel"),
        row.optString("sourceUpdatedAt").takeIf { it.isNotBlank() && it != "null" })
}

/** Catalogue codes include one-letter stations and internal hyphen segments such as NRL-DLS. */
object RailStationCode {
    fun isValid(code: String) = code.length in 1..10 && code.matches(Regex("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*"))
}

object StationSearch {
    // Fixed station shortcuts from the approved design; no live service or location is implied.
    val shortcuts = listOf("NDLS" to "New Delhi", "MMCT" to "Mumbai Central", "KOTA" to "Kota Jn", "BRC" to "Vadodara Jn")
        .map { (code, name) -> StationSearchResult(code, name, "Station shortcut") }
    fun previewStations(routes: List<RoutePreview>) = routes.flatMap { it.calls }.distinctBy { it.code }
        .map { StationSearchResult(it.code, it.name, "Historical route pack") }.sortedBy { it.code }
}
