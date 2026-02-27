package com.openwatt.droid.model.energy

/** Detail level for progressive disclosure of meter data. */
enum class DetailLevel {
    SIMPLE, DETAIL, ADVANCED;

    fun next(): DetailLevel = when (this) {
        SIMPLE -> DETAIL
        DETAIL -> ADVANCED
        ADVANCED -> ADVANCED
    }

    fun previous(): DetailLevel = when (this) {
        SIMPLE -> SIMPLE
        DETAIL -> SIMPLE
        ADVANCED -> DETAIL
    }
}

/** Chart period selection. */
enum class ChartPeriod(val label: String) {
    REALTIME("Realtime"),
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
}

/**
 * Power state for display — label, CSS-like class name, directional arrow.
 * The 10W dead-band prevents flickering near zero.
 */
data class PowerState(
    val label: String,
    val stateClass: String,
    val arrow: String,
)

/**
 * Flattened appliance summary for the ranked consumption list.
 */
data class ApplianceSummary(
    val id: String,
    val name: String,
    val type: String,
    val icon: String,
    val currentPower: Double,
    val dailyImport: Double,
    val dailyExport: Double,
    val isProducing: Boolean,
)

/**
 * Period total stat for the summary cards.
 */
data class PeriodTotal(
    val label: String,
    val value: String,
    val costText: String?,
    val isStub: Boolean,
)

/**
 * Chart data for the power chart.
 */
data class ChartData(
    val timestamps: List<Long>,
    val power: List<Float>,
)
