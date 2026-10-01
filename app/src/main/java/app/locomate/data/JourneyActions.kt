package app.locomate.data

fun SavedJourney.openUnavailableReason(): String? = when {
    preview -> null
    originDate == null -> "Origin date missing. Find this train in Search and choose its origin date."
    JourneyAlertLink.fromRunId("$trainNumber:$originDate") == null ->
        "Saved run details are invalid. Find this train in Search and choose its origin date."
    else -> null
}

/** Share the exact service date; a historical preview never supplies an actionable run link. */
fun journeyShareText(route: RoutePreview, plan: JourneyPlan?): String {
    val selected = plan?.takeIf { it.isValidFor(route) } ?: JourneyPlan.default(route)
    val board = route.calls.firstOrNull { it.code == selected.boardingCode }
    val leave = route.calls.firstOrNull { it.code == selected.alightingCode }
    val reference = if (route.isPreview) null else JourneyAlertLink.fromRunId("${route.trainNumber}:${route.runDate}")
    return buildString {
        appendLine("${route.trainNumber} · ${route.displayName}")
        appendLine("${board?.name ?: route.originName} → ${leave?.name ?: route.destinationName}")
        if (route.isPreview) appendLine("Historical preview · not a live journey")
        else appendLine(reference?.let { "Origin date (India): ${it.serviceDate}" } ?: "Origin date unavailable")
        append("${route.statusLabel}. ${route.sourceDetail}")
        reference?.let { append("\n${it.url}") }
    }
}
