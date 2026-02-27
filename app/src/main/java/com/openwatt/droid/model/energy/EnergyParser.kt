package com.openwatt.droid.model.energy

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses JSON responses from /api/energy/circuit and /api/energy/appliances
 * into typed Kotlin models.
 *
 * Handles:
 * - Scalar values (number)
 * - Three-phase arrays [sum, L1, L2, L3]
 * - Quantity objects {q: number, u: string}
 */
object EnergyParser {

    /**
     * Parse the /api/energy/circuit response.
     * Top level is a map of circuit ID -> circuit object.
     */
    fun parseCircuits(json: JsonObject): Map<String, Circuit> {
        val result = mutableMapOf<String, Circuit>()
        for ((id, element) in json.entrySet()) {
            if (element.isJsonObject) {
                result[id] = parseCircuit(id, element.asJsonObject)
            }
        }
        return result
    }

    /**
     * Parse the /api/energy/appliances response.
     * Top level is a map of appliance ID -> appliance object.
     */
    fun parseAppliances(json: JsonObject): Map<String, Appliance> {
        val result = mutableMapOf<String, Appliance>()
        for ((id, element) in json.entrySet()) {
            if (element.isJsonObject) {
                result[id] = parseAppliance(id, element.asJsonObject)
            }
        }
        return result
    }

    private fun parseCircuit(id: String, json: JsonObject): Circuit {
        val subCircuits = mutableMapOf<String, Circuit>()
        json.getAsJsonObject("sub_circuits")?.let { sub ->
            for ((subId, subElement) in sub.entrySet()) {
                if (subElement.isJsonObject) {
                    subCircuits[subId] = parseCircuit(subId, subElement.asJsonObject)
                }
            }
        }

        val appliances = mutableListOf<String>()
        json.getAsJsonArray("appliances")?.forEach { el ->
            if (el.isJsonPrimitive) appliances.add(el.asString)
        }

        return Circuit(
            id = id,
            name = json.getString("name"),
            type = json.getString("type"),
            meterData = parseMeterData(json.getAsJsonObject("meter_data")),
            maxCurrent = json.getInt("max_current"),
            appliances = appliances,
            subCircuits = subCircuits,
        )
    }

    private fun parseAppliance(id: String, json: JsonObject): Appliance {
        return Appliance(
            id = id,
            name = json.getString("name"),
            type = json.getString("type") ?: "unknown",
            enabled = json.getBoolean("enabled") ?: true,
            meterData = parseMeterData(json.getAsJsonObject("meter_data")),
            inverter = json.getAsJsonObject("inverter")?.let { parseInverter(it) },
            evse = json.getAsJsonObject("evse")?.let { parseEvse(it) },
            car = json.getAsJsonObject("car")?.let { parseCar(it) },
        )
    }

    private fun parseInverter(json: JsonObject): InverterData {
        val mpptList = mutableListOf<Mppt>()
        json.getAsJsonArray("mppt")?.forEach { el ->
            if (el.isJsonObject) {
                val mpptJson = el.asJsonObject
                mpptList.add(
                    Mppt(
                        id = mpptJson.getString("id") ?: "",
                        template = mpptJson.getString("template"),
                        soc = mpptJson.getDouble("soc"),
                        meterData = parseMeterData(mpptJson.getAsJsonObject("meter_data")),
                    )
                )
            }
        }
        return InverterData(
            ratedPower = json.getDouble("rated_power"),
            mppt = mpptList,
        )
    }

    private fun parseEvse(json: JsonObject): EvseData {
        return EvseData(connectedCar = json.getString("connected_car"))
    }

    private fun parseCar(json: JsonObject): CarData {
        return CarData(
            vin = json.getString("vin"),
            evse = json.getString("evse"),
        )
    }

    fun parseMeterData(json: JsonObject?): MeterData? {
        if (json == null) return null
        return MeterData(
            power = parsePhaseValue(json.get("power")),
            voltage = parsePhaseValue(json.get("voltage")),
            current = parsePhaseValue(json.get("current")),
            pf = parsePhaseValue(json.get("pf")),
            frequency = json.getDouble("frequency"),
            apparent = parsePhaseValue(json.get("apparent")),
            reactive = parsePhaseValue(json.get("reactive")),
            import = parsePhaseValue(json.get("import")),
            export = parsePhaseValue(json.get("export")),
            type = json.getString("type"),
        )
    }

    /**
     * Parse a value that might be:
     * - A number (scalar)
     * - An array [sum, L1, L2, L3] (three-phase)
     * - A quantity object {q: number, u: string}
     * - null
     */
    fun parsePhaseValue(element: JsonElement?): PhaseValue? {
        if (element == null || element.isJsonNull) return null

        // Quantity object: {q: number} or {q: number, u: string}
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val q = obj.get("q")
            if (q != null && q.isJsonPrimitive && q.asJsonPrimitive.isNumber) {
                return PhaseValue.Scalar(q.asDouble)
            }
            return null
        }

        // Three-phase array: [sum, L1, L2, L3]
        if (element.isJsonArray) {
            val arr = element.asJsonArray
            if (arr.size() >= 4) {
                return PhaseValue.ThreePhase(
                    sum = arr[0].safeDouble(),
                    l1 = arr[1].safeDouble(),
                    l2 = arr[2].safeDouble(),
                    l3 = arr[3].safeDouble(),
                )
            }
            // Shorter arrays: treat first element as scalar
            if (arr.size() >= 1) {
                return PhaseValue.Scalar(arr[0].safeDouble())
            }
            return null
        }

        // Scalar number
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            return PhaseValue.Scalar(element.asDouble)
        }

        return null
    }

    // Extension helpers for safe JSON extraction

    private fun JsonElement.safeDouble(): Double {
        return try {
            if (isJsonPrimitive && asJsonPrimitive.isNumber) asDouble else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun JsonObject.getString(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asString } catch (_: Exception) { null }
    }

    private fun JsonObject.getDouble(key: String): Double? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asDouble } catch (_: Exception) { null }
    }

    private fun JsonObject.getInt(key: String): Int? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asInt } catch (_: Exception) { null }
    }

    private fun JsonObject.getBoolean(key: String): Boolean? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try { el.asBoolean } catch (_: Exception) { null }
    }
}
