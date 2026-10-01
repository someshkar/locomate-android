package app.locomate.data

import android.content.Context
import org.json.JSONObject

data class JourneyPlan(val boardingCode: String, val alightingCode: String) {
    companion object {
        fun default(route: RoutePreview): JourneyPlan =
            JourneyPlan(route.calls.firstOrNull()?.code ?: route.originCode,
                route.calls.lastOrNull()?.code ?: route.destinationCode)
    }

    fun isValidFor(route: RoutePreview): Boolean {
        val board = route.calls.indexOfFirst { it.code == boardingCode }
        val leave = route.calls.indexOfFirst { it.code == alightingCode }
        return board >= 0 && leave > board
    }
}

/** Personal stop choices stay on this device and never enter a gateway request. */
class JourneyPlanStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("locomate.plans", Context.MODE_PRIVATE)

    fun load(route: RoutePreview): JourneyPlan {
        val default = JourneyPlan.default(route)
        val value = prefs.getString(key(route), null) ?: return default
        return runCatching {
            val json = JSONObject(value)
            JourneyPlan(json.getString("boarding"), json.getString("alighting"))
        }.getOrNull()?.takeIf { it.isValidFor(route) } ?: default
    }

    fun save(route: RoutePreview, plan: JourneyPlan) {
        require(plan.isValidFor(route)) { "Boarding must precede alighting" }
        prefs.edit().putString(key(route), JSONObject()
            .put("boarding", plan.boardingCode)
            .put("alighting", plan.alightingCode)
            .toString()).apply()
    }

    private fun key(route: RoutePreview): String =
        if (route.isPreview) "preview:${route.trainNumber}"
        else "run:${route.trainNumber}:${route.runDate}"
}
