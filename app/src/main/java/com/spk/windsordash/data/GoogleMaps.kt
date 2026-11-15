package com.spk.windsordash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LatLng(val lat: Double, val lng: Double)

/** One sampled point along a route: distance so far, elevation, and the average speed of the step it's in. */
data class RoutePoint(val distanceM: Double, val latLng: LatLng, val elevationM: Double, val speedKmh: Double)

data class RouteSummary(val distanceM: Double, val durationMin: Double, val points: List<RoutePoint>)

/** A DC/AC charger Places found near the route. */
data class ChargerPoi(
    val placeId: String,
    val name: String,
    val at: LatLng,
    val address: String?,
    val alongRouteM: Double,
    val detourM: Double,
    val rating: Double? = null,
    val openNow: Boolean? = null,
)

class MapsException(message: String) : Exception(message)

/**
 * Minimal client for the Google Maps Platform APIs the route planner needs: Geocoding, Directions
 * and Elevation (legacy, key-as-query-param APIs), and Places Nearby Search on the newer
 * Places API (New) — many projects created after March 2025 can't enable the old Places endpoint
 * at all, so this talks to the one Google actually points you at. Plain HttpURLConnection +
 * org.json — no new Gradle dependency. All calls are suspend/IO; each throws [MapsException] with
 * a message fit to show the user directly (bad key, missing API, zero results, no network).
 */
object GoogleMaps {
    private const val BASE = "https://maps.googleapis.com/maps/api"

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw MapsException("Network error ($code) talking to Google Maps.")
            JSONObject(body)
        } catch (e: MapsException) {
            throw e
        } catch (e: Exception) {
            throw MapsException("Couldn't reach Google Maps: ${e.message ?: e::class.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    /** POST with a JSON body, for the newer, header-authenticated Places API (New). */
    private suspend fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: "{}"
                val j = if (text.isBlank()) JSONObject() else JSONObject(text)
                val err = j.optJSONObject("error")
                if (err != null) {
                    val status = err.optString("status", "ERROR")
                    val msg = err.optString("message", "")
                    throw MapsException(
                        if (status == "PERMISSION_DENIED" || code == 403)
                            "Google rejected the Places (New) request — enable \"Places API (New)\" for this key in Cloud Console.${if (msg.isNotEmpty()) " ($msg)" else ""}"
                        else "Google Places error: $status${if (msg.isNotEmpty()) " ($msg)" else ""}"
                    )
                }
                if (code !in 200..299) throw MapsException("Network error ($code) talking to Google Places.")
                j
            } catch (e: MapsException) {
                throw e
            } catch (e: Exception) {
                throw MapsException("Couldn't reach Google Places: ${e.message ?: e::class.simpleName}")
            } finally {
                conn.disconnect()
            }
        }

    private fun checkStatus(j: JSONObject) {
        val status = j.optString("status", "UNKNOWN_ERROR")
        if (status == "OK" || status == "ZERO_RESULTS") return
        val msg = j.optString("error_message", "")
        throw MapsException(
            when (status) {
                "REQUEST_DENIED" -> "Google Maps rejected the request — check the API key and that Directions/Elevation/Places/Geocoding are enabled for it.${if (msg.isNotEmpty()) " ($msg)" else ""}"
                "OVER_QUERY_LIMIT" -> "Hit the Google Maps quota for this key — check billing is enabled."
                "INVALID_REQUEST" -> "Google Maps said the request was invalid.${if (msg.isNotEmpty()) " ($msg)" else ""}"
                else -> "Google Maps error: $status${if (msg.isNotEmpty()) " ($msg)" else ""}"
            }
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun geocode(address: String, apiKey: String): LatLng {
        val j = get("$BASE/geocode/json?address=${enc(address)}&key=$apiKey")
        checkStatus(j)
        val results = j.optJSONArray("results")
        if (results == null || results.length() == 0) throw MapsException("Couldn't find \"$address\".")
        val loc = results.getJSONObject(0).getJSONObject("geometry").getJSONObject("location")
        return LatLng(loc.getDouble("lat"), loc.getDouble("lng"))
    }

    /** Route distance/duration plus a decoded, distance-stamped point list (one per leg step) for the energy model. */
    suspend fun directions(origin: LatLng, destination: LatLng, apiKey: String): RouteSummary {
        val j = get(
            "$BASE/directions/json?origin=${origin.lat},${origin.lng}&destination=${destination.lat},${destination.lng}" +
                "&key=$apiKey"
        )
        checkStatus(j)
        val routes = j.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw MapsException("Google Maps found no route between those points.")
        val legs = routes.getJSONObject(0).getJSONArray("legs")
        val points = ArrayList<RoutePoint>()
        var dist = 0.0
        var durationS = 0.0
        var distM = 0.0
        points += RoutePoint(0.0, origin, 0.0, 0.0)
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            distM += leg.getJSONObject("distance").getDouble("value")
            durationS += leg.getJSONObject("duration").getDouble("value")
            val steps = leg.getJSONArray("steps")
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val stepDistM = step.getJSONObject("distance").getDouble("value")
                val stepDurS = step.getJSONObject("duration").getDouble("value").coerceAtLeast(1.0)
                val speedKmh = (stepDistM / 1000.0) / (stepDurS / 3600.0)
                val end = step.getJSONObject("end_location")
                dist += stepDistM
                points += RoutePoint(dist, LatLng(end.getDouble("lat"), end.getDouble("lng")), 0.0, speedKmh)
            }
        }
        return RouteSummary(distM, durationS / 60.0, points)
    }

    /** Fills in elevation for each point, batched (Google caps URL length, so ~100 points per call). */
    suspend fun withElevations(points: List<RoutePoint>, apiKey: String): List<RoutePoint> {
        if (points.isEmpty()) return points
        val out = ArrayList<RoutePoint>(points.size)
        points.chunked(100).forEach { batch ->
            val locs = batch.joinToString("|") { "${it.latLng.lat},${it.latLng.lng}" }
            val j = get("$BASE/elevation/json?locations=$locs&key=$apiKey")
            checkStatus(j)
            val results = j.optJSONArray("results")
            batch.forEachIndexed { i, p ->
                val elev = results?.optJSONObject(i)?.optDouble("elevation", 0.0) ?: 0.0
                out += p.copy(elevationM = elev)
            }
        }
        return out
    }

    /**
     * DC/AC chargers near the route, sampled every [everyM] along it (radius [radiusM] per sample),
     * deduplicated by place_id and tagged with how far along the route and how far off it they sit.
     */
    suspend fun chargersAlongRoute(
        points: List<RoutePoint>,
        apiKey: String,
        everyM: Double = 40_000.0,
        radiusM: Int = 15_000,
    ): List<ChargerPoi> {
        if (points.isEmpty()) return emptyList()
        val total = points.last().distanceM
        val samples = generateSequence(0.0) { it + everyM }.takeWhile { it <= total }.toList()
            .ifEmpty { listOf(0.0) } + total
        val byId = LinkedHashMap<String, ChargerPoi>()
        for (target in samples.distinct()) {
            val near = points.minByOrNull { kotlin.math.abs(it.distanceM - target) } ?: continue
            val body = JSONObject().apply {
                put("includedTypes", org.json.JSONArray(listOf("electric_vehicle_charging_station")))
                put("maxResultCount", 20)
                put(
                    "locationRestriction",
                    JSONObject().put(
                        "circle",
                        JSONObject()
                            .put("center", JSONObject().put("latitude", near.latLng.lat).put("longitude", near.latLng.lng))
                            .put("radius", radiusM.coerceAtMost(50_000).toDouble()),
                    ),
                )
            }
            val j = post(
                "https://places.googleapis.com/v1/places:searchNearby",
                body,
                mapOf(
                    "Content-Type" to "application/json",
                    "X-Goog-Api-Key" to apiKey,
                    "X-Goog-FieldMask" to "places.id,places.displayName,places.location,places.formattedAddress,places.rating,places.currentOpeningHours.openNow",
                ),
            )
            val results = j.optJSONArray("places") ?: continue
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val id = r.optString("id")
                if (id.isEmpty() || byId.containsKey(id)) continue
                val loc = r.optJSONObject("location") ?: continue
                val at = LatLng(loc.getDouble("latitude"), loc.getDouble("longitude"))
                val along = points.minByOrNull { haversineM(it.latLng, at) }?.distanceM ?: near.distanceM
                val onRoutePoint = points.minByOrNull { kotlin.math.abs(it.distanceM - along) }?.latLng ?: near.latLng
                byId[id] = ChargerPoi(
                    placeId = id,
                    name = r.optJSONObject("displayName")?.optString("text") ?: "Charger",
                    at = at,
                    address = r.optString("formattedAddress", null),
                    alongRouteM = along,
                    detourM = haversineM(onRoutePoint, at),
                    rating = r.optDouble("rating", Double.NaN).takeIf { !it.isNaN() },
                    openNow = r.optJSONObject("currentOpeningHours")?.optBoolean("openNow"),
                )
            }
        }
        return byId.values.sortedBy { it.alongRouteM }
    }

    private fun haversineM(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(la1) * kotlin.math.cos(la2) * kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }
}
